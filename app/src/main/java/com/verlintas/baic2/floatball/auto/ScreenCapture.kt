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

import android.graphics.Bitmap
import android.util.Log
import com.verlintas.baic2.floatball.FrameQuality
import com.verlintas.baic2.floatball.shizuku.ShizukuGate
import com.ven.assists.mp.MPManager
import com.ven.assists.service.AssistsService
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 免无障碍截屏门面（docs/2026-10-22-连续模式录屏优化与免无障碍截屏方案.md）。
 *
 * 模块 A——投影授权统一编排：a11y 开 → [MPManager.request] 弹窗自动点
 * （autoAllow）；Shizuku 活 → appops PROJECT_MEDIA 静默直过后再 request；
 * 皆无 → 手动点弹窗（超时放大）。[ensureProjection] 必须从本 App 的前台
 * Activity 上下文调用（授权弹窗走 Activity 结果通道，Service 内发起必失败
 * ——AGENTS §8 教训 2；服务层经 FloatCaptureActivity 中转）。
 *
 * 模块 B——免 a11y 截屏：常驻投影 acquire 主源（毫秒级、原生分辨率）+
 * [FrameQuality] 空白帧质检（黑帧=FLAG_SECURE/息屏/过渡）+ Shizuku
 * screencap 兜底；失败显式报因（§4.5.2 禁静默）。感知全程无 a11y 痕迹
 * （反侦察，方案 §3）。
 *
 * 投影生命周期：常驻不主动释放（与 RPA/WebRTC 共用同一基础设施）；
 * 上游 ScreenshotProvider 保留给主框架聊天内截图，本门面不碰。
 */
@Singleton
class ScreenCapture @Inject constructor(
    private val shizuku: ShizukuInjector,
    private val shizukuGate: ShizukuGate,
) {

    /**
     * 投影会话真存活：imageReader 与 MediaProjection 句柄都在。vendor
     * isEnable 只看 imageReader（onStop 后不置空，读到假活），句柄在
     * onStop 时被置 null，故以两者都非空为准。
     */
    val projectionAlive: Boolean
        get() = MPManager.isEnable && MPManager.getMediaProjection() != null

    /**
     * 模块 A：投影授权统一编排（幂等，已建立直接复用）。返回是否已建立。
     * 调用方须处于本 App 前台 Activity 生命周期内（FloatCaptureActivity /
     * 设置页 / AdvisorProfileActivity 均满足）。
     */
    suspend fun ensureProjection(): Boolean {
        if (MPManager.isEnable) {
            Log.i(TAG, "ensureProjection: already enabled, reuse")
            return true
        }
        // Shizuku 活：appops PROJECT_MEDIA allow——授权框静默直过
        // （HyperOS 真机实测，docs/2026-10-21-投影免弹窗授权方案.md）。
        if (!shizukuGate.projectionSilentGranted()) {
            val grant = runCatching { shizukuGate.grantProjectionSilent() }.getOrNull()
            Log.i(TAG, "ensureProjection: appops grant=$grant")
        }
        // a11y 开 → 弹窗自动点（vendor 250ms 轮询点「立即开始」）；关 →
        // 用户手动点，超时须放大（vendor 默认 5s 用户来不及）。
        val autoAllow = AssistsService.getOrNull() != null
        val ok = runCatching {
            MPManager.request(
                autoAllow = autoAllow,
                timeOut = if (autoAllow) AUTO_ALLOW_TIMEOUT_MS else MANUAL_TIMEOUT_MS,
            )
        }.getOrDefault(false)
        Log.i(TAG, "ensureProjection: request autoAllow=$autoAllow ok=$ok isEnable=${MPManager.isEnable}")
        return ok && MPManager.isEnable
    }

    /**
     * 投影 acquire 原始帧（无质检）。[maxWaitMs] 内短轮询等首帧（投影刚
     * 建立/画面无变化时 Reader 为空）；返回 null = 无新帧或投影不可用，
     * 调用方据 [projectionAlive] 区分会话死亡与画面静止。
     */
    suspend fun acquireProjectionFrame(maxWaitMs: Long = FIRST_FRAME_WAIT_MS): Bitmap? {
        if (!MPManager.isEnable) return null
        var waited = 0L
        while (true) {
            val frame = runCatching { MPManager.takeScreenshot2Bitmap() }.getOrNull()
            if (frame != null) return frame
            if (waited >= maxWaitMs) return null
            delay(ACQUIRE_POLL_MS)
            waited += ACQUIRE_POLL_MS
        }
    }

    /**
     * 模块 B 主入口：免 a11y 截屏（三层保障，防黑白屏）。投影 acquire
     * 主源 + 空白质检重采，Shizuku screencap 兜底；全失败显式报因。
     * [qualityCheck]=false 供自带质检的调用方（拼接器）跳过重复检查。
     */
    suspend fun captureNow(
        maxWaitMs: Long = FIRST_FRAME_WAIT_MS,
        qualityCheck: Boolean = true,
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        if (MPManager.isEnable) {
            repeat(QUALITY_RETRY) { attempt ->
                val frame = acquireProjectionFrame(maxWaitMs)
                if (frame != null) {
                    val blank = qualityCheck && withContext(Dispatchers.Default) {
                        FrameQuality.isBlank(FrameQuality.grayBitmap(frame))
                    }
                    if (!blank) return@withContext Result.success(frame)
                    Log.w(TAG, "captureNow: projection frame blank (attempt=$attempt)")
                    if (attempt < QUALITY_RETRY - 1) delay(QUALITY_RETRY_DELAY_MS)
                }
            }
        }
        // 兜底：Shizuku screencap（原生分辨率清晰帧，秒级 shell 开销）。
        val viaScreencap = runCatching { shizuku.screencap() }.getOrNull()
        if (viaScreencap != null) {
            val blank = qualityCheck && withContext(Dispatchers.Default) {
                FrameQuality.isBlank(FrameQuality.grayBitmap(viaScreencap))
            }
            if (!blank) return@withContext Result.success(viaScreencap)
            Log.w(TAG, "captureNow: screencap frame blank")
        }
        Result.failure(
            IllegalStateException(
                if (MPManager.isEnable || viaScreencap != null) {
                    REASON_BLANK_FRAME
                } else {
                    REASON_CAPTURE_UNAVAILABLE
                },
            ),
        )
    }

    /**
     * bytes 版（OCR 消费者）：与 [captureNow] 同阶梯，产出 JPEG 90
     * （OCR 不在乎压缩格式，编码比 PNG 快数倍）。
     */
    suspend fun captureBytes(
        maxWaitMs: Long = FIRST_FRAME_WAIT_MS,
        qualityCheck: Boolean = true,
    ): Result<ByteArray> {
        val frame = captureNow(maxWaitMs, qualityCheck).getOrNull()
            ?: return Result.failure(IllegalStateException(REASON_CAPTURE_UNAVAILABLE))
        return withContext(Dispatchers.Default) {
            val sink = ByteArrayOutputStream(INITIAL_JPEG_BUFFER)
            if (frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, sink)) {
                Result.success(sink.toByteArray())
            } else {
                Result.failure(IllegalStateException("frame_compress_failed"))
            }
        }
    }

    companion object {
        private const val TAG = "ScreenCapture"

        /** 投影刚建立时等首帧的上限（Reader 从空到有通常 <300ms）。 */
        private const val FIRST_FRAME_WAIT_MS = 1_500L
        private const val ACQUIRE_POLL_MS = 120L
        private const val QUALITY_RETRY = 2
        private const val QUALITY_RETRY_DELAY_MS = 300L
        private const val AUTO_ALLOW_TIMEOUT_MS = 8_000L
        private const val MANUAL_TIMEOUT_MS = 60_000L
        private const val JPEG_QUALITY = 90
        private const val INITIAL_JPEG_BUFFER = 1 shl 20

        const val REASON_BLANK_FRAME = "blank_frame"
        const val REASON_CAPTURE_UNAVAILABLE = "capture_unavailable"
    }
}
