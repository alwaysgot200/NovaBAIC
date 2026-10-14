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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 连续参谋的差分引擎：按 [AdvisorPrefs] 的节奏抓投影帧，运动检测 →
 * 静默期稳定后触发 OCR → 与上次内容去重 → 交给 [ScreenAdvisor] 的串行
 * LLM 队列。静止画面零 OCR 零 LLM。
 *
 * 回合调度是「帧生产 / LLM 消费」两段：抓帧循环永不因 LLM 忙而停，去重
 * 通过的新帧 trySend 进 CONFLATED 通道——LLM 期间的多次画面变化只保留
 * 最新一帧（合并），上一回合 LLM 一结束，下一帧**立即**送出，形成连续的
 * OCR→LLM→OCR→LLM。
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
    private var consumerJob: Job? = null

    /** 当前正在跑的 LLM 回合（消费者的子 Job，取消它不影响后续回合）。 */
    private var currentTurn: Job? = null

    /** 帧生产→LLM 消费的合并通道：CONFLATED 保证 LLM 忙时只保留最新帧。 */
    private var turnChannel: Channel<String>? = null

    @Volatile
    private var paused = false

    @Volatile
    private var lastOcrText: String? = null

    /** 上次已送 OCR 的稳定帧：预筛应与它比，而不是与候选帧比（候选帧与静止后的当前帧恒等）。 */
    @Volatile
    private var lastOcrGray: FrameQuality.GrayFrame? = null

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
        lastOcrGray = null
        val channel = Channel<String>(Channel.CONFLATED)
        turnChannel = channel
        // LLM 消费者：永远串行；上一回合 join 一结束立刻取通道里的最新帧，
        // 无帧则挂起等待——「LLM 完成」与「画面变化出新帧」在此合并即发。
        consumerJob = engineScope.launch {
            for (text in channel) {
                val turn = launch {
                    // 连续模式后台回合：重试预算强制收窄（见 ScreenAdvisor.submitTurn）。
                    advisor.submitTurn(advisor.autoScreenContent(text), backgroundTurn = true)
                }
                currentTurn = turn
                turn.join()
                currentTurn = null
            }
        }
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
                // 轻量预筛：与**上次已 OCR 的帧**比——几乎一致说明用户停在同一内容，
                // 不做 OCR。注意不能与候选帧比：候选帧是静止后定格的，与当前帧恒等，
                // 会把「切到新页面并停住」这个核心场景永久挡住。
                val lastGray = lastOcrGray
                if (lastGray != null &&
                    FrameQuality.blockDiff(lastGray.blocks, frame.blocks) < GRAY_RESEND_THRESHOLD
                ) {
                    continue
                }

                candidate = null // 触发后重新蓄水
                val masked = withContext(Dispatchers.Default) {
                    FrameQuality.maskRects(current.bytes, maskProvider())
                }
                val text = withContext(Dispatchers.Default) {
                    ocrProvider.recognizeText(masked).getOrNull()?.trim()
                }
                if (text.isNullOrEmpty()) {
                    // OCR 空结果（纯图页）：推进灰度基线，免得每 tick 重 OCR。
                    lastOcrGray = frame
                    continue
                }
                val last = lastOcrText
                if (last != null && FrameQuality.textSimilarity(last, text) > DEDUP_SIMILARITY) {
                    android.util.Log.d(TAG, "ocr dedup hit, len=${text.length}")
                    lastOcrGray = frame
                    continue
                }
                lastOcrText = text
                lastOcrGray = current.gray
                android.util.Log.d(TAG, "dispatching turn, len=${text.length}")
                // 帧生产与 LLM 消费解耦：CONFLATED 通道忙时只留最新帧，
                // 不阻塞抓帧循环；回合串行性由消费者保证。
                channel.trySend(text)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        consumerJob?.cancel()
        consumerJob = null
        currentTurn = null
        turnChannel?.close()
        turnChannel = null
        lastOcrText = null
        lastOcrGray = null
    }

    /** 停止当前正在进行的 LLM 回合（输入行「停止」键；消费者与抓帧循环继续）。 */
    fun cancelRound() {
        currentTurn?.cancel()
        currentTurn = null
    }

    fun isRoundActive(): Boolean = currentTurn?.isActive == true

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

        /** 与上次已 OCR 帧的灰度差低于该值视为同一内容，不重发 LLM。
         *  取 0.02：状态栏时钟跳字等微小局部变化（实测均值 ≈0.01）被挡掉，
         *  而真正切页/滚动的差值通常在 0.1 量级，不受影响。 */
        const val GRAY_RESEND_THRESHOLD = 0.02f
    }
}
