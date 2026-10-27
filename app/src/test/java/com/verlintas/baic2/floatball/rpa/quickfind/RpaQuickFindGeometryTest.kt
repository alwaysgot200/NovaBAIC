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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 抓帧像素矩形 → 覆盖层窗口坐标的缩放口径（方案 §6.5）。 */
class RpaQuickFindGeometryTest {

    @Test
    fun `same aspect ratio scales uniformly`() {
        val box = RpaQuickFindGeometry.viewBox(
            frameWidth = 1000,
            frameHeight = 2000,
            viewWidth = 500,
            viewHeight = 1000,
            match = RpaBounds(left = 100, top = 200, right = 300, bottom = 600),
        )

        requireNotNull(box)
        assertEquals(50f, box.left, 0.0001f)
        assertEquals(100f, box.top, 0.0001f)
        assertEquals(150f, box.right, 0.0001f)
        assertEquals(300f, box.bottom, 0.0001f)
        assertEquals(100f, box.width, 0.0001f)
        assertEquals(200f, box.height, 0.0001f)
        assertEquals(100f, box.centerX, 0.0001f)
        assertEquals(200f, box.centerY, 0.0001f)
    }

    @Test
    fun `x and y scale independently when window excludes system bars`() {
        // 1080x2400 的抓帧映射到 1080x2160 的窗口：y 轴单独缩放到 0.9。
        val box = RpaQuickFindGeometry.viewBox(
            frameWidth = 1080,
            frameHeight = 2400,
            viewWidth = 1080,
            viewHeight = 2160,
            match = RpaBounds(left = 100, top = 100, right = 300, bottom = 500),
        )

        requireNotNull(box)
        assertEquals(100f, box.left, 0.0001f)
        assertEquals(90f, box.top, 0.0001f)
        assertEquals(300f, box.right, 0.0001f)
        assertEquals(450f, box.bottom, 0.0001f)
    }

    @Test
    fun `invalid frame or view size returns null`() {
        val match = RpaBounds(left = 0, top = 0, right = 10, bottom = 10)
        assertNull(RpaQuickFindGeometry.viewBox(0, 100, 100, 100, match))
        assertNull(RpaQuickFindGeometry.viewBox(100, 0, 100, 100, match))
        assertNull(RpaQuickFindGeometry.viewBox(100, 100, 0, 100, match))
        assertNull(RpaQuickFindGeometry.viewBox(100, 100, 100, 0, match))
    }

    @Test
    fun `empty or inverted match returns null`() {
        assertNull(
            RpaQuickFindGeometry.viewBox(
                frameWidth = 100,
                frameHeight = 100,
                viewWidth = 100,
                viewHeight = 100,
                match = RpaBounds(left = 10, top = 10, right = 10, bottom = 40),
            ),
        )
        assertNull(
            RpaQuickFindGeometry.viewBox(
                frameWidth = 100,
                frameHeight = 100,
                viewWidth = 100,
                viewHeight = 100,
                match = RpaBounds(left = 40, top = 10, right = 10, bottom = 40),
            ),
        )
    }

    @Test
    fun `box is empty when collapsed`() {
        val box = RpaQuickFindBox(left = 5f, top = 5f, right = 5f, bottom = 5f)

        assertTrue(box.isEmpty)
        assertEquals(0f, box.width, 0.0001f)
        assertEquals(0f, box.height, 0.0001f)
    }

    @Test
    fun `ratio normalizes and clamps to unit range`() {
        assertEquals(0.25f, RpaQuickFindGeometry.ratio(250, 1000), 0.0001f)
        assertEquals(0f, RpaQuickFindGeometry.ratio(-20, 1000), 0.0001f)
        assertEquals(1f, RpaQuickFindGeometry.ratio(1200, 1000), 0.0001f)
        assertEquals(0f, RpaQuickFindGeometry.ratio(250, 0), 0.0001f)
    }
}
