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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa.quickfind

import android.graphics.Bitmap
import com.verlintas.rpa.model.RpaBounds

/**
 * 屏幕拾取的裁剪与掩膜（方案 §4.2）：
 *
 * - **方形**：[crop] 直接裁出帧像素矩形，与相册选图产出的模板同口径；
 * - **圆形**：[cropCircle] 裁外接方形并把圆外像素置全透明（alpha=0），
 *   PNG 保留 alpha 通道——`CvTemplateOps.templateFromBitmap` 检测到透明像素
 *   即提取 alpha 为匹配掩膜，圆形目标的四角背景不参与打分；
 * - 掩膜核心 [applyCircleMask] 是纯函数（Int 数组），JVM 单测直接守护
 *   「圆内不透明 / 圆外透明」；位图 IO 只是薄封装。
 *
 * 坐标均为帧像素（由 [RpaQuickFindGeometry.frameRect] / [RpaQuickFindGeometry.frameEllipse]
 * 从窗口选区换算而来）；裁剪矩形允许被帧边界钳位，掩膜按椭圆原参数计算，
 * 钳位只影响「画到哪里」，不影响「哪些像素透明」。
 */
object RpaQuickFindPicker {

    /** 裁出帧像素矩形（方形选区）；矩形非法或越界时返回 null。 */
    fun crop(frame: Bitmap, rect: RpaBounds): Bitmap? {
        val area = clamped(rect, frame.width, frame.height) ?: return null
        return runCatching {
            Bitmap.createBitmap(frame, area.left, area.top, area.width, area.height)
        }.getOrNull()
    }

    /**
     * 圆形选区：裁 [rect]（椭圆外接方形）并把椭圆外像素置透明。
     * [ellipse] 为帧像素坐标（圆心相对整帧），内部换算为裁剪局部坐标。
     */
    fun cropCircle(
        frame: Bitmap,
        rect: RpaBounds,
        ellipse: RpaQuickFindEllipse,
    ): Bitmap? {
        if (!ellipse.radiusX.isFinite() || !ellipse.radiusY.isFinite()) return null
        if (ellipse.radiusX <= 0f || ellipse.radiusY <= 0f) return null
        val area = clamped(rect, frame.width, frame.height) ?: return null
        val cropped = runCatching {
            Bitmap.createBitmap(frame, area.left, area.top, area.width, area.height)
                .copy(Bitmap.Config.ARGB_8888, true)
        }.getOrNull() ?: return null
        val width = cropped.width
        val height = cropped.height
        val pixels = IntArray(width * height)
        cropped.getPixels(pixels, 0, width, 0, 0, width, height)
        applyCircleMask(
            pixels = pixels,
            width = width,
            height = height,
            centerX = ellipse.centerX - area.left,
            centerY = ellipse.centerY - area.top,
            radiusX = ellipse.radiusX,
            radiusY = ellipse.radiusY,
        )
        cropped.setPixels(pixels, 0, width, 0, 0, width, height)
        return cropped
    }

    /**
     * 椭圆外像素置全透明（行主序 ARGB 数组，原地修改）。
     *
     * 判定用像素中心 `(x + 0.5, y + 0.5)`，边界含在椭圆内；
     * 半径非法（NaN / 负 / 0）视为「椭圆内无像素」→ 全部透明，
     * 与「圆外一律透明」语义一致，调用方在上游已拒非法选区。
     */
    fun applyCircleMask(
        pixels: IntArray,
        width: Int,
        height: Int,
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
    ) {
        val valid = radiusX.isFinite() && radiusY.isFinite() && radiusX > 0f && radiusY > 0f
        for (y in 0 until height) {
            val dy = (y + 0.5f - centerY) / if (valid) radiusY else 1f
            val dy2 = if (valid) dy * dy else Float.MAX_VALUE
            val row = y * width
            for (x in 0 until width) {
                val dx = (x + 0.5f - centerX) / if (valid) radiusX else 1f
                val inside = valid && (dx * dx + dy2) <= 1f
                if (!inside) {
                    // 只清 alpha，RGB 置零避免预乘歧义；解码端只看 alpha 掩膜。
                    pixels[row + x] = 0
                }
            }
        }
    }

    /** 矩形钳位到帧内且不为空；非法帧尺寸或空矩形返回 null。 */
    private fun clamped(rect: RpaBounds, frameWidth: Int, frameHeight: Int): RpaBounds? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        if (rect.isEmpty) return null
        val left = rect.left.coerceIn(0, frameWidth - 1)
        val top = rect.top.coerceIn(0, frameHeight - 1)
        val right = rect.right.coerceIn(left + 1, frameWidth)
        val bottom = rect.bottom.coerceIn(top + 1, frameHeight)
        return RpaBounds(left = left, top = top, right = right, bottom = bottom)
    }
}
