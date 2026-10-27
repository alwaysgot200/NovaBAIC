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
import android.os.Build
import android.util.Log
import com.verlintas.baic2.device.impl.a11y.AndroidAccessibilityBridge
import com.ven.assists.mp.MPManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 找图帧源阶梯（方案 §17.3）：a11y 免授权截图（API 30+，首选）→ 自家常驻投影
 * （毫秒级）→ Shizuku screencap（原生分辨率清晰帧，模式 2 历史主源）→
 * assists-mp 投影抽帧（兜底）。
 *
 * 2026-10-27 真机「回复慢」归因修复：原阶梯在 a11y 被系统限频拒掉后直接落到
 * Shizuku screencap（写 /data/local/tmp + 读回 + MediaScanner，实测 **单帧
 * 2.4s**），而发送链一次要取 3~4 帧（聚焦/定位/取证）→ 单次派发 10.2s。自家
 * 投影帧实测 31ms（感知链同源），故提到 screencap 之前。
 *
 * **分辨率闸**：绿色发送键的像素阈值（宽 60~280px、右侧 70%）与 OCR 质量都按
 * 原生分辨率标定，投影下采样帧会把定位算歪 / OCR 掉字，此时宁可慢也不要错——
 * 帧宽不足 [MIN_FRAME_WIDTH_RATIO]×屏幕宽即视为不可用，继续走 screencap。
 */
@Singleton
class FrameSource @Inject constructor(
    private val a11y: AndroidAccessibilityBridge,
    private val shizuku: ShizukuInjector,
    private val screenCapture: ScreenCapture,
) {

    /**
     * 帧 + 来源信息。[guaranteedFresh] 表示「当场抓拍」：用到「未发出」这类
     * 强判上才安全（见 [OcrSendLocator.sendProof]）。
     */
    data class Captured(
        val bitmap: Bitmap,
        val guaranteedFresh: Boolean,
        val source: String,
    )

    /** 当前帧（兼容既有调用方）。 */
    suspend fun grabFrame(): Result<Bitmap> = grabFrameWithFreshness().map { it.bitmap }

    /**
     * 带来源标记的取帧。`guaranteedFresh=true` 只给当场抓拍源（a11y/screencap）；
     * 投影走 latest-frame 语义，画面静止时可能回到上一拍缓存，只能用于
     * 「已发出」类正证据，不能用于「未发出」判负（否则诱发重复点击）。
     */
    suspend fun grabFrameWithFreshness(): Result<Captured> = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 直取位图（2026-10-27）：免 PNG 编码/解码往返，每帧省 0.2~0.3s。
            a11y.captureScreenBitmap().getOrNull()?.let { return@withContext captured("a11y", it, fresh = true) }
        }
        // 自家常驻投影：毫秒级（感知链同源）。分辨率达标才采用。
        val projection = runCatching { screenCapture.acquireProjectionFrame(maxWaitMs = 0) }
            .getOrNull()
        if (projection != null) {
            val screenWidth = runCatching { a11y.screenSize()?.first }.getOrNull()
            if (resolutionUsable(projection.width, screenWidth)) {
                return@withContext captured("projection", projection, fresh = false)
            }
            Log.d(
                TAG,
                "projection frame ${projection.width}x${projection.height} below native " +
                    "width (screen=$screenWidth), falling back to screencap",
            )
        }
        // Shizuku screencap：原生分辨率清晰帧（秒级 shell 开销，保证当场抓拍）。
        val viaScreencap = runCatching { shizuku.screencap() }.getOrNull()
        if (viaScreencap != null) return@withContext captured("screencap", viaScreencap, fresh = true)
        if (projection != null) {
            // 低分辨率投影帧总比没有强（至少能读文字），但不算当场抓拍。
            return@withContext captured("projection_lowres", projection, fresh = false)
        }
        Result.failure(
            IllegalStateException(
                "frame_unavailable: a11y capture, projection and shizuku screencap " +
                    "are all unavailable (模式 B 需先完成投影授权，见就绪门)",
            ),
        )
    }

    private fun captured(source: String, bitmap: Bitmap, fresh: Boolean): Result<Captured> {
        Log.d(TAG, "frame src=$source ${bitmap.width}x${bitmap.height} fresh=$fresh")
        return Result.success(Captured(bitmap, guaranteedFresh = fresh, source = source))
    }

    internal companion object {

        private const val TAG = "FrameSource"

        /**
         * 分辨率闸（纯函数，JVM 可测）：帧宽 ≥ 屏宽 × [MIN_FRAME_WIDTH_RATIO]
         * 才算「原生分辨率可用」；拿不到屏宽时不做判断（放行，交由上层证据兜底）。
         */
        internal fun resolutionUsable(frameWidth: Int, screenWidth: Int?): Boolean {
            if (frameWidth <= 0) return false
            if (screenWidth == null || screenWidth <= 0) return true
            return frameWidth >= (screenWidth * MIN_FRAME_WIDTH_RATIO).toInt()
        }

        /** 原生分辨率容差（投影与屏宽常见 ±2px 偏差）。 */
        internal const val MIN_FRAME_WIDTH_RATIO = 0.9f
    }
}
