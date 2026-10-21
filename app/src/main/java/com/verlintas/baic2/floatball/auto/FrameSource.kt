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
import android.graphics.BitmapFactory
import android.os.Build
import com.verlintas.baic2.device.impl.a11y.AndroidAccessibilityBridge
import com.ven.assists.mp.MPManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 找图帧源阶梯（方案 §17.3）：a11y 免授权截图（API 30+，首选）→ Shizuku
 * screencap（原生分辨率清晰帧，模式 2 主源）→ assists-mp 投影抽帧（兜底）。
 *
 * [ImageMatcher] 只认本类产出的 Bitmap，不关心来源；自家 [com.verlintas.baic2.floatball.FloatCaptureBridge]
 * 保留于屏幕参谋投影读屏链路，两套投影各守领地（参谋=自家链，自动托管找图=assists-mp）。
 * 解码一律 [Dispatchers.Default]（主线程禁位图解码，AGENTS §8 教训 3）。
 */
@Singleton
class FrameSource @Inject constructor(
    private val a11y: AndroidAccessibilityBridge,
    private val shizuku: ShizukuInjector,
) {

    /** 当前帧；三级阶梯都失败时返回 actionable 失败。 */
    suspend fun grabFrame(): Result<Bitmap> = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val viaA11y = a11y.captureScreen().mapCatching { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("frame_decode_failed")
            }
            if (viaA11y.isSuccess) return@withContext viaA11y
        }
        // Shizuku screencap：原生分辨率清晰帧——HyperOS 投影帧 ~2× 下采样
        // 模糊（ML Kit OCR lines=0，2026-10-21 真机实测），可用时优先于投影。
        val viaScreencap = runCatching { shizuku.screencap() }.getOrNull()
        if (viaScreencap != null) return@withContext Result.success(viaScreencap)
        val viaProjection = runCatching { MPManager.takeScreenshot2Bitmap() }
        if (viaProjection.isSuccess && viaProjection.getOrNull() != null) {
            return@withContext Result.success(viaProjection.getOrNull()!!)
        }
        Result.failure(
            IllegalStateException(
                "frame_unavailable: a11y capture, shizuku screencap and projection " +
                    "are all unavailable (模式 B 需先完成投影授权，见就绪门)",
            ),
        )
    }
}
