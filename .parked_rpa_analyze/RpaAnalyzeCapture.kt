/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa.analyze

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.verlintas.baic2.floatball.auto.FrameSource
import com.verlintas.rpa.model.RpaBounds
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 布局分析的一次冻结采集结果。
 *
 * [frame] 是覆盖层出现**之前**抓到的画面；[windows] 是同一时刻的多窗口节点树。
 * 两者都可能单独缺失：无 Shizuku / 投影授权时仍应能看节点框，只有节点树不可用时
 * 分析才没有意义（由 [RpaAnalyzeCapture] 返回失败）。
 */
internal data class RpaAnalyzeSnapshot(
    val frame: Bitmap?,
    val frameSource: String,
    val frameErrorCode: String?,
    val windows: List<RpaAnalyzeWindow>,
    val activeWindowIndex: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val capturedAt: Long,
) {
    val frameWidth: Int get() = frame?.width ?: 0
    val frameHeight: Int get() = frame?.height ?: 0

    fun windowAt(index: Int): RpaAnalyzeWindow? = windows.getOrNull(index)
}

/**
 * 冻结帧 + 多窗口节点树采集。
 *
 * **为什么不用 `AssistsCore.NodeTree`**：它不携带 `isEditable`，选择器生成会把
 * 输入框退化成「className 选择器」；而且 `toNodeTree()` 递归深度与节点数都无
 * 上限，微信这类超大界面容易在采集时阻塞主线程。这里按窗口直接用
 * [AccessibilityNodeInfo] 做有预算的窄树转换（[MAX_RAW_NODES]），后续
 * [RpaNodeIndex] 再限 800 个可绘制节点。
 *
 * 采集必须在覆盖层挂载**之前**执行（或先摘掉覆盖层再刷新），否则无障碍截图
 * 会把分析器自己也画进去。窗口里的自身包名节点也会被过滤，双保险。
 */
@Singleton
class RpaAnalyzeCapture @Inject constructor(
    private val frameSource: FrameSource,
) {

    suspend fun capture(
        screenWidth: Int,
        screenHeight: Int,
        selfPackage: String,
    ): Result<RpaAnalyzeSnapshot> {
        val startedAt = System.currentTimeMillis()
        val captured = runCatching { frameSource.grabFrameWithFreshness().getOrNull() }
            .getOrNull()
        val windows = try {
            withContext(Dispatchers.Main) { collectWindows(selfPackage) }
        } catch (t: Throwable) {
            return Result.failure(
                IllegalStateException(
                    if (t.message?.startsWith(ERROR_A11Y_UNAVAILABLE) == true) {
                        ERROR_A11Y_UNAVAILABLE
                    } else {
                        ERROR_NODE_TREE
                    },
                    t,
                ),
            )
        }
        if (windows.isEmpty()) {
            return Result.failure(IllegalStateException(ERROR_NO_WINDOWS))
        }
        val activeIndex = chooseActiveWindow(windows)
        return Result.success(
            RpaAnalyzeSnapshot(
                frame = captured?.bitmap,
                frameSource = captured?.source.orEmpty(),
                frameErrorCode = if (captured == null) ERROR_NO_FRAME else null,
                windows = windows,
                activeWindowIndex = activeIndex,
                screenWidth = screenWidth.coerceAtLeast(1),
                screenHeight = screenHeight.coerceAtLeast(1),
                capturedAt = startedAt,
            ),
        )
    }

    /**
     * 读取 `service.windows`：`isFocused` 窗口即前台窗口，同时保留输入法 /
     * 系统窗口供「切换窗口」使用。自身包名窗口（悬浮球/编辑器）一律剔除。
     */
    private fun collectWindows(selfPackage: String): List<RpaAnalyzeWindow> {
        val service = AssistsService.getOrNull()
            ?: throw IllegalStateException(ERROR_A11Y_UNAVAILABLE)
        val rawWindows = service.windows
            ?: throw IllegalStateException(ERROR_NO_WINDOWS)
        val out = ArrayList<RpaAnalyzeWindow>(rawWindows.size)
        var focusedCollectedIndex = -1
        for (window in rawWindows) {
            var root: AccessibilityNodeInfo? = null
            try {
                root = runCatching { window.root }.getOrNull() ?: continue
                val packageName = runCatching { root.packageName?.toString().orEmpty() }
                    .getOrDefault("")
                if (packageName.isBlank() || packageName == selfPackage) continue
                val tree = runCatching {
                    root.toRawNode(Budget(MAX_RAW_NODES), depth = 0)
                }.getOrNull() ?: continue
                if (tree.bounds.isEmpty && tree.children.isEmpty()) continue
                if (runCatching { window.isFocused }.getOrDefault(false)) {
                    focusedCollectedIndex = out.size
                }
                out += RpaAnalyzeWindow(
                    index = out.size,
                    packageName = packageName,
                    label = windowLabel(window.type, packageName),
                    root = tree,
                )
            } finally {
                runCatching { window.recycle() }
            }
        }
        if (focusedCollectedIndex >= 0) {
            // 把前台窗口挪到首位：默认视图、窗口选择器的第一行都更符合直觉。
            val focused = out.removeAt(focusedCollectedIndex)
            out.add(0, focused.copy(index = 0))
            for (i in 1 until out.size) out[i] = out[i].copy(index = i)
        }
        return out
    }

    private fun chooseActiveWindow(windows: List<RpaAnalyzeWindow>): Int {
        val focusedPackage = runCatching {
            AssistsCore.getPackageName(AssistsCore.NodeLookupScope.AllWindows)
        }.getOrDefault("")
        if (focusedPackage.isNotBlank()) {
            windows.firstOrNull { it.packageName == focusedPackage }?.let { return it.index }
        }
        return 0
    }

    private fun windowLabel(type: Int, packageName: String): String = when (type) {
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "$packageName · 输入法"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "$packageName · 系统"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "$packageName · 浮层"
        else -> packageName
    }

    /** 有预算的节点窄树转换：超预算 / 超深度只截断分支，不抛弃整棵树。 */
    private fun AccessibilityNodeInfo.toRawNode(budget: Budget, depth: Int): RpaRawNode {
        budget.remaining--
        val rect = Rect()
        getBoundsInScreen(rect)
        val children = ArrayList<RpaRawNode>(childCount)
        if (budget.remaining > 0 && depth < MAX_RAW_DEPTH) {
            for (i in 0 until childCount) {
                if (budget.remaining <= 0) break
                val child = runCatching { getChild(i) }.getOrNull() ?: continue
                children += child.toRawNode(budget, depth + 1)
            }
        }
        return RpaRawNode(
            packageName = packageName?.toString().orEmpty(),
            className = className?.toString().orEmpty(),
            text = text?.toString().orEmpty(),
            description = contentDescription?.toString().orEmpty(),
            viewId = viewIdResourceName.orEmpty(),
            hintText = hintText?.toString().orEmpty(),
            bounds = RpaBounds(
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
            ),
            clickable = isClickable,
            editable = isEditable,
            scrollable = isScrollable,
            focused = isFocused,
            enabled = isEnabled,
            visibleToUser = isVisibleToUser,
            children = children,
        )
    }

    private class Budget(var remaining: Int)

    companion object {
        const val ERROR_A11Y_UNAVAILABLE = "a11y_unavailable"
        const val ERROR_NODE_TREE = "node_tree_unavailable"
        const val ERROR_NO_WINDOWS = "no_windows"
        const val ERROR_NO_FRAME = "no_frame"

        /** 窄树预算：足够覆盖微信主界面，又不会让主线程 binder 调用失控。 */
        private const val MAX_RAW_NODES = 1200
        private const val MAX_RAW_DEPTH = 64
    }
}
