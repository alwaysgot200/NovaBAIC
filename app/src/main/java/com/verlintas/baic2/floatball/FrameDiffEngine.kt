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

package com.verlintas.baic2.floatball

import android.graphics.Rect
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.device.api.ScreenshotProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 连续参谋的差分引擎：按 [AdvisorPrefs] 的节奏抓投影帧，运动检测 →
 * 静默期稳定后触发 OCR → 与上次内容去重 → 交给 [ScreenAdvisor] 的串行
 * LLM 队列。静止画面零 OCR 零 LLM；LLM 期间照常抓帧但不重复排队。
 *
 * 自身浮窗（分屏面板/悬浮球）由服务层通过 [maskProvider] 提供已知矩形，
 * OCR 前涂黑——几何掩膜零误判。
 */
@Singleton
class FrameDiffEngine @Inject constructor(
    private val screenshotProvider: ScreenshotProvider,
    private val ocrProvider: OcrProvider,
    private val advisor: ScreenAdvisor,
    private val prefs: AdvisorPrefs,
) {

    sealed interface Signal {
        data class Failed(val reason: String) : Signal

        /** 达到单次时长上限，服务层应停止引擎并提示。 */
        data object SessionExpired : Signal
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var roundJob: Job? = null

    @Volatile
    private var paused = false

    @Volatile
    private var lastOcrText: String? = null

    /** 息屏时由服务层调用：暂停抓帧（投影镜像在息屏下本就无内容）。 */
    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun isRunning(): Boolean = loopJob?.isActive == true

    fun start(
        maskProvider: () -> List<Rect>,
        onSignal: (Signal) -> Unit,
    ) {
        if (loopJob?.isActive == true) return
        lastOcrText = null
        loopJob = engineScope.launch {
            val startedAt = System.currentTimeMillis()
            var baseline: FrameQuality.GrayFrame? = null
            var candidate: Candidate? = null
            var motionAtMs = 0L

            while (true) {
                val interval = prefs.state.value.pollIntervalMs
                delay(interval)
                if (paused) continue

                val maxSession = prefs.state.value.maxSessionMs
                if (maxSession > 0 && System.currentTimeMillis() - startedAt > maxSession) {
                    onSignal(Signal.SessionExpired)
                    return@launch
                }

                val bytes = screenshotProvider.capture().getOrNull()
                if (bytes == null) {
                    onSignal(Signal.Failed("projection_lost"))
                    return@launch
                }
                val frame = FrameQuality.grayFrame(bytes)
                if (frame == null || FrameQuality.isBlank(frame)) continue

                val previous = baseline
                baseline = frame
                if (previous == null) continue

                val motion = FrameQuality.blockDiff(previous.blocks, frame.blocks)
                val now = System.currentTimeMillis()
                if (motion > CHANGE_THRESHOLD) {
                    // 画面在动：候选帧作废，记录运动时间。
                    motionAtMs = now
                    candidate = null
                    continue
                }
                // 静止：从运动结束起算，稳定满静默窗才触发。
                val motionAnchor = if (motionAtMs == 0L) now else motionAtMs
                if (now - motionAnchor < interval * STABLE_TICKS) continue
                if (candidate == null) {
                    android.util.Log.d(TAG, "stable, candidate armed (motion=$motion)")
                    candidate = Candidate(bytes, frame)
                }

                val current = candidate ?: continue
                val last = lastOcrText
                if (last != null) {
                    // 与上次已处理内容几乎一致：用户没换新内容，跳过。
                    // 轻量预筛：先比灰度签名，几乎一致直接丢弃。
                    if (FrameQuality.blockDiff(current.gray.blocks, frame.blocks) < 0.01f) continue
                }

                candidate = null // 触发后重新蓄水
                val masked = withContext(Dispatchers.Default) {
                    FrameQuality.maskRects(current.bytes, maskProvider())
                }
                val text = withContext(Dispatchers.Default) {
                    ocrProvider.recognizeText(masked).getOrNull()?.trim()
                }
                if (text.isNullOrEmpty()) continue
                if (last != null && FrameQuality.textSimilarity(last, text) > DEDUP_SIMILARITY) {
                    android.util.Log.d(TAG, "ocr dedup hit, len=${text.length}")
                    continue
                }
                lastOcrText = text
                android.util.Log.d(TAG, "dispatching turn, len=${text.length}")
                // 串行队列在 advisor 内部：这里不等待，循环继续抓帧。
                if (roundJob?.isActive != true) {
                    roundJob = engineScope.launch { advisor.submitTurn(text) }
                }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        cancelRound()
        lastOcrText = null
    }

    /** 停止当前正在进行的 LLM 回合（输入行「停止」键；循环本身继续）。 */
    fun cancelRound() {
        roundJob?.cancel()
        roundJob = null
    }

    fun isRoundActive(): Boolean = roundJob?.isActive == true

    fun destroy() {
        stop()
        engineScope.cancel()
    }

    /** 触发时定格的候选帧。 */
    private class Candidate(val bytes: ByteArray, val gray: FrameQuality.GrayFrame)

    companion object {
        private const val TAG = "FrameDiffEngine"

        /** 8×8 分块平均亮度差超过该值视为"画面在动"。 */
        const val CHANGE_THRESHOLD = 0.035f

        /** 静默窗 = 抓帧间隔 × 该系数（约两个周期）。 */
        const val STABLE_TICKS = 2

        /** OCR 文本相似度高于该值视为重复内容。 */
        const val DEDUP_SIMILARITY = 0.8
    }
}
