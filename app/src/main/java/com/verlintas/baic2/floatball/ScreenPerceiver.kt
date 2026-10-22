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

import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.floatball.auto.ScreenCapture
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 三级感知阶梯：a11y 文本 → a11y 截图（API 30+，无对话框）→ 像素帧
 * （[ScreenCapture] 免 a11y 阶梯：常驻投影 acquire 主源 + Shizuku
 * screencap 兜底，docs/2026-10-22 方案模块 C）。文本路径命中时零截屏、
 * 零闪烁、毫秒级；像素路径全程无 a11y 痕迹（反侦察感知面）。
 */
@Singleton
class ScreenPerceiver @Inject constructor(
    private val accessibility: AccessibilityBridge,
    private val screenCapture: ScreenCapture,
) {

    sealed interface Perception {
        /** 无障碍树文本足够丰富，直接作为问题内容。 */
        data class Text(val text: String) : Perception

        /** 像素帧（已过空白质检），交给 OCR。 */
        data class Image(val bytes: ByteArray) : Perception

        /** 投影未授权：需要用户确认（服务层走 FloatCaptureActivity）。 */
        data object NeedsProjection : Perception

        /** 全阶梯失败，[reason] 供文案映射。 */
        data class Failed(val reason: String) : Perception
    }

    val accessibilityConnected: Boolean get() = accessibility.connected.value

    /** 无障碍连接状态流（设置页徽章）。 */
    val a11yConnected get() = accessibility.connected

    /** 前台包名（联系人识别 L1 的信号之一，读失败按未知处理）。 */
    fun foregroundPackage(): String? =
        runCatching { accessibility.foregroundPackage() }.getOrNull()

    /** 活动窗口标题（IM 聊天页即对方/群名，联系人识别 L1 主信号）。 */
    fun windowTitle(): String? =
        runCatching { accessibility.windowTitle() }.getOrNull()

    /** 输入框是否聚焦（IME 弹起判定；多屏滚动前置条件之一）。 */
    fun editableFocused(): Boolean =
        runCatching { accessibility.editableFocused() }.getOrDefault(false)

    /** 发送返回键（收拢 IME 等）；无障碍不可用返回 false。 */
    suspend fun pressBack(): Boolean =
        runCatching { accessibility.pressKey("back").isSuccess }.getOrDefault(false)

    /** 触点预热：只读无障碍文本（无副作用），未确认长按即丢弃。 */
    suspend fun readA11yText(): String? {
        if (!accessibility.connected.value) return null
        return withContext(Dispatchers.Default) { accessibility.screenText(MAX_NODES) }
            .takeIf { it.isNotBlank() }
    }

    /**
     * 单次参谋的感知入口。[preheatedText] 为预热结果，命中即省去二次遍历。
     * [awaitProjection] 供授权后重跑：MPService 正在异步启动，跳过存活快检
     * 等待就绪，避免把启动窗口误判为 NeedsProjection。
     */
    suspend fun quickPerceive(
        preheatedText: String? = null,
        awaitProjection: Boolean = false,
    ): Perception {
        if (accessibility.connected.value) {
            val text = preheatedText ?: readA11yText()
            if (!text.isNullOrBlank() && text.length >= MIN_TEXT_CHARS) {
                return Perception.Text(text)
            }
            // 文本稀薄：当前帧大概率图片为主，走像素路径。
            a11yScreenshotWithRetry()?.let { return it }
        }
        return projectionPerception(awaitProjection)
    }

    /** 阶梯②：无障碍截图 API（同进程、无对话框、无投影指示灯）。 */
    private suspend fun a11yScreenshotWithRetry(): Perception? {
        repeat(2) { attempt ->
            val result = accessibility.captureScreen()
            val bytes = result.getOrNull()
            if (bytes != null) {
                val gray = withContext(Dispatchers.Default) { FrameQuality.grayFrame(bytes) }
                if (gray != null && !FrameQuality.isBlank(gray)) {
                    return Perception.Image(bytes)
                }
                android.util.Log.w(TAG, "a11y screenshot blank (attempt=$attempt)")
                // 疑似黑/白过渡帧：等一拍重采一次。
                if (attempt == 0) delay(RETRY_DELAY_MS)
            } else {
                android.util.Log.w(
                    TAG,
                    "a11y screenshot failed: ${result.exceptionOrNull()?.message}",
                )
                return null // API 不可用（<API 30 或被拒），直接降级
            }
        }
        return null
    }

    /**
     * 阶梯③：像素帧（ScreenCapture 免 a11y 阶梯——常驻投影 acquire 主源 +
     * screencap 兜底 + 空白质检）。投影会话未建立且 screencap 也不可用时
     * 返回 [Perception.NeedsProjection]（服务层走授权编排后重跑）。
     */
    suspend fun projectionPerception(awaitReady: Boolean = false): Perception {
        if (awaitReady) {
            // 授权中转刚返回：MPService 异步启动窗口内 isEnable 尚为 false，
            // 轮询等就绪，避免误回 NeedsProjection 触发二次授权。
            var waited = 0L
            while (!screenCapture.projectionAlive && waited < AWAIT_READY_TIMEOUT_MS) {
                delay(READY_POLL_MS)
                waited += READY_POLL_MS
            }
        }
        // 投影死时 captureBytes 内部自动探 screencap 兜底；两者皆不可用才
        // NeedsProjection（服务层走授权编排后重跑）。
        val captured = screenCapture.captureBytes(qualityCheck = true)
        val bytes = captured.getOrNull()
        if (bytes != null) return Perception.Image(bytes)
        val reason = captured.exceptionOrNull()?.message
            ?: ScreenCapture.REASON_CAPTURE_UNAVAILABLE
        return if (reason == ScreenCapture.REASON_CAPTURE_UNAVAILABLE) {
            Perception.NeedsProjection
        } else {
            Perception.Failed(REASON_BLANK)
        }
    }

    companion object {
        private const val TAG = "ScreenPerceiver"
        const val MIN_TEXT_CHARS = 30
        const val MAX_NODES = 400
        const val RETRY_DELAY_MS = 300L
        const val REASON_BLANK = "blank_frame"
        private const val AWAIT_READY_TIMEOUT_MS = 3_000L
        private const val READY_POLL_MS = 200L
    }
}
