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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.auto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.OcrLine
import com.verlintas.baic2.device.api.OcrProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 手势粘贴通道（2026-10-27 真机「自动回复发不出去」根因修复，第三种执行面）：
 * **只用无障碍手势 + 截图 OCR + 剪贴板**，不依赖节点文本注入、不依赖 Shizuku。
 *
 * 病灶（真机证据，微信 8.0.79 / HyperOS Android 15）：
 * - 微信会话页对无障碍客户端**零暴露**节点树（`uiautomator dump` 只有 1 个空节点，
 *   `AssistsA11yBridge.typeText` 连候选可编辑节点都找不到 → `no_focused_input`）；
 * - 于是模式 A（[A11yInjector]）在微信上必然 `input_inject_failed`；
 * - 本机无 Shizuku → 模式 B 不可用 → 整轮只有降级起草，自动回复永远发不出去。
 *
 * 本通道把「文本注入」换成**剪贴板 + 长按粘贴菜单**（长按输入栏 → OCR 找
 * 「粘贴」→ 手势点它），点击/长按走 `dispatchGesture`（系统级注入，目标 App
 * 无法屏蔽），定位与证据全部复用 [OcrSendLocator] 的截图/OCR 面。等价于把
 * 模式 B 的「shell input」换成「a11y 手势」，因此**零额外安装、纯本地**。
 *
 * 自愈设计（与 [SendSequence] 的纪律一致）：
 * - 输入栏锚点不是一个魔数，而是**候选行阶梯**（像素绿键行 → OCR「发送」行 →
 *   键盘展开态比例 → 键盘收起态比例），每档都用「长按后能否 OCR 到粘贴菜单」
 *   验证，不成立就换下一档；
 * - 注入前先自检键盘是否展开（[ensureKeyboardOpen]），未展开先按阶梯点输入栏，
 *   避免在「非聚焦」状态下长按（微信会先收起键盘，粘贴菜单不会出现）；
 * - 粘贴成功与否由 [SendSequence] 的定位步（绿键只在输入非空时出现）与像素
 *   三态证据复核，本类不自封成功。
 */
@Singleton
class A11yGestureInjector @Inject constructor(
    private val bridge: AccessibilityBridge,
    private val frameSource: FrameSource,
    private val ocr: OcrProvider,
    @ApplicationContext private val context: Context,
) : InputInjector {

    override val channel = InjectorChannel.Gesture

    /** 手势与截图都来自无障碍服务：连接断开即不可用（与模式 A 同一前置，不装 Shizuku）。 */
    override val available: Boolean
        get() = bridge.connected.value

    override val unavailableReason: String = SendSequence.REASON_A11Y_DISCONNECTED

    override suspend fun tap(x: Int, y: Int): Result<Unit> = bridge.tap(x, y)

    /**
     * 文本注入 = 写剪贴板 + 长按输入栏 + 点「粘贴」。写前备份、注入后延迟恢复
     * 用户剪贴板（恢复必须晚于粘贴菜单真正读取内容，否则微信粘到空内容）。
     */
    override suspend fun type(text: String): Result<Unit> = withContext(Dispatchers.Default) {
        val clipboard = clipboardManager() ?: return@withContext fail("clipboard_unavailable")
        val previous = runCatching { clipboard.primaryClip }.getOrNull()
        val written = runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text)) }
        if (written.isFailure) {
            val why = written.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
            return@withContext fail("clipboard_write_rejected:$why")
        }
        val result = pasteViaMenu()
        restoreClipboardLater(clipboard, previous)
        result
    }

    /** 模式 B 口径的「粘贴当前剪贴板」（剪贴板已由调用方写好）。 */
    override suspend fun paste(): Result<Unit> = withContext(Dispatchers.Default) { pasteViaMenu() }

    /**
     * best effort 清空输入框：长按 → 「全选」→「剪切」。微信输入框在输入非空时
     * 才显示绿键，绿键不在场即视为已空（省掉整轮 OCR）；任一菜单找不到即失败，
     * 由调用方忽略（[SendSequence] 对 clearInput 一律 runCatching）。
     */
    override suspend fun clearInput(): Result<Unit> = withContext(Dispatchers.Default) {
        val snapshot = snapshot() ?: return@withContext fail("frame_unavailable")
        if (!inputHasText(snapshot)) return@withContext Result.success(Unit)
        for (rowY in inputRowCandidates(snapshot)) {
            longPressInputRow(snapshot, rowY)
            val selectAll = pollMenu(rowY, KEYWORD_SELECT_ALL) ?: continue
            if (bridge.tap(selectAll.centerX, selectAll.centerY).isFailure) continue
            delay(MENU_SETTLE_MS)
            val cut = pollMenu(rowY, KEYWORD_CUT) ?: return@withContext fail("cut_menu_missing")
            return@withContext if (bridge.tap(cut.centerX, cut.centerY).isSuccess) {
                Log.i(TAG, "clearInput: anchor=$rowY cut=(${cut.centerX},${cut.centerY})")
                delay(PASTE_SETTLE_MS)
                Result.success(Unit)
            } else {
                fail("cut_tap_failed")
            }
        }
        fail("select_all_menu_missing")
    }

    override suspend fun foregroundPackage(): String? = bridge.foregroundPackage()

    /**
     * 发送成功后收起输入法（best effort，2026-10-27 与模式 B 同款收尾）：
     * Sogou 工具条/候选条会被下一帧 OCR 当成来信，诱发自问自答回合。本通道注入前
     * 必然展开键盘，但**只以 OCR 证据为准**再发 BACK——OCR 没看到 IME 就不按，
     * 宁可键盘留着，也不能把微信聊天页误退到消息列表。
     */
    suspend fun hideImeIfShown(): Boolean = withContext(Dispatchers.Default) {
        val current = snapshot() ?: return@withContext false
        if (!OcrSendLocator.looksLikeIme(current.lines, current.frame.height)) {
            return@withContext false
        }
        val back = bridge.pressKey(KEY_BACK)
        delay(IME_HIDE_SETTLE_MS)
        Log.i(TAG, "hideIme: back=${back.isSuccess}")
        back.isSuccess
    }

    // ── 粘贴主链 ──

    private suspend fun pasteViaMenu(): Result<Unit> {
        val focused = ensureKeyboardOpen()
        Log.i(TAG, "paste: keyboard=$focused")
        val snapshot = snapshot() ?: return fail("frame_unavailable")
        for (rowY in inputRowCandidates(snapshot)) {
            longPressInputRow(snapshot, rowY)
            val item = pollMenu(rowY, KEYWORD_PASTE) ?: continue
            val tapped = bridge.tap(item.centerX, item.centerY)
            if (tapped.isFailure) return fail("paste_tap_failed")
            delay(PASTE_SETTLE_MS)
            Log.i(
                TAG,
                "paste: anchor=$rowY menu='${item.text}' at=(${item.centerX},${item.centerY})",
            )
            return Result.success(Unit)
        }
        return fail("paste_menu_missing")
    }

    /**
     * 注入前自检键盘：未展开时按阶梯点输入栏（展开态比例优先，其次收起态），
     * 每档取帧 + OCR 验证是否真的弹起。三档都不成立即返回 false，粘贴链照常
     * 继续走（可能因上一轮布局仍在，锚点阶梯仍有命中机会）。
     */
    private suspend fun ensureKeyboardOpen(): Boolean {
        val first = snapshot() ?: return false
        if (OcrSendLocator.looksLikeIme(first.lines, first.frame.height)) return true
        for (rowY in focusCandidates(first)) {
            Log.i(TAG, "focus: tap input row=$rowY")
            bridge.tap(first.fieldX, rowY)
            delay(FOCUS_SETTLE_MS)
            val probe = snapshot() ?: continue
            if (OcrSendLocator.looksLikeIme(probe.lines, probe.frame.height)) return true
        }
        return false
    }

    /** 长按输入栏（`dispatchGesture` 650ms 同点按压）。 */
    private suspend fun longPressInputRow(snapshot: Snapshot, rowY: Int) {
        Log.i(TAG, "longPress input row=(${snapshot.fieldX},$rowY)")
        bridge.longPress(snapshot.fieldX, rowY)
        delay(MENU_SETTLE_MS)
    }

    /**
     * 轮询粘贴/编辑菜单项：长按菜单是异步渲染的，单帧常拿不到（真机实测 ~0.3~0.6s
     * 才可见），因此按 [MENU_POLL_TRIES] 拍取帧 + OCR，命中即返回其行框（供点击）。
     */
    private suspend fun pollMenu(anchorY: Int, keyword: String): OcrLine? {
        repeat(MENU_POLL_TRIES) { attempt ->
            val current = snapshot() ?: return null
            val rows = mergeRows(current.lines, mergeTolerance(current.frame.height))
            val hit = pickMenuKeyword(rows, current.frame.height, keyword, anchorY)
            if (hit != null) {
                Log.i(TAG, "menu '$keyword' hit attempt=$attempt text='${hit.text}'")
                return hit
            }
            delay(MENU_POLL_DELAY_MS)
        }
        return null
    }

    /** 输入框是否已有内容：微信只在草稿非空时显示绿色发送键（像素判定，不耗 OCR）。 */
    private fun inputHasText(snapshot: Snapshot): Boolean {
        val height = snapshot.frame.height
        val pixels = snapshot.frame.pixels() ?: return false
        return OcrSendLocator.findGreenSend(pixels, snapshot.frame.width, height, imeOpen = true) != null ||
            OcrSendLocator.findGreenSend(pixels, snapshot.frame.width, height, imeOpen = false) != null
    }

    // ── 帧/OCR/剪贴板底座 ──

    private class Snapshot(val frame: Bitmap, val lines: List<OcrLine>, val fieldX: Int)

    private suspend fun snapshot(): Snapshot? {
        val frame = frameSource.grabFrame().getOrNull() ?: return null
        val lines = ocrLines(frame) ?: return null
        return Snapshot(frame, lines, (frame.width * INPUT_FIELD_X_RATIO).toInt())
    }

    private suspend fun ocrLines(frame: Bitmap): List<OcrLine>? {
        val bytes = runCatching {
            ByteArrayOutputStream().use { sink ->
                frame.compress(Bitmap.CompressFormat.PNG, 100, sink)
                sink.toByteArray()
            }
        }.getOrNull() ?: return null
        return ocr.recognize(bytes).getOrNull()
    }

    private fun Bitmap.pixels(): IntArray? {
        if (width <= 0 || height <= 0) return null
        val out = IntArray(width * height)
        return runCatching { getPixels(out, 0, width, 0, 0, width, height) }.map { out }.getOrNull()
    }

    private fun clipboardManager(): ClipboardManager? =
        runCatching { context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }.getOrNull()

    /** 粘贴菜单读剪贴板是异步的：延迟恢复用户原剪贴板，失败只记日志。 */
    private fun restoreClipboardLater(clipboard: ClipboardManager, previous: ClipData?) {
        Handler(Looper.getMainLooper()).postDelayed(
            {
                runCatching {
                    if (previous != null) {
                        clipboard.setPrimaryClip(previous)
                    } else {
                        clipboard.clearPrimaryClip()
                    }
                }
            },
            CLIP_RESTORE_DELAY_MS,
        )
    }

    private fun fail(detail: String): Result<Unit> =
        Result.failure(IllegalStateException("gesture_paste_$detail"))

    /** 锚点候选（[Snapshot] → 纯函数 [inputRowCandidates] 的取值适配）。 */
    private fun inputRowCandidates(snapshot: Snapshot): List<Int> {
        val height = snapshot.frame.height
        val pixels = snapshot.frame.pixels()
        val greenYs = if (pixels == null) {
            emptyList()
        } else {
            listOfNotNull(
                OcrSendLocator.findGreenSend(pixels, snapshot.frame.width, height, imeOpen = true),
                OcrSendLocator.findGreenSend(pixels, snapshot.frame.width, height, imeOpen = false),
            ).map { it.centerY }
        }
        val ocrSendY = OcrSendLocator.pickSend(snapshot.lines, height)?.centerY
        return inputRowCandidates(height, greenYs, ocrSendY)
    }

    /** 聚焦候选（键盘展开态优先，最后才用键盘收起态，避免把键盘点歪）。 */
    private fun focusCandidates(snapshot: Snapshot): List<Int> = listOf(
        (snapshot.frame.height * INPUT_ROW_IME_OPEN_Y_RATIO).toInt(),
        (snapshot.frame.height * INPUT_ROW_HANDWRITING_Y_RATIO).toInt(),
        (snapshot.frame.height * INPUT_BAR_Y_RATIO).toInt(),
    ).distinct()

    private fun mergeTolerance(height: Int): Int = (height * MERGE_ROW_TOLERANCE_RATIO).toInt()

    internal companion object {

        private const val TAG = "A11yGesture"

        /** 剪贴板标签（与模式 B 同源口径；不进台账、不落盘草稿内容）。 */
        internal const val CLIP_LABEL = "baic2"

        /** 粘贴菜单项文案（微信/输入法工具条的通用叫法）。 */
        internal const val KEYWORD_PASTE = "粘贴"

        /** 全选/剪切（best effort 清空输入框）。 */
        internal const val KEYWORD_SELECT_ALL = "全选"
        internal const val KEYWORD_CUT = "剪切"

        /** 长按菜单只在屏幕下半区判定（聊天正文里的同名词不参与）。 */
        private const val MENU_ZONE_TOP_RATIO = 0.40f

        /** 候选锚点的合法纵向范围（顶栏/状态栏不参与长按）。 */
        private const val ANCHOR_MIN_Y_RATIO = 0.28f
        private const val ANCHOR_MAX_Y_RATIO = 0.99f

        /** 键盘展开态输入行中线（真机 2026-10-27 实测 1382/2460，Sogou QWERTY）。 */
        private const val INPUT_ROW_IME_OPEN_Y_RATIO = 0.562f

        /** 手写/随写面板展开态输入行中线（2026-10-26 实测 1077/2460，面板更高）。 */
        private const val INPUT_ROW_HANDWRITING_Y_RATIO = 0.438f

        /** 键盘收起态输入栏中线（2026-10-26 实测 2304/2460）。 */
        private const val INPUT_BAR_Y_RATIO = 0.936f

        /** 输入框横向比例（避开左语音、右表情/加号图标）。 */
        private const val INPUT_FIELD_X_RATIO = 0.45f

        /** 长按后菜单渲染等待；点菜单项后的落定等待。 */
        private const val MENU_SETTLE_MS = 500L
        private const val PASTE_SETTLE_MS = 500L

        /** 菜单轮询（异步渲染）：3 拍 × 300ms。 */
        private const val MENU_POLL_TRIES = 3
        private const val MENU_POLL_DELAY_MS = 300L

        /** 点输入栏到键盘弹起的等待。 */
        private const val FOCUS_SETTLE_MS = 900L

        /** 收键盘后的落定等待（BACK 后键盘动画 ~300ms）。 */
        private const val IME_HIDE_SETTLE_MS = 400L

        /** 无障碍全局返回键名（[AccessibilityBridge.pressKey]）。 */
        private const val KEY_BACK = "back"

        /** 剪贴板恢复延迟（必须晚于粘贴菜单读取内容）。 */
        private const val CLIP_RESTORE_DELAY_MS = 2_000L

        /** 同一视觉行合并容差（高度比例，1080×2460 约 24px）。 */
        private const val MERGE_ROW_TOLERANCE_RATIO = 0.010f

        /**
         * 输入栏锚点候选（纯函数，JVM 可测）：像素绿键行 → OCR「发送」行 →
         * 键盘展开态比例 → 键盘收起态比例。去重、裁掉非法纵向范围，**首位优先**。
         * 每档最终都要靠「长按后能否 OCR 到粘贴菜单」验证，所以宁多勿缺。
         */
        internal fun inputRowCandidates(
            height: Int,
            greenSendYs: List<Int>,
            ocrSendY: Int?,
        ): List<Int> {
            if (height <= 0) return emptyList()
            val min = (height * ANCHOR_MIN_Y_RATIO).toInt()
            val max = (height * ANCHOR_MAX_Y_RATIO).toInt()
            return (greenSendYs + listOfNotNull(ocrSendY) + listOf(
                (height * INPUT_ROW_IME_OPEN_Y_RATIO).toInt(),
                (height * INPUT_BAR_Y_RATIO).toInt(),
            )).distinct().filter { it in min..max }
        }

        /**
         * 同一视觉行的 OCR 行合并（纯函数，JVM 可测）：ML Kit 常把「粘贴」拆成
         * 「粘」「贴」两行（甚至「全 选」），逐行匹配会漏；按纵向容差分行、取
         * 行框并集与文本拼接，点击中心随之回到整词中心。
         */
        internal fun mergeRows(lines: List<OcrLine>, tolerancePx: Int): List<OcrLine> {
            if (lines.size <= 1) return lines
            val sorted = lines.sortedWith(compareBy({ it.centerY }, { it.left }))
            val out = ArrayList<OcrLine>(sorted.size)
            var group = mutableListOf<OcrLine>()
            fun flush() {
                if (group.isEmpty()) return
                out += OcrLine(
                    text = group.joinToString(separator = "") { it.text },
                    left = group.minOf { it.left },
                    top = group.minOf { it.top },
                    right = group.maxOf { it.right },
                    bottom = group.maxOf { it.bottom },
                )
                group = mutableListOf()
            }
            for (line in sorted) {
                if (group.isEmpty() || abs(line.centerY - group.last().centerY) <= tolerancePx) {
                    group += line
                } else {
                    flush()
                    group += line
                }
            }
            flush()
            return out
        }

        /**
         * 长按菜单项定位（纯函数，JVM 可测）：只在屏幕下半区（[MENU_ZONE_TOP_RATIO]
         * 以下）找文本含 [keyword] 的行，取**离锚点最近**者（长按菜单贴在输入栏旁，
         * 聊天气泡里的同名词距离远）；并排同距取最左，保证同一行多词时结果稳定。
         */
        internal fun pickMenuKeyword(
            lines: List<OcrLine>,
            height: Int,
            keyword: String,
            anchorY: Int,
        ): OcrLine? {
            if (height <= 0 || keyword.isBlank()) return null
            val zoneTop = (height * MENU_ZONE_TOP_RATIO).toInt()
            val needle = keyword.filter { it.isLetterOrDigit() }
            if (needle.isEmpty()) return null
            return lines.asSequence()
                .filter { it.centerY >= zoneTop }
                .filter { it.text.filter { ch -> ch.isLetterOrDigit() }.contains(needle) }
                .minWithOrNull(compareBy({ abs(it.centerY - anchorY) }, { it.left }))
        }
    }
}