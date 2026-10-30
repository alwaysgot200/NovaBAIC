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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa.analyze

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.verlintas.baic2.floatball.auto.FrameSource
import com.verlintas.baic2.floatball.auto.TouchExplorationGate
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次「冻结帧 + 多窗口节点树」采集结果。截图失败不阻断分析（[frameErrorCode] 非空）。 */
data class RpaAnalyzeSnapshot(
    val frame: Bitmap?,
    val frameSource: String,
    val frameErrorCode: String?,
    val windows: List<RpaAnalyzeWindow>,
    val activeWindowIndex: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodeCount: Int,
    val capturedAt: Long,
) {
    val hasFrame: Boolean get() = frame != null
}

/**
 * 布局分析采集器（方案 §5.2）：
 * 1. **先抓帧**——控制器在调用前已把覆盖层摘掉，这里拿到的是目标 App 的干净画面；
 * 2. **再在主线程读窗口树**——[AccessibilityService.getWindows] 与
 *    [AccessibilityNodeInfo.getChild] 必须在服务线程（主线程）访问；
 * 3. 过滤空包名与自身包名（分析器自己的覆盖层不在 `windows` 中，但服务若
 *    提前挂过其他浮层仍需剔除），聚焦窗口排首位后重排索引。
 *
 * 不直接使用 [AssistsCore.NodeTree]：其字段缺 `isEditable`，且 `toNodeTree()`
 * 递归无深度/数量上限，遇到深层 WebView 会拖死主线程。这里按预算手写遍历。
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
        val capturedAt = System.currentTimeMillis()
        // 抓帧放到节点读取之前：节点树可能因目标界面刷新而变化，冻结帧必须
        // 与用户接下来点击的控件处于同一画面。
        val captured = runCatching { frameSource.grabFrameWithFreshness() }.getOrNull()
        val frame = captured?.getOrNull()
        val frameErrorCode = if (frame == null) ERROR_NO_FRAME else null
        if (frame == null) {
            captured?.exceptionOrNull()?.let {
                android.util.Log.w(TAG, "frame unavailable: ${it.message}")
            }
        }

        var collected = withContext(Dispatchers.Main) {
            runCatching { collectWindows(selfPackage) }
                .getOrElse { t ->
                    android.util.Log.w(TAG, "collect windows failed", t)
                    Collected(emptyList(), 0, ERROR_NODE_TREE, 0)
                }
        }
        // 微信等反自动化 App 会把自身进程的无障碍树摘空（根节点无子节点，整树
        // 只剩 1 个空节点）。真机取证（2026-10-28）：触摸探索打开后微信秒级
        // 重建全树、关闭后秒级再摘空，故采集退化时脉冲一次触摸探索再重采，
        // 用完即由 TouchExplorationGate 恢复原状，不影响用户触摸习惯。
        if (collected.windows.isEmpty() || collected.nodeCount < MIN_REAL_NODES) {
            TouchExplorationGate.acquire()
            try {
                TouchExplorationGate.awaitTreeSettled()
                val retry = withContext(Dispatchers.Main) {
                    runCatching { collectWindows(selfPackage) }
                        .getOrElse { t ->
                            android.util.Log.w(TAG, "retry collect windows failed", t)
                            collected
                        }
                }
                if (retry.windows.isNotEmpty() && retry.nodeCount > collected.nodeCount) {
                    collected = retry
                }
            } finally {
                TouchExplorationGate.release()
            }
        }
        if (collected.windows.isEmpty()) {
            val error = collected.errorCode ?: ERROR_NO_WINDOWS
            return Result.failure(IllegalStateException(error))
        }
        return Result.success(
            RpaAnalyzeSnapshot(
                frame = frame?.bitmap,
                frameSource = frame?.source.orEmpty(),
                frameErrorCode = frameErrorCode,
                windows = collected.windows,
                activeWindowIndex = collected.activeWindowIndex,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                nodeCount = collected.nodeCount,
                capturedAt = capturedAt,
            ),
        )
    }

    private fun collectWindows(selfPackage: String): Collected {
        val service = AssistsService.getOrNull()
            ?: return Collected(emptyList(), 0, ERROR_A11Y_UNAVAILABLE, 0)
        val foregroundPackage = runCatching {
            AssistsCore.getPackageName(AssistsCore.NodeLookupScope.AllWindows)
        }.getOrDefault("")

        val infoList = runCatching { service.windows }.getOrNull()
        if (infoList.isNullOrEmpty()) {
            // flagRetrieveInteractiveWindows 未生效时仍可分析活动窗口，避免直接空白。
            val root = runCatching { service.rootInActiveWindow }.getOrNull()
                ?: return Collected(emptyList(), 0, ERROR_NO_WINDOWS, 0)
            val packageName = root.packageName?.toString().orEmpty()
            if (packageName.isBlank() || packageName == selfPackage) {
                return Collected(emptyList(), 0, ERROR_NO_WINDOWS, 0)
            }
            val budget = Budget(MAX_RAW_NODES)
            val raw = runCatching { readNode(root, budget, 0) }.getOrNull()
                ?: return Collected(emptyList(), 0, ERROR_NODE_TREE, 0)
            val window = RpaAnalyzeWindow(
                index = 0,
                packageName = packageName,
                label = windowLabel(null, packageName),
                root = raw,
            )
            return Collected(listOf(window), 0, null, budget.used)
        }

        val windows = ArrayList<RpaAnalyzeWindow>(infoList.size)
        val appFlags = ArrayList<Boolean>(infoList.size)
        val focusedFlags = ArrayList<Boolean>(infoList.size)
        val activeFlags = ArrayList<Boolean>(infoList.size)
        val nodeCounts = ArrayList<Int>(infoList.size)
        val budget = Budget(MAX_RAW_NODES)
        for (info in infoList) {
            try {
                val root = runCatching { info.root }.getOrNull() ?: continue
                val packageName = root.packageName?.toString().orEmpty()
                if (packageName.isBlank() || packageName == selfPackage) continue
                val usedBefore = budget.used
                val raw = runCatching { readNode(root, budget, 0) }.getOrNull() ?: continue
                val index = windows.size
                appFlags.add(info.type == AccessibilityWindowInfo.TYPE_APPLICATION)
                focusedFlags.add(info.isFocused)
                activeFlags.add(info.isActive)
                nodeCounts.add(budget.used - usedBefore)
                windows.add(
                    RpaAnalyzeWindow(
                        index = index,
                        packageName = packageName,
                        label = windowLabel(info, packageName),
                        root = raw,
                    ),
                )
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "read window failed", t)
            } finally {
                runCatching { info.recycle() }
            }
        }
        if (windows.isEmpty()) {
            return Collected(emptyList(), 0, ERROR_NO_WINDOWS, budget.used)
        }

        val activeBeforeReorder = pickActiveWindow(
            windows = windows,
            appFlags = appFlags,
            focusedFlags = focusedFlags,
            activeFlags = activeFlags,
            nodeCounts = nodeCounts,
            foregroundPackage = foregroundPackage,
        )
        val reordered = ArrayList<RpaAnalyzeWindow>(windows.size)
        val order = ArrayList<Int>(windows.size)
        order.add(activeBeforeReorder)
        for (i in windows.indices) if (i != activeBeforeReorder) order.add(i)
        order.forEachIndexed { newIndex, oldIndex ->
            val window = windows[oldIndex]
            reordered.add(window.copy(index = newIndex))
        }

        return Collected(
            windows = reordered,
            activeWindowIndex = 0,
            errorCode = null,
            nodeCount = budget.used,
        )
    }

    /**
     * 选出画布默认展示的「当前窗口」。
     *
     * 不能只按包名匹配：MIUI 桌面的手势/导航条窗口同样属于 `com.miui.home`，
     * 且在 [AccessibilityService.getWindows] 中排在应用窗口之前。取第一个
     * 同包名窗口会把只含根节点的系统窗口当成当前窗口，画布上只剩一条贴着
     * 屏幕底边的绿框（真机 2026-10-28 复现）。
     *
     * 依次优先：活动/聚焦的应用窗口 → 前台包名的应用窗口 → 任意应用窗口。
     * 系统窗口（状态栏/导航条/输入法）只在没有任何应用窗口时兜底。
     */
    private fun pickActiveWindow(
        windows: List<RpaAnalyzeWindow>,
        appFlags: List<Boolean>,
        focusedFlags: List<Boolean>,
        activeFlags: List<Boolean>,
        nodeCounts: List<Int>,
        foregroundPackage: String,
    ): Int {
        fun best(predicate: (Int) -> Boolean): Int =
            windows.indices.filter(predicate).maxByOrNull { nodeCounts[it] } ?: -1

        val isApp = { i: Int -> appFlags.getOrNull(i) == true }
        val isFocused = { i: Int -> focusedFlags.getOrNull(i) == true }
        val isActive = { i: Int -> activeFlags.getOrNull(i) == true }
        val isForeground = { i: Int -> windows[i].packageName == foregroundPackage }
        val candidates = listOf(
            best { isApp(it) && isActive(it) && isForeground(it) },
            best { isApp(it) && isActive(it) },
            best { isApp(it) && isFocused(it) && isForeground(it) },
            best { isApp(it) && isFocused(it) },
            best { isApp(it) && isForeground(it) },
            best { isApp(it) },
            best { isActive(it) },
            best { isFocused(it) },
            best { true },
        )
        return candidates.firstOrNull { it >= 0 } ?: 0
    }

    private fun readNode(
        node: AccessibilityNodeInfo,
        budget: Budget,
        depth: Int,
    ): RpaRawNode? {
        if (budget.remaining <= 0) return null
        budget.remaining--
        budget.used++

        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        val bounds = if (rect.width() > 0 && rect.height() > 0) {
            RpaBox(rect.left, rect.top, rect.right, rect.bottom)
        } else {
            RpaBox.EMPTY
        }

        val children = ArrayList<RpaRawNode>()
        if (depth < MAX_RAW_DEPTH && budget.remaining > 0) {
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                if (budget.remaining <= 0) break
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                val raw = runCatching { readNode(child, budget, depth + 1) }.getOrNull()
                if (raw != null) children.add(raw)
            }
        }

        return RpaRawNode(
            packageName = runCatching { node.packageName?.toString().orEmpty() }.getOrDefault(""),
            className = runCatching { node.className?.toString().orEmpty() }.getOrDefault(""),
            text = runCatching { node.text?.toString().orEmpty() }.getOrDefault(""),
            description = runCatching {
                node.contentDescription?.toString().orEmpty()
            }.getOrDefault(""),
            viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault(""),
            hint = runCatching { node.hintText?.toString().orEmpty() }.getOrDefault(""),
            bounds = bounds,
            clickable = runCatching { node.isClickable }.getOrDefault(false),
            editable = runCatching { node.isEditable }.getOrDefault(false),
            scrollable = runCatching { node.isScrollable }.getOrDefault(false),
            focused = runCatching { node.isFocused }.getOrDefault(false),
            enabled = runCatching { node.isEnabled }.getOrDefault(true),
            visibleToUser = runCatching { node.isVisibleToUser }.getOrDefault(true),
            children = children,
        )
    }

    private fun windowLabel(info: AccessibilityWindowInfo?, packageName: String): String {
        val kind = when (info?.type) {
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "输入法"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "系统"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "浮层"
            AccessibilityWindowInfo.TYPE_APPLICATION -> "应用"
            else -> "窗口"
        }
        return "$packageName · $kind"
    }

    private data class Collected(
        val windows: List<RpaAnalyzeWindow>,
        val activeWindowIndex: Int,
        val errorCode: String?,
        val nodeCount: Int,
    )

    private class Budget(var remaining: Int) {
        var used: Int = 0
    }

    companion object {
        private const val TAG = "RpaAnalyzeCapture"
        const val ERROR_A11Y_UNAVAILABLE = "a11y_unavailable"
        const val ERROR_NODE_TREE = "node_tree_failed"
        const val ERROR_NO_WINDOWS = "no_windows"
        const val ERROR_NO_FRAME = "no_frame"

        /** 单次采集的原始节点预算；800 个框已足够覆盖一屏，避免 WebView 拖死主线程。 */
        const val MAX_RAW_NODES = 1200
        private const val MAX_RAW_DEPTH = 64

        /** 判定「节点树退化」的下限：真实应用根节点至少带 1 个子节点（≥2 个节点）。 */
        private const val MIN_REAL_NODES = 2
    }
}
