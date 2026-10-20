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

package com.verlintas.baic2.floatball.auto

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.TextNode
import com.verlintas.baic2.device.impl.a11y.AndroidAccessibilityBridge
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AccessibilityBridge] 的 assists 换源实现（方案 §17.1，接口契约不变）：
 *
 * - **节点面**（findText/screenText/typeText/pressKey-enter/editableFocused）委托
 *   [AssistsCore]——继承合体后 [AssistsService.getOrNull] 全局可用，微信等新版
 *   IM 的对象树解析力显著强于手写 walk；
 * - **手势/截图面**（tap/longPress/swipe/captureScreen）委托既有
 *   [AndroidAccessibilityBridge]——纯系统 API 路径，保留已验证实现零行为漂移，
 *   其 attach/detach 生命周期照常由 [com.verlintas.baic2.device.impl.a11y.Baic2AccessibilityService] 接线；
 *
 * 定位链（resource-id/描述精确匹配 → 文本包含匹配 → OCR → 找图）由调用方
 * （InputInjector/SendSequence，§17.2）组合，本类保持 findText 的
 * 「text 或 contentDescription 包含匹配」契约语义。
 */
@Singleton
class AssistsA11yBridge @Inject constructor(
    private val legacy: AndroidAccessibilityBridge,
) : AccessibilityBridge {

    override val connected = legacy.connected

    override suspend fun tap(x: Int, y: Int): Result<Unit> = legacy.tap(x, y)

    override suspend fun longPress(x: Int, y: Int): Result<Unit> = legacy.longPress(x, y)

    override suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): Result<Unit> = legacy.swipe(x1, y1, x2, y2, durationMs)

    override suspend fun captureScreen(): Result<ByteArray> = legacy.captureScreen()

    override suspend fun typeText(text: String): Result<Unit> = withContext(Dispatchers.Main) {
        val service = AssistsService.getOrNull() ?: return@withContext notEnabled()
        val root = service.rootInActiveWindow
        val target = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root?.let { firstEditable() }
            ?: return@withContext Result.failure(
                IllegalStateException("no_focused_input: tap a text field first"),
            )
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            return@withContext Result.success(Unit)
        }
        pasteFallback(service, target, text)
    }

    /** SET_TEXT 拒绝时的剪贴板兜底（直写剪贴板 + 节点 ACTION_PASTE，不经 IME）。 */
    private fun pasteFallback(
        service: AssistsService,
        target: AccessibilityNodeInfo,
        text: String,
    ): Result<Unit> {
        val clipboard = runCatching {
            service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        }.getOrNull() ?: return pasteFailure("no clipboard service")
        val previous = runCatching {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(service)?.toString()
        }.getOrNull()
        val setOk = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("baic2", text))
        }.isSuccess
        if (!setOk) {
            return pasteFailure("clipboard unavailable")
        }
        // 恢复原剪贴板必须等 ACTION_PASTE 真正读取内容之后（异步），失败仅记日志
        Handler(Looper.getMainLooper()).postDelayed(
            {
                runCatching {
                    previous?.let { clipboard.setPrimaryClip(ClipData.newPlainText("baic2", it)) }
                }
            },
            RESTORE_DELAY_MS,
        )
        return if (target.performAction(AccessibilityNodeInfo.ACTION_PASTE, null)) {
            Result.success(Unit)
        } else {
            pasteFailure("the field refused the paste")
        }
    }

    private fun pasteFailure(detail: String): Result<Unit> = Result.failure(
        IllegalStateException("clipboard_paste_rejected: $detail"),
    )

    override suspend fun pressKey(key: String): Result<Unit> = withContext(Dispatchers.Main) {
        val service = AssistsService.getOrNull() ?: return@withContext notEnabled()
        if (key.lowercase() == "enter") {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                return@withContext Result.failure(
                    IllegalStateException("enter_requires_android_11: use press_key 'back' or tap the send button"),
                )
            }
            val root = service.rootInActiveWindow
            val target = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root?.let { firstEditable() }
                ?: return@withContext Result.failure(
                    IllegalStateException("no_focused_input: tap a text field first"),
                )
            val enterAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            return@withContext if (target.performAction(enterAction)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("enter_rejected: the field refused the action"))
            }
        }
        val action = when (key.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "shade" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return@withContext Result.failure(
                IllegalArgumentException("unknown_key: use back|home|recents|notifications|enter"),
            )
        }
        if (service.performGlobalAction(action)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("global_action_rejected"))
        }
    }

    override fun findText(query: String): List<TextNode> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val results = LinkedHashMap<String, TextNode>()
        fun collect(node: AccessibilityNodeInfo) {
            runCatching {
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: return
                if (!label.lowercase().contains(needle)) return
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return
                results.putIfAbsent(
                    "$label\u0000$bounds",
                    TextNode(label, bounds.left, bounds.top, bounds.right, bounds.bottom),
                )
            }
        }
        // 系统级文本查找先行（结构化、覆盖动态补齐节点），再全树兜 contentDescription
        runCatching { AssistsCore.findByText(query).forEach { collect(it) } }
        runCatching { AssistsCore.getAllNodes().forEach { collect(it) } }
        return results.values.toList()
    }

    override fun screenText(maxNodes: Int): String {
        if (maxNodes <= 0) return ""
        val lines = mutableListOf<String>()
        runCatching {
            for (node in AssistsCore.getAllNodes()) {
                if (lines.size >= maxNodes) break
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                label?.let { lines += it }
            }
        }
        return lines.joinToString("\n")
    }

    override fun foregroundPackage(): String? = runCatching {
        AssistsCore.getAccessibilityRootNodes(AssistsCore.NodeLookupScope.ActiveWindow)
            .firstOrNull()?.packageName?.toString()
    }.getOrNull()

    override fun windowTitle(): String? = runCatching {
        AssistsService.getOrNull()?.rootInActiveWindow?.window?.title?.toString()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun screenSize(): Pair<Int, Int>? = runCatching {
        AssistsService.getOrNull()?.resources?.displayMetrics
            ?.let { it.widthPixels to it.heightPixels }
    }.getOrNull()

    override fun editableFocused(): Boolean = runCatching {
        val root = AssistsService.getOrNull()?.rootInActiveWindow
            ?: return@runCatching false
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null || firstEditable() != null
    }.getOrDefault(false)

    private fun firstEditable(): AccessibilityNodeInfo? =
        AssistsCore.getAllNodes().firstOrNull { it.isEditable && it.isEnabled }

    private fun notEnabled(): Result<Unit> = Result.failure(
        IllegalStateException(
            "accessibility_not_enabled: ask the user to enable the BAIC2 accessibility service " +
                "in 设置 → 无障碍, then retry.",
        ),
    )

    private companion object {
        const val RESTORE_DELAY_MS = 120L
    }
}
