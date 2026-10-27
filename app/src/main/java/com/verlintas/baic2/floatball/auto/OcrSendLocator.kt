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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.auto

import android.graphics.Bitmap
import android.util.Log
import com.verlintas.baic2.device.api.OcrLine
import com.verlintas.baic2.device.api.OcrProvider
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 模式 B 定位器（方案 §17.2「定位改由录屏抽帧+OCR+找图产出的坐标驱动」）：
 * [FrameSource] 取帧 → 像素级绿色发送按钮定位（首选，微信品牌绿 #07C160）
 * → 未命中再 OCR 识别并与模式 A 同口径筛选发送按钮（候选词包含匹配 +
 * 小控件尺寸过滤）。
 *
 * 2026-10-27 真机修复：只用 OCR 会同时命中「微信绿色发送按钮」和「Sogou
 * 键盘右下角发送键」，且取最靠下会把 IME 键当成目标（真机点到了键盘上）。
 * 像素定位只认微信品牌绿、右上区域、紧凑矩形，天然排除 IME 灰键与我方
 * 绿色气泡（#95EC69）。
 *
 * 帧源阶梯优先 a11y 免授权截图，无 a11y 时走 Shizuku screencap/投影兜底。
 * 选择逻辑均为纯函数（[findGreenSend] / [pickSend] / [pickFocusAnchor]），
 * JVM 可测。
 */
@Singleton
class OcrSendLocator @Inject constructor(
    private val frameSource: FrameSource,
    private val ocr: OcrProvider,
) : SendLocator {

    override suspend fun locate(): SendTarget? = withContext(Dispatchers.Default) {
        val captured = grabCaptured() ?: return@withContext null
        val frame = captured.bitmap
        findGreenSend(frame)?.let {
            Log.i(
                TAG,
                "locate green src=${captured.source} fresh=${captured.guaranteedFresh} " +
                    "at=(${it.centerX},${it.centerY}) size=${it.width}x${it.height}",
            )
            return@withContext it
        }
        val lines = ocrLines(frame) ?: return@withContext null
        val target = pickSend(lines, frame.height)
        Log.i(
            TAG,
            "locate ocr src=${captured.source} fresh=${captured.guaranteedFresh} " +
                "target=${target?.let { "(${it.centerX},${it.centerY})" }} lines=${lines.size}",
        )
        target
    }

    /**
     * 发送后上屏回读（方案 §四.A.3，模式 B 证据源）：当前帧 OCR 文本是否包含
     * [clip]。取帧失败按「未包含」处理（调用方判负降级——宁降级不假成功）。
     */
    suspend fun frameContains(clip: String): Boolean = withContext(Dispatchers.Default) {
        val lines = frameLines() ?: return@withContext false
        frameContains(lines, clip)
    }

    /**
     * 发送证据三态（2026-10-27 真机「auto_sent 假成功」修复）：点击后先看
     * 原发送按钮是否仍在原位——
     * - 首帧仍在 → 再等一拍复检，仍在 = [SendProof.NOT_SENT]（草稿还在输入框，
     *   文本回读即使命中也是输入框里的草稿，绝不能再判成功）；
     * - 按钮消失 → 以文本回读作为上屏证据：命中 = [SendProof.SENT]，未命中隔
     *   一拍重查，仍不中 = [SendProof.UNKNOWN]（证据不足，按失败降级，不再点，
     *   防重复发送）；
     * - 取帧失败同样只在文本命中时判 [SendProof.SENT]，否则 UNKNOWN。
     *
     * 2026-10-27 二次修正（回复节奏/稳定性）：NOT_SENT 判负会触发「重定位再点」，
     * 只有**当场抓拍**的帧才配得上这个结论。投影帧是 latest-frame 语义（画面静止
     * 时会把上一拍缓存当新帧，实测 09:52 假 NOT_SENT 让 10.2s 派发白跑一趟），
     * 因此非保证新鲜的两个帧一律降级为「只认正证据」——按钮看起来还在时也不
     * 判负，避免重复发送。
     */
    suspend fun sendProof(clips: List<String>, tapped: SendTarget): SendProof =
        withContext(Dispatchers.Default) {
            delay(PostSendVerifier.VERIFY_DELAY_MS)
            val first = grabCaptured()
            if (first != null) {
                val still = findGreenSend(first.bitmap)
                if (still != null && sameButton(still, tapped)) {
                    if (!first.guaranteedFresh) {
                        // 帧不保证新鲜：「按钮仍在」不足以证明未发出，只认文本正证据。
                        return@withContext proofFromCaptured(first, clips)
                    }
                    // 渲染抖动容错：再等一拍；仍在原位即强证据「未发出」。
                    delay(PostSendVerifier.RETRY_DELAY_MS)
                    val second = grabCaptured()
                    val stillSecond = second?.let { findGreenSend(it.bitmap) }
                    if (second != null && second.guaranteedFresh &&
                        stillSecond != null && sameButton(stillSecond, tapped)
                    ) {
                        return@withContext SendProof.NOT_SENT
                    }
                    return@withContext proofFromCaptured(second ?: first, clips)
                }
                // 2026-10-27 回复慢归因：当场抓拍的新鲜帧里**绿色发送键整体消失**
                // = 输入框已空（微信只在草稿非空时显示绿键）= 消息已离开发送态，
                // 直接判 SENT——省掉一轮 OCR（0.4~0.7s）+ 可能的 800ms 重查。
                // 只认当场抓拍：投影帧是 latest-frame 语义，画面静止时可能是点击
                // 前的缓存帧，用它判「已发出」会假成功（宁慢勿错）。
                if (first.guaranteedFresh && still == null) return@withContext SendProof.SENT
                return@withContext proofFromCaptured(first, clips)
            }
            proofFromFrame(null, clips)
        }

    /**
     * 模式 B 注入前聚焦（2026-10-26 真机补齐）：无 a11y 时剪贴板 PASTE 只对
     * 已聚焦输入框生效；微信默认停在「按住 说话」语音条，直接 paste 会静默
     * 丢失，随后定位「发送」必败（台账 send_button_not_found）。
     *
     * 2026-10-27 修复：绿色发送按钮在场 = 输入框已聚焦且有草稿（微信只在
     * 输入非空时显示绿色发送键），直接返回，**不再点任何坐标**——上一版在
     * 键盘已打开时按底部比例补点，实际落到 Sogou 键盘，触发「随写」面板。
     * 其余形态见 [pickFocusAnchor]：语音条先切键盘；键盘打开且输入为空时
     * 点键盘上方输入栏中线；键盘收起时点底部输入栏。
     *
     * 返回 false 仅表示取帧/OCR 失败，调用方按 best-effort 继续注入（与
     * [SendSequence] 的 focusStep 口径一致）。
     *
     * 2026-10-27 回复慢归因（[imeShownProbe] 快通道）：IME 已弹起 ⇒ 聊天页
     * 输入框已持有焦点（微信仅在输入框聚焦时弹键盘），取帧/像素/OCR 判定与
     * 坐标 tap/落定等待整段可省——真机实测该段 2.2~2.5s，快通道 ~0.15s
     * （一次 dumpsys）。探针异常/为 null 一律回落完整判定，绝不因探针故障
     * 误判「已聚焦」。
     */
    suspend fun focusInput(
        injector: InputInjector,
        imeShownProbe: (suspend () -> Boolean)? = null,
    ): Boolean = withContext(Dispatchers.Default) {
        if (imeShownProbe != null && runCatching { imeShownProbe() }.getOrDefault(false)) {
            imeOpenAtSend = true
            Log.d(TAG, "focus fast-path: ime shown, skip frame/ocr/tap")
            return@withContext true
        }
        // 探针未命中/未提供：默认键盘收起，允许 [findGreenSend] 扫底部输入栏。
        imeOpenAtSend = false
        val frame = grabFrame() ?: return@withContext false
        findGreenSend(frame)?.let {
            // 绿键在 0.30~0.75H ⇒ 键盘展开（随 IME 上移）；在底部段 ⇒ 收起态。
            imeOpenAtSend = it.centerY < frame.height * GREEN_SEND_MAX_Y_RATIO
            Log.i(TAG, "focus green present y=${it.centerY}, ime=$imeOpenAtSend")
            return@withContext true
        }
        // 绿色键不在场：可能是未聚焦的语音条（OCR 全帧判定，最坏 ~1.5s）。
        // 先给 IME 探针第二次机会——未聚焦原因多半是上一步探针调用失败，重探
        // 命中即省掉这 ~1.5s（发送链的概览名）。
        if (imeShownProbe != null && runCatching { imeShownProbe() }.getOrDefault(false)) {
            imeOpenAtSend = true
            Log.d(TAG, "focus fast-path (retry): ime shown, skip ocr/tap")
            return@withContext true
        }
        val lines = ocrLines(frame) ?: return@withContext false
        val anchor = pickFocusAnchor(lines, frame.height)
        if (anchor.voice) {
            val voiceX = (frame.width * VOICE_TOGGLE_X_RATIO).toInt()
            Log.i(TAG, "focus anchor=voice tap=($voiceX,${anchor.centerY})")
            injector.tap(voiceX, anchor.centerY)
            delay(SWITCH_SETTLE_MS)
            // 切换成功的最强旁证是键盘弹起（微信仅在输入框聚焦时弹 IME）：
            // 探针命中即返回，省掉一次全帧 OCR（0.4~0.7s）。探针为 null/异常
            // 时仍走下面的 OCR 复检，口径不变。
            if (imeShownProbe != null && runCatching { imeShownProbe() }.getOrDefault(false)) {
                imeOpenAtSend = true
                Log.d(TAG, "focus fast-path (voice->ime): ime shown, skip recheck ocr")
                return@withContext true
            }
            // 切换后复检：键盘已弹出则输入框自动获得焦点，直接交给 PASTE；
            // 仍判定语音条（同高）说明切换手势未生效，才补点旧锚点。
            val stillVoice = frameLines()?.let { after ->
                pickFocusAnchor(after, frame.height).let {
                    it.voice && abs(it.centerY - anchor.centerY) <= VOICE_RECHECK_TOLERANCE_PX
                }
            } ?: false
            if (!stillVoice) {
                imeOpenAtSend = true
                return@withContext true
            }
            imeOpenAtSend = false
        }
        val tapX = (frame.width * INPUT_FIELD_X_RATIO).toInt()
        Log.i(TAG, "focus anchor=input tap=($tapX,${anchor.centerY})")
        injector.tap(tapX, anchor.centerY)
        delay(FOCUS_SETTLE_MS)
        if (imeShownProbe != null) {
            val imeNow = runCatching { imeShownProbe() }.getOrDefault(false)
            imeOpenAtSend = imeNow
            // 2026-10-27 稳定性：tap 后 IME 仍未弹起 = 输入框可能没拿到焦点
            //（落在输入栏边缘/手势区）。同锚点补点一次再等一拍；探针异常不
            // 补点（保持旧口径，绝不凭猜测连点）。
            if (!imeNow) {
                Log.w(TAG, "focus ime still false after tap, retry tap=($tapX,${anchor.centerY})")
                injector.tap(tapX, anchor.centerY)
                delay(FOCUS_RETRY_SETTLE_MS)
                imeOpenAtSend = runCatching { imeShownProbe() }.getOrDefault(false)
            }
        } else {
            // 无探针：点击输入框的标准结果就是键盘展开，按展开态处理，
            // 禁止底部扫描（宁可报定位失败，也不误点 Sogou 键盘键）。
            imeOpenAtSend = true
        }
        true
    }

    /** 证据帧回读：命中候选片段即 SENT；否则隔一拍取新帧重查，仍不中即 UNKNOWN。 */
    private suspend fun proofFromFrame(frame: Bitmap?, clips: List<String>): SendProof {
        val candidates = clips.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return SendProof.UNKNOWN
        if (frame != null) {
            val lines = ocrLines(frame)
            if (lines != null && frameContainsAny(lines, candidates)) return SendProof.SENT
        }
        delay(PostSendVerifier.RETRY_DELAY_MS)
        val retryLines = frameLines() ?: return SendProof.UNKNOWN
        return if (frameContainsAny(retryLines, candidates)) SendProof.SENT else SendProof.UNKNOWN
    }

    /** 同 [proofFromFrame]，但首帧已带在手（省一次抓拍）。 */
    private suspend fun proofFromCaptured(first: FrameSource.Captured, clips: List<String>): SendProof {
        val candidates = clips.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return SendProof.UNKNOWN
        val lines = ocrLines(first.bitmap)
        if (lines != null && frameContainsAny(lines, candidates)) return SendProof.SENT
        delay(PostSendVerifier.RETRY_DELAY_MS)
        val retryLines = frameLines() ?: return SendProof.UNKNOWN
        return if (frameContainsAny(retryLines, candidates)) SendProof.SENT else SendProof.UNKNOWN
    }

    private suspend fun frameContainsAny(lines: List<OcrLine>, clips: List<String>): Boolean =
        clips.any { frameContains(lines, it) }

    private fun sameButton(a: SendTarget, b: SendTarget): Boolean =
        abs(a.centerX - b.centerX) <= SEND_BUTTON_STILL_TOLERANCE_PX &&
            abs(a.centerY - b.centerY) <= SEND_BUTTON_STILL_TOLERANCE_PX

    private suspend fun grabFrame(): Bitmap? = frameSource.grabFrame().getOrNull()

    /** 带来源/新鲜度标记的取帧（投影帧不保证当场抓拍，判负前须看标记）。 */
    private suspend fun grabCaptured(): FrameSource.Captured? =
        frameSource.grabFrameWithFreshness().getOrNull()

    private suspend fun frameLines(): List<OcrLine>? {
        val frame = grabFrame() ?: return null
        return ocrLines(frame)
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

    /**
     * 最近一次发送链确认的 IME 状态（[focusInput] 维护）：true 时底部绿键按
     * Sogou 键盘键处理，禁止第二段扫描（见 [findGreenSend]）。
     */
    @Volatile
    private var imeOpenAtSend = false

    /** 像素级绿色发送按钮定位：取帧像素 → 纯函数 [findGreenSend]。 */
    private fun findGreenSend(frame: Bitmap): SendTarget? {
        val width = frame.width
        val height = frame.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        runCatching { frame.getPixels(pixels, 0, width, 0, 0, width, height) }
            .getOrElse { return null }
        return findGreenSend(pixels, width, height, imeOpenAtSend)
    }

    internal companion object {

        private const val TAG = "OcrSendLocator"

        /** 输入栏锚点：centerY 为输入栏中线；voice=true 表示当前是「按住 说话」语音条。 */
        internal data class InputAnchor(val centerY: Int, val voice: Boolean)

        /**
         * 像素级绿色发送按钮定位（纯函数，JVM 可测）：两段式纵向扫描。
         * 第一段 [GREEN_SEND_MIN_Y_RATIO]~[GREEN_SEND_MAX_Y_RATIO]（键盘展开时
         * 绿键在 ~0.43H）；[imeOpen]=false 时补扫第二段
         * [GREEN_SEND_MAX_Y_RATIO]~[GREEN_SEND_FALLBACK_MAX_Y_RATIO]——键盘收起
         * 时绿键贴底部输入栏 ~0.93H，旧单段上限 0.75 在收起态恒扫不到（真机
         * 12:16/12:17 两次 `send_button_not_found` 的直接根因）。
         * [imeOpen]=true 时**禁止**扫底部：Sogou 键盘右下角也有同色「发送/回车」
         * 键，扫下去会点到键盘上（旧单测 `ignoresImeGreenKeyAtBottom` 的口径）。
         */
        internal fun findGreenSend(
            pixels: IntArray,
            width: Int,
            height: Int,
            imeOpen: Boolean = false,
        ): SendTarget? {
            scanGreenSend(pixels, width, height, GREEN_SEND_MIN_Y_RATIO, GREEN_SEND_MAX_Y_RATIO)
                ?.let { return it }
            if (imeOpen) return null
            return scanGreenSend(
                pixels,
                width,
                height,
                GREEN_SEND_MAX_Y_RATIO,
                GREEN_SEND_FALLBACK_MAX_Y_RATIO,
            )
        }

        /**
         * 单段扫描：屏幕右半找微信品牌绿连通块，过滤尺寸/长宽比/填充率后取
         * 最靠下者。扫描按 [GREEN_SCAN_STEP] 采样，输出坐标换算回屏幕像素。
         */
        private fun scanGreenSend(
            pixels: IntArray,
            width: Int,
            height: Int,
            minYRatio: Float,
            maxYRatio: Float,
        ): SendTarget? {
            if (width <= 0 || height <= 0 || pixels.size < width * height) return null
            val step = GREEN_SCAN_STEP
            val cols = width / step
            val rows = height / step
            if (cols <= 0 || rows <= 0) return null
            val xFrom = ((width * GREEN_SEND_MIN_X_RATIO).toInt() / step).coerceIn(0, cols)
            val yFrom = ((height * minYRatio).toInt() / step).coerceIn(0, rows)
            val yTo = ((height * maxYRatio).toInt() / step).coerceIn(0, rows)
            if (xFrom >= cols || yFrom >= yTo) return null
            val mask = BooleanArray(cols * rows)
            for (gy in yFrom until yTo) {
                val y = gy * step
                for (gx in xFrom until cols) {
                    if (isSendGreen(pixels[y * width + gx * step])) {
                        mask[gy * cols + gx] = true
                    }
                }
            }
            val queue = IntArray(cols * rows)
            var best: SendTarget? = null
            var bestBottom = -1
            for (gy in yFrom until yTo) {
                for (gx in xFrom until cols) {
                    val seed = gy * cols + gx
                    if (!mask[seed]) continue
                    mask[seed] = false
                    var head = 0
                    var tail = 0
                    queue[tail++] = seed
                    var minX = gx
                    var maxX = gx
                    var minY = gy
                    var maxY = gy
                    var count = 0
                    fun enqueue(x: Int, y: Int) {
                        val idx = y * cols + x
                        if (!mask[idx]) return
                        mask[idx] = false
                        queue[tail++] = idx
                    }
                    while (head < tail) {
                        val cur = queue[head++]
                        val cx = cur % cols
                        val cy = cur / cols
                        count++
                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy
                        if (cx > xFrom) enqueue(cx - 1, cy)
                        if (cx + 1 < cols) enqueue(cx + 1, cy)
                        if (cy > yFrom) enqueue(cx, cy - 1)
                        if (cy + 1 < yTo) enqueue(cx, cy + 1)
                    }
                    val boxW = (maxX - minX + 1) * step
                    val boxH = (maxY - minY + 1) * step
                    if (boxW !in GREEN_SEND_MIN_WIDTH_PX..GREEN_SEND_MAX_WIDTH_PX) continue
                    if (boxH !in GREEN_SEND_MIN_HEIGHT_PX..GREEN_SEND_MAX_HEIGHT_PX) continue
                    val aspect = boxW.toFloat() / boxH
                    if (aspect < GREEN_SEND_MIN_ASPECT || aspect > GREEN_SEND_MAX_ASPECT) continue
                    val fill = count.toFloat() * step * step / (boxW.toFloat() * boxH)
                    if (fill < GREEN_SEND_MIN_FILL) continue
                    if ((maxX + 1) * step < width * GREEN_SEND_MIN_RIGHT_RATIO) continue
                    val bottom = (maxY + 1) * step
                    if (bottom > bestBottom) {
                        bestBottom = bottom
                        best = SendTarget(
                            centerX = ((minX + maxX + 1) * step) / 2,
                            centerY = ((minY + maxY + 1) * step) / 2,
                            width = boxW,
                            height = boxH,
                        )
                    }
                }
            }
            return best
        }

        private fun isSendGreen(argb: Int): Boolean {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return abs(r - SEND_GREEN_R) <= GREEN_CHANNEL_TOLERANCE &&
                abs(g - SEND_GREEN_G) <= GREEN_CHANNEL_TOLERANCE &&
                abs(b - SEND_GREEN_B) <= GREEN_CHANNEL_TOLERANCE
        }

        /**
         * 从 OCR 行中挑出发送按钮点击中心（与 [A11ySendLocator] 同一启发式
         * 口径）。带帧高时优先取键盘上方（[SEND_ABOVE_IME_RATIO] 以上）的
         * 候选——微信绿色发送键随键盘上移到 y≈0.43H，而 Sogou 键盘右下角
         * 也有「发送」键；只有上方无候选时才回退全帧。
         */
        internal fun pickSend(lines: List<OcrLine>): SendTarget? = pickSend(lines, Int.MAX_VALUE)

        internal fun pickSend(lines: List<OcrLine>, frameHeight: Int): SendTarget? {
            val candidates = lines.asSequence()
                .filter { line -> SEND_BUTTON_QUERIES.any { line.text.trim().lowercase().contains(it) } }
                .filter { it.bottom - it.top <= MAX_SEND_NODE_HEIGHT }
                .filter { it.right - it.left <= MAX_SEND_NODE_WIDTH }
                .toList()
            if (candidates.isEmpty()) return null
            val aboveIme = if (frameHeight == Int.MAX_VALUE) {
                candidates
            } else {
                candidates.filter { it.centerY <= frameHeight * SEND_ABOVE_IME_RATIO }
            }
            return (aboveIme.ifEmpty { candidates })
                .maxWithOrNull(compareBy({ it.bottom }, { it.right }))
                ?.let { SendTarget(it.centerX, it.centerY) }
        }

        /**
         * 帧文本包含判定（纯函数，JVM 可测）：OCR 会把同一条气泡拆成多行，逐行
         * contains 会漏判（2026-10-26 真机 kickoff 假阴性根因之一）。这里先拼接
         * 全部行，再统一去空白/去标点、忽略大小写后做包含匹配；clip 与帧文本走
         * 同一归一化口径，标点差异（，。：！）不再造成假阴性。
         */
        internal fun frameContains(lines: List<OcrLine>, clip: String): Boolean {
            val needle = normalizeFrameText(clip)
            if (needle.isEmpty()) return false
            val haystack = normalizeFrameText(lines.joinToString(separator = "") { it.text })
            return haystack.contains(needle)
        }

        /**
         * 从 OCR 行中挑输入栏锚点（纯函数，JVM 可测）：只认屏幕下部输入栏区；
         * 语音条优先（需先切键盘），其次同行「发送」按钮，最后用比例兜底。
         * 既有调用/断言保持原口径；聚焦决策走 [pickFocusAnchor]。
         */
        internal fun pickInputAnchor(lines: List<OcrLine>, frameHeight: Int): InputAnchor {
            val bottomLines = lines.filter { it.centerY >= frameHeight * INPUT_ZONE_TOP_RATIO }
            val voice = bottomLines
                .filter { normalizeInputText(it.text).contains("按住说话") }
                .maxWithOrNull(compareBy({ it.centerY }, { it.right }))
            if (voice != null) return InputAnchor(voice.centerY, voice = true)
            val send = pickSend(bottomLines)
            if (send != null) return InputAnchor(send.centerY, voice = false)
            return InputAnchor((frameHeight * INPUT_BAR_Y_RATIO).toInt(), voice = false)
        }

        /**
         * 聚焦锚点（纯函数，JVM 可测；2026-10-27 IME 误触修复）：
         * 1. 语音条（仅屏幕下部）→ 先切键盘；
         * 2. 键盘上方存在「发送」（微信绿色键，随键盘上移）→ 点同一行输入框；
         * 3. 识别到 IME 特征词 → 键盘已开、输入为空，点键盘上方输入栏比例位；
         * 4. 其余（键盘收起）→ 点底部输入栏比例位。
         * 绝不把底部比例位用在键盘已打开的场景（上一版误触 Sogou「随写」）。
         */
        internal fun pickFocusAnchor(lines: List<OcrLine>, frameHeight: Int): InputAnchor {
            val bottomLines = lines.filter { it.centerY >= frameHeight * INPUT_ZONE_TOP_RATIO }
            val voice = bottomLines
                .filter { normalizeInputText(it.text).contains("按住说话") }
                .maxWithOrNull(compareBy({ it.centerY }, { it.right }))
            if (voice != null) return InputAnchor(voice.centerY, voice = true)
            val upperSend = pickSend(lines, frameHeight)
                ?.takeIf { it.centerY <= frameHeight * SEND_ABOVE_IME_RATIO }
            if (upperSend != null) return InputAnchor(upperSend.centerY, voice = false)
            if (looksLikeIme(lines, frameHeight)) {
                return InputAnchor((frameHeight * KEYBOARD_OPEN_INPUT_Y_RATIO).toInt(), voice = false)
            }
            return InputAnchor((frameHeight * INPUT_BAR_Y_RATIO).toInt(), voice = false)
        }

        /** IME 特征词/键位识别（纯函数）：底部区域的输入法 chrome，不含聊天正文常用词。 */
        internal fun looksLikeIme(lines: List<OcrLine>, frameHeight: Int): Boolean {
            val bottom = lines.filter { it.centerY >= frameHeight * IME_ZONE_TOP_RATIO }
            return bottom.any { line ->
                val text = normalizeInputText(line.text)
                IME_TOKENS.any { text.contains(it) }
            }
        }

        private fun normalizeInputText(text: String): String =
            text.filterNot { it.isWhitespace() }.lowercase()

        /** 帧文本归一化：只保留字母/数字/CJK，忽略空白与标点（OCR 标点不稳定的口径）。 */
        private fun normalizeFrameText(text: String): String =
            text.filter { it.isLetterOrDigit() }.lowercase()

        /** 输入栏只在屏幕下部（聊天正文之上）判定。 */
        private const val INPUT_ZONE_TOP_RATIO = 0.78f

        /** IME chrome 只在屏幕最下部判定（聊天正文/输入栏都不在此带）。 */
        private const val IME_ZONE_TOP_RATIO = 0.80f

        /** 微信绿色发送键随键盘上移后的中线比例（1077/2460，2026-10-26 实测）。 */
        private const val KEYBOARD_OPEN_INPUT_Y_RATIO = 0.438f

        /** 发送候选优先取该比例以上的（其下视为 IME 键盘发送键）。 */
        private const val SEND_ABOVE_IME_RATIO = 0.75f

        /** 输入栏左端键盘/语音切换图标中心的比例位置（微信 2026-10-26 像素实测）。 */
        private const val VOICE_TOGGLE_X_RATIO = 0.055f

        /** 输入框中心的比例位置（避开左右图标与发送按钮）。 */
        private const val INPUT_FIELD_X_RATIO = 0.45f

        /** 文本模式输入为空且键盘收起时的输入栏中线比例（2304/2460）。 */
        private const val INPUT_BAR_Y_RATIO = 0.936f

        /** 语音条 → 文本模式的转场稳定等待。 */
        private const val SWITCH_SETTLE_MS = 900L

        /** 切换后复检仍视为「同一语音条」的纵向容差（像素）。 */
        private const val VOICE_RECHECK_TOLERANCE_PX = 40

        /** 点击输入框到键盘就绪/焦点落定的等待。 */
        private const val FOCUS_SETTLE_MS = 800L

        /** 聚焦补点后的等待（键盘未弹起时同锚点再点一次，2026-10-27）。 */
        private const val FOCUS_RETRY_SETTLE_MS = 500L

        /** 发送按钮「仍在原位」的坐标容差（像素）。 */
        private const val SEND_BUTTON_STILL_TOLERANCE_PX = 60

        /** 像素扫描采样步长（2px → 半分辨率，1080×2460 约 66 万采样点）。 */
        private const val GREEN_SCAN_STEP = 2

        /** 微信品牌绿 #07C160 各通道值。 */
        private const val SEND_GREEN_R = 7
        private const val SEND_GREEN_G = 193
        private const val SEND_GREEN_B = 96

        /** 品牌绿通道容差（排除我方气泡 #95EC69：R 差 142 远超容差）。 */
        private const val GREEN_CHANNEL_TOLERANCE = 55

        /** 发送键只可能出现在屏幕右侧。 */
        private const val GREEN_SEND_MIN_X_RATIO = 0.55f
        private const val GREEN_SEND_MIN_RIGHT_RATIO = 0.70f

        /** 发送键纵向范围：键盘上方输入栏区（下方是 IME 键盘，不在候选内）。 */
        private const val GREEN_SEND_MIN_Y_RATIO = 0.30f
        private const val GREEN_SEND_MAX_Y_RATIO = 0.75f

        /** 第二段扫描上沿（键盘收起时绿键在输入栏 ~0.93H）。 */
        private const val GREEN_SEND_FALLBACK_MAX_Y_RATIO = 0.97f

        /** 发送键外接盒尺寸范围（真机实测 134×85 px）。 */
        private const val GREEN_SEND_MIN_WIDTH_PX = 60
        private const val GREEN_SEND_MAX_WIDTH_PX = 280
        private const val GREEN_SEND_MIN_HEIGHT_PX = 40
        private const val GREEN_SEND_MAX_HEIGHT_PX = 200

        /** 长宽比/填充率过滤（排除细线、整条绿色横幅、圆角外的零散像素）。 */
        private const val GREEN_SEND_MIN_ASPECT = 1.0f
        private const val GREEN_SEND_MAX_ASPECT = 3.2f
        private const val GREEN_SEND_MIN_FILL = 0.35f

        /** IME chrome 特征词（Sogou/系统输入法面板；不含聊天常用单字，防误判）。 */
        private val IME_TOKENS = listOf(
            "随写", "空格", "拼音", "手写", "笔画", "中/英", "中英", "换行", "符",
        )
    }
}
