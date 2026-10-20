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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageScalesTest {

    @Test
    fun `720p screen yields 0_9 to 1_1 around unity`() {
        val scales = ImageScales.forScreen(720)
        assertEquals(3, scales.size)
        assertEquals(0.9, scales[0], 1e-9)
        assertEquals(1.0, scales[1], 1e-9)
        assertEquals(1.1, scales[2], 1e-9)
    }

    @Test
    fun `1080p screen scales by width ratio`() {
        val scales = ImageScales.forScreen(1080)
        assertEquals(1.5, scales[1], 1e-9)
    }

    @Test
    fun `degenerate screen width falls back to unity`() {
        assertEquals(listOf(1.0), ImageScales.forScreen(0))
        assertEquals(listOf(1.0), ImageScales.forScreen(-1))
    }

    @Test
    fun `ratio collapse keeps distinct scales only`() {
        // 极小屏宽比率下 0.9r 与 1.0r 舍入相近仍应保持 distinct（double 精度）
        val scales = ImageScales.forScreen(72)
        assertEquals(scales.size, scales.toSet().size)
        assertTrue(scales.all { it > 0.0 })
    }
}
