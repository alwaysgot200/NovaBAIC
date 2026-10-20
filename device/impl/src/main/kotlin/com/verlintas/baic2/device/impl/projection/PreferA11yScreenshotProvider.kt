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

package com.verlintas.baic2.device.impl.projection

import android.content.Intent
import android.os.Build
import com.verlintas.baic2.device.api.ScreenshotProvider
import com.verlintas.baic2.device.impl.a11y.AndroidAccessibilityBridge
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/**
 * ScreenshotProvider 绑定面（F4，方案 §5）：
 * capture() 阶梯 = 无障碍免授权截图（API 30+ [AndroidAccessibilityBridge.captureScreen]）
 * → 失败/低版本再委托投影实现。授权 intent、ready、release 全部委托投影，
 * 投影「拍完即关」策略不变——不授投影时 ui_control/screen_ocr 的 OCR 兜底
 * 在 API 30+ 设备上开箱可用。
 */
@Singleton
class PreferA11yScreenshotProvider @Inject constructor(
    private val a11y: AndroidAccessibilityBridge,
    private val projection: AndroidScreenshotProvider,
) : ScreenshotProvider {

    override val ready: StateFlow<Boolean> = projection.ready

    override fun createPermissionIntent(): Intent = projection.createPermissionIntent()

    override fun onPermissionResult(resultCode: Int, data: Intent): Boolean =
        projection.onPermissionResult(resultCode, data)

    override suspend fun capture(): Result<ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val viaA11y = a11y.captureScreen()
            if (viaA11y.isSuccess) return viaA11y
        }
        return projection.capture()
    }

    override fun release() = projection.release()
}
