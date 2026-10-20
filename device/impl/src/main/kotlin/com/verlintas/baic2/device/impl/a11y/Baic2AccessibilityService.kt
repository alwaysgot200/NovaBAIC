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

package com.verlintas.baic2.device.impl.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.ven.assists.service.AssistsService
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * 本应用唯一无障碍服务。R1 集成起继承 assists 的 [AssistsService]（D2 方案甲，
 * 一行级 [upstream-touch]）：super 调用自动完成 AssistsCore 全局实例注册与
 * listeners 分发；Manifest 组件名、服务配置 xml、[bridge] 接线全部不变，
 * 系统无障碍设置里仍只有一个开关。
 */
@AndroidEntryPoint
class Baic2AccessibilityService : AssistsService() {

    @Inject
    lateinit var bridge: AndroidAccessibilityBridge

    override fun onServiceConnected() {
        super.onServiceConnected()
        bridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        super.onAccessibilityEvent(event)
    }

    override fun onDestroy() {
        bridge.detach(this)
        super.onDestroy()
    }

    /**
     * Captures the current display via [AccessibilityService.takeScreenshot]
     * (API 30+, requires `canTakeScreenshot` in the service config). No
     * runtime dialog and no MediaProjection involved.
     */
    fun captureScreenCompat(onResult: (Result<ByteArray>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(Result.failure(IllegalStateException("screenshot_requires_api_30")))
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bytes = runCatching {
                        val hardware = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace,
                        )
                        val soft = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        hardware?.recycle()
                        screenshot.hardwareBuffer.close()
                        soft?.let { bmp ->
                            val out = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            bmp.recycle()
                            out.toByteArray()
                        }
                    }.getOrNull()
                    if (bytes != null) {
                        onResult(Result.success(bytes))
                    } else {
                        onResult(Result.failure(IllegalStateException("screenshot_decode_failed")))
                    }
                }

                override fun onFailure(errorCode: Int) {
                    onResult(Result.failure(IllegalStateException("screenshot_failed_$errorCode")))
                }
            },
        )
    }
}
