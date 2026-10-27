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
}
