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

package com.verlintas.baic2.device.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.StateFlow

/** One accessibility node's text with its on-screen bounds. */
data class TextNode(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * UI automation through an AccessibilityService. All gestures run on the
 * main thread; a missing service produces an actionable failure.
 */
interface AccessibilityBridge {
    val connected: StateFlow<Boolean>

    suspend fun tap(x: Int, y: Int): Result<Unit>

    /** Press and hold a point (e.g. context menus, drag handles). */
    suspend fun longPress(x: Int, y: Int): Result<Unit>

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<Unit>

    suspend fun typeText(text: String): Result<Unit>

    /** key: back | home | recents | notifications | enter */
    suspend fun pressKey(key: String): Result<Unit>

    /** Interactive elements whose text/description contains [query]. */
    fun findText(query: String): List<TextNode>

    fun screenText(maxNodes: Int = 200): String

    /** Package name of the app currently in the foreground, when known. */
    fun foregroundPackage(): String?

    /** Active window title, when the service can read one. */
    fun windowTitle(): String?

    /** Display size in pixels. */
    fun screenSize(): Pair<Int, Int>?

    /** True when a text input currently owns the input focus (or one exists). */
    fun editableFocused(): Boolean

    /**
     * Captures the current display through the accessibility screenshot API
     * (API 30+, requires `canTakeScreenshot` in the service config). No
     * runtime dialog and no MediaProjection involved; PNG bytes on success.
     */
    suspend fun captureScreen(): Result<ByteArray>

    /**
     * Bitmap variant of [captureScreen]（2026-10-27 回复慢归因优化）：需要像素
     * 的消费方（找图帧源等）直接拿位图，省掉「PNG 编码 → 解码」往返（真机
     * 每帧 0.2~0.3s）。默认实现由 [captureScreen] 的字节流解码（老实现零改动
     * 也能编译）；设备端实现覆写为直取位图，不做往返。
     *
     * 成功回调的 Bitmap 归调用方所有（大图，用毕即弃）。
     */
    suspend fun captureScreenBitmap(): Result<Bitmap> =
        captureScreen().mapCatching { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("screen_bitmap_decode_failed")
        }
}
