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

import org.junit.Assert.assertEquals
import org.junit.Test

/** 圆形掩膜纯逻辑：圆内不透明、圆外透明（方案 §3.3 决策 B）。 */
class RpaQuickFindPickerTest {

    private fun opaqueGrid(width: Int, height: Int): IntArray =
        IntArray(width * height) { 0xFF112233.toInt() }

    @Test
    fun `pixels inside circle keep color and outside become transparent`() {
        val width = 5
        val height = 5
        val pixels = opaqueGrid(width, height)

        RpaQuickFindPicker.applyCircleMask(
            pixels = pixels,
            width = width,
            height = height,
            centerX = 2.5f,
            centerY = 2.5f,
            radiusX = 1.5f,
            radiusY = 1.5f,
        )

        // 中心像素（2,2）圆内：不透明。
        assertEquals(0xFF112233.toInt(), pixels[2 * width + 2])
        // 四角圆外：透明。
        assertEquals(0, pixels[0])
        assertEquals(0, pixels[width - 1])
        assertEquals(0, pixels[(height - 1) * width])
        assertEquals(0, pixels[height * width - 1])
    }

    @Test
    fun `boundary pixels stay inside the mask`() {
        val width = 5
        val height = 1
        val pixels = opaqueGrid(width, height)

        // 圆心 (2.5, 0.5)、半径 2.5：像素中心 x=0.5 / 4.5 恰在边界上（dx²=1）。
        RpaQuickFindPicker.applyCircleMask(
            pixels = pixels,
            width = width,
            height = height,
            centerX = 2.5f,
            centerY = 0.5f,
            radiusX = 2.5f,
            radiusY = 2.5f,
        )

        for (x in 0 until width) {
            assertEquals("x=$x", 0xFF112233.toInt(), pixels[x])
        }
    }

    @Test
    fun `ellipse radii scale the mask independently`() {
        val width = 7
        val height = 3
        val pixels = opaqueGrid(width, height)

        // 扁椭圆：x 半径 3.5、y 半径 0.5 → 只有中行不透明。
        RpaQuickFindPicker.applyCircleMask(
            pixels = pixels,
            width = width,
            height = height,
            centerX = 3.5f,
            centerY = 1.5f,
            radiusX = 3.5f,
            radiusY = 0.5f,
        )

        for (x in 0 until width) {
            assertEquals("top x=$x", 0, pixels[x])
            assertEquals("middle x=$x", 0xFF112233.toInt(), pixels[width + x])
            assertEquals("bottom x=$x", 0, pixels[2 * width + x])
        }
    }

    @Test
    fun `degenerate radii make every pixel transparent`() {
        val width = 3
        val height = 3
        for (radius in listOf(0f, -1f, Float.NaN)) {
            val pixels = opaqueGrid(width, height)
            RpaQuickFindPicker.applyCircleMask(
                pixels = pixels,
                width = width,
                height = height,
                centerX = 1.5f,
                centerY = 1.5f,
                radiusX = radius,
                radiusY = 1f,
            )
            for (value in pixels) {
                assertEquals(0, value)
            }
        }
    }
}
