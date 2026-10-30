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

import com.verlintas.rpa.model.RpaBounds

/** 结果框在窗口坐标系中的浮点矩形（避免缩放取整造成 1px 抖动）。 */
data class RpaQuickFindBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    override fun toString(): String =
        "RpaQuickFindBox($left,$top,$right,$bottom)"
}

/**
 * 抓帧像素空间的椭圆参数（圆形选区经 x/y 独立缩放后的形状）。
 * 缩放比一致时 [radiusX] == [radiusY]，即正圆；圆心/半径单位均为帧像素。
 */
data class RpaQuickFindEllipse(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
)

/**
 * 「以图找图」快捷浮层的纯几何：
 *
 * - 抓帧尺寸与窗口尺寸经常不一致（状态栏/导航栏、刘海、分屏），所以 x/y
 *   **各自独立缩放**，与方案 §6.5 的口径一致；
 * - 比例坐标直接按帧尺寸归一化，供 `RpaVisionLocator(kind = RATIO)` 使用；
 * - 所有退化输入都返回 null / 0，调用方无需再判空。
 */
object RpaQuickFindGeometry {

    /** 抓帧像素矩形 → 窗口坐标矩形；任一尺寸非法或矩形为空返回 null。 */
    fun viewBox(
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        match: RpaBounds,
    ): RpaQuickFindBox? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        if (viewWidth <= 0 || viewHeight <= 0) return null
        if (match.isEmpty) return null
        val scaleX = viewWidth.toFloat() / frameWidth
        val scaleY = viewHeight.toFloat() / frameHeight
        return RpaQuickFindBox(
            left = match.left * scaleX,
            top = match.top * scaleY,
            right = match.right * scaleX,
            bottom = match.bottom * scaleY,
        )
    }

    /** 单轴归一化（夹取 0..1）；总量非法时返回 0，绝不抛异常。 */
    fun ratio(value: Int, total: Int): Float =
        if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)

    /**
     * 窗口坐标选区 → 抓帧像素矩形（[viewBox] 的逆向），x/y 各自独立缩放；
     * 结果钳位到帧边界内且不为空，任一尺寸非法或选区为空返回 null。
     */
    fun frameRect(
        selection: RpaQuickFindBox,
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): RpaBounds? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        if (viewWidth <= 0 || viewHeight <= 0) return null
        if (selection.isEmpty) return null
        val scaleX = frameWidth.toFloat() / viewWidth
        val scaleY = frameHeight.toFloat() / viewHeight
        val left = (selection.left * scaleX).toInt().coerceIn(0, frameWidth - 1)
        val top = (selection.top * scaleY).toInt().coerceIn(0, frameHeight - 1)
        val right = (selection.right * scaleX).toInt().coerceIn(left + 1, frameWidth)
        val bottom = (selection.bottom * scaleY).toInt().coerceIn(top + 1, frameHeight)
        return RpaBounds(left = left, top = top, right = right, bottom = bottom)
    }

    /**
     * 圆形选区（圆心 + 半径，窗口坐标）→ 外接方形选区（同一窗口坐标）。
     * 半径非法（NaN / 负 / 无穷）返回空框，调用方按 `isEmpty` 判废。
     */
    fun circleBoundingSquare(centerX: Float, centerY: Float, radius: Float): RpaQuickFindBox {
        if (!radius.isFinite() || radius <= 0f) {
            return RpaQuickFindBox(0f, 0f, 0f, 0f)
        }
        return RpaQuickFindBox(
            left = centerX - radius,
            top = centerY - radius,
            right = centerX + radius,
            bottom = centerY + radius,
        )
    }

    /**
     * 圆形选区（窗口坐标）→ 帧像素椭圆（x/y 独立缩放后正圆变椭圆，
     * 圆形掩膜裁剪按此形状取圆内像素）。任一尺寸非法返回 null。
     */
    fun frameEllipse(
        centerX: Float,
        centerY: Float,
        radius: Float,
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): RpaQuickFindEllipse? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        if (viewWidth <= 0 || viewHeight <= 0) return null
        if (!radius.isFinite() || radius <= 0f) return null
        val scaleX = frameWidth.toFloat() / viewWidth
        val scaleY = frameHeight.toFloat() / viewHeight
        return RpaQuickFindEllipse(
            centerX = centerX * scaleX,
            centerY = centerY * scaleY,
            radiusX = radius * scaleX,
            radiusY = radius * scaleY,
        )
    }
}
