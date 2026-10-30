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
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.TextNode
import com.verlintas.baic2.device.impl.a11y.AndroidAccessibilityBridge
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    override suspend fun tap(x: Int, y: Int): Result<Unit> {
        // 屏幕外坐标直接失败（dispatchGesture 对非法坐标可能挂起不回调——
        // 2026-10-21 真机实测 (-734,2045) 节点 tap 卡死整条导航链）。
        if (x < 0 || y < 0) {
            return Result.failure(IllegalStateException("tap_offscreen: ($x,$y)"))
        }
        return legacy.tap(x, y)
    }

    override suspend fun longPress(x: Int, y: Int): Result<Unit> = legacy.longPress(x, y)

    override suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): Result<Unit> = legacy.swipe(x1, y1, x2, y2, durationMs)

    override suspend fun captureScreen(): Result<ByteArray> = legacy.captureScreen()

    override suspend fun captureScreenBitmap(): Result<Bitmap> = legacy.captureScreenBitmap()

    override suspend fun typeText(text: String): Result<Unit> = withContext(Dispatchers.Main) {
        val service = AssistsService.getOrNull() ?: return@withContext notEnabled()
        val root = service.rootInActiveWindow
        // 目标候选：焦点输入节点 + 全部可编辑节点（className@bounds 去重）。
        // 微信 FTS 搜索页的 findFocus 节点可能不是真输入框（SET_TEXT 被静默
        // 吞掉，2026-10-21 真机实测 performAction=true 但文本不落地），对候选
        // 逐个 SET_TEXT，以 [inputTextContains] 回读验证为准。
        val candidates = LinkedHashMap<String, AccessibilityNodeInfo>()
        fun addTarget(tag: String, node: AccessibilityNodeInfo) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val key = "${node.className}@$bounds"
            if (candidates.containsKey(key)) return
            candidates[key] = node
            Log.i(TAG, "typeText candidate[$tag] $key text='${node.text}'")
        }
        root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { addTarget("focus", it) }
        runCatching {
            AssistsCore.getAllNodes()
                .filter { it.isEditable && it.isEnabled }
                .take(EDITABLE_CANDIDATE_MAX)
                .forEach { addTarget("editable", it) }
        }
        if (candidates.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("no_focused_input: tap a text field first"),
            )
        }
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        for (target in candidates.values) {
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                delay(VERIFY_DELAY_MS)
                if (inputTextContains(text)) return@withContext Result.success(Unit)
            }
        }
        // SET_TEXT 全部未落地 → 焦点节点剪贴板 PASTE 兜底，paste 后同样验证。
        val pasted = pasteFallback(service, candidates.values.first(), text)
        if (pasted.isFailure) return@withContext pasted
        delay(VERIFY_DELAY_MS)
        if (inputTextContains(text)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("set_text_not_applied: the field kept its old text"))
        }
    }

    /**
     * 任意可编辑节点当前文本是否包含 [text]——typeText 的落地验证口径。
     * 焦点节点可能不是文本真正落地的节点（多候选尝试），故扫全部可编辑节点；
     * hint 占位符不含关键词，可区分「空框」。
     */
    fun inputTextContains(text: String): Boolean = runCatching {
        val service = AssistsService.getOrNull() ?: return@runCatching false
        val focus = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus?.text?.toString()?.contains(text) == true) return@runCatching true
        AssistsCore.getAllNodes()
            .any { it.isEditable && it.text?.toString()?.contains(text) == true }
    }.getOrDefault(false)

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
        // 匹配口径：大小写不敏感 + 空白无关——IME 候选词常带分词空格
        // （如「吴瑞曦 topwayricky」，2026-10-21 真机实测），精确子串会 miss。
        val compactNeedle = needle.filter { !it.isWhitespace() }
        val results = LinkedHashMap<String, TextNode>()
        fun collect(node: AccessibilityNodeInfo) {
            runCatching {
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: return
                val lower = label.lowercase()
                val matched = lower.contains(needle) ||
                    (
                        compactNeedle.isNotEmpty() &&
                            lower.filter { !it.isWhitespace() }.contains(compactNeedle)
                        )
                if (!matched) return
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
        // 全窗口兜底：IME 候选词在独立输入法窗口，ActiveWindow（FTS 页）扫不到
        // （2026-10-21 真机实测 ⑦ 轮询全 miss 的根因）。findByText 走系统
        // 服务端子串匹配，多窗口成本可控。
        runCatching {
            AssistsCore.findByText(query, scope = AssistsCore.NodeLookupScope.AllWindows)
                .forEach { collect(it) }
        }
        return results.values.toList()
    }

    // ── 微信对象操作（照搬 Assists Simple 的 ScrollContacts / GestureBottomTab） ──

    /**
     * 按文本定位并点击对象——Simple「手势点击微信主页底部Tab」的对象链：
     * 节点自身 ACTION_CLICK → 首个可点击父级 ACTION_CLICK（OpenWechat/ScrollContacts
     * 对底部 tab 的做法）→ 节点中心手势点击兜底（nodeGestureClick 等价，走已验证的
     * legacy 手势通道）。微信对象树放行靠启用列表里有 TalkBack 家族服务（主路线
     * WechatTreeUnlock）；[TouchExplorationGate] 仅为树退化兜底脉冲（HyperOS 上
     * 运行时 setServiceInfo 是空操作），见 AGENTS.md §8 教训 11。
     */
    suspend fun clickText(query: String): Result<Unit> = withContext(Dispatchers.Main) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("click_text_empty_query"))
        }
        AssistsService.getOrNull() ?: return@withContext notEnabled()
        TouchExplorationGate.acquire()
        try {
            TouchExplorationGate.awaitTreeSettled()
            val clicked = with(AssistsCore) {
                findByText(needle).any { node ->
                    node.click() ||
                        node.findFirstParentClickable()?.click() == true ||
                        run {
                            val bounds = node.getBoundsInScreen()
                            !bounds.isEmpty &&
                                legacy.tap(bounds.centerX(), bounds.centerY()).isSuccess
                        }
                }
            }
            if (clicked) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("click_text_miss: no node matched \"$query\""))
            }
        } finally {
            TouchExplorationGate.release()
        }
    }

    /**
     * 列表滚动——Simple「自动滚动微信通讯录」的对象链：对
     * ListView/RecyclerView 容器执行 ACTION_SCROLL_FORWARD/BACKWARD；节点滚动
     * 全 miss 时回退屏幕中线 1/3 幅手势滑动。自带树退化兜底脉冲（解锁主路线
     * 见 WechatTreeUnlock / AGENTS.md §8 教训 11）。
     */
    suspend fun scrollList(forward: Boolean): Result<Unit> = withContext(Dispatchers.Main) {
        val service = AssistsService.getOrNull() ?: return@withContext notEnabled()
        TouchExplorationGate.acquire()
        try {
            TouchExplorationGate.awaitTreeSettled()
            val moved = with(AssistsCore) {
                SCROLL_TAGS.any { tag ->
                    findByTags(tag).any { node ->
                        if (forward) node.scrollForward() else node.scrollBackward()
                    }
                }
            }
            if (moved) return@withContext Result.success(Unit)
            val metrics = service.resources.displayMetrics
            val x = metrics.widthPixels / 2
            val top = metrics.heightPixels / 3
            val bottom = metrics.heightPixels * 2 / 3
            val (y1, y2) = if (forward) bottom to top else top to bottom
            legacy.swipe(x, y1, x, y2, SCROLL_SWIPE_MS)
        } finally {
            TouchExplorationGate.release()
        }
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

    // ── 聚焦步（托管方案 §四.A.4，模式 A 专用能力，不进上游接口契约） ──

    /**
     * 输入框真实持有焦点（仅 findFocus 口径）。与 [editableFocused] 的差异：
     * 后者「存在可编辑节点即真」，不能作为聚焦步判据——多可编辑节点界面
     * （电商客服页顶部搜索框等）按它判定会跳过聚焦，注入落错节点。
     */
    fun inputFocused(): Boolean = runCatching {
        val root = AssistsService.getOrNull()?.rootInActiveWindow
            ?: return@runCatching false
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null
    }.getOrDefault(false)

    /** 首个可编辑节点的屏幕中心（聚焦步 tap 目标）；无可编辑节点/空 bounds 返回 null。 */
    fun firstEditableCenter(): Pair<Int, Int>? = runCatching {
        val node = firstEditable() ?: return@runCatching null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) null else bounds.centerX() to bounds.centerY()
    }.getOrNull()

    /**
     * 聚焦步：无真实焦点先 tap 首个可编辑节点中心。best-effort——失败返回
     * false 不阻断注入（SET_TEXT/节点动作可自选目标注入）。
     */
    suspend fun focusFirstEditable(): Boolean = withContext(Dispatchers.Main) {
        if (inputFocused()) return@withContext true
        val center = firstEditableCenter() ?: return@withContext false
        tap(center.first, center.second).isSuccess
    }

    private fun notEnabled(): Result<Unit> = Result.failure(
        IllegalStateException(
            "accessibility_not_enabled: ask the user to enable the BAIC2 accessibility service " +
                "in 设置 → 无障碍, then retry.",
        ),
    )

    private companion object {
        const val RESTORE_DELAY_MS = 120L

        /** typeText 落地验证前的等待（控件刷新文本快照，协程挂起不阻塞主线程）。 */
        const val VERIFY_DELAY_MS = 150L

        /** typeText 多候选注入的最多可编辑节点数（防大列表全扫）。 */
        const val EDITABLE_CANDIDATE_MAX = 5

        /** scrollList 的容器标签（照搬 Simple ScrollContacts 的口径）。 */
        val SCROLL_TAGS = listOf(
            "android.widget.ListView",
            "androidx.recyclerview.widget.RecyclerView",
        )

        /** scrollList 手势兜底的滑动时长。 */
        const val SCROLL_SWIPE_MS = 400

        const val TAG = "AssistsA11yBridge"
    }
}
