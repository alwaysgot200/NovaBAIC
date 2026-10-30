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

    @Test
    fun `frameRect inverts viewBox with independent axes`() {
        // viewBox 的同参数回程：view 500x1000 ← frame 1000x2000，比例一致。
        val rect = RpaQuickFindGeometry.frameRect(
            selection = RpaQuickFindBox(left = 50f, top = 100f, right = 150f, bottom = 300f),
            frameWidth = 1000,
            frameHeight = 2000,
            viewWidth = 500,
            viewHeight = 1000,
        )

        requireNotNull(rect)
        assertEquals(100, rect.left)
        assertEquals(200, rect.top)
        assertEquals(300, rect.right)
        assertEquals(600, rect.bottom)
    }

    @Test
    fun `frameRect scales x and y independently`() {
        val rect = RpaQuickFindGeometry.frameRect(
            selection = RpaQuickFindBox(left = 0f, top = 0f, right = 540f, bottom = 1080f),
            frameWidth = 1080,
            frameHeight = 2400,
            viewWidth = 1080,
            viewHeight = 2160,
        )

        requireNotNull(rect)
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(540, rect.right)
        assertEquals(1200, rect.bottom)
    }

    @Test
    fun `frameRect clamps selection to frame bounds`() {
        val rect = RpaQuickFindGeometry.frameRect(
            selection = RpaQuickFindBox(left = -80f, top = -40f, right = 1200f, bottom = 2600f),
            frameWidth = 1080,
            frameHeight = 2400,
            viewWidth = 1080,
            viewHeight = 2400,
        )

        requireNotNull(rect)
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(1080, rect.right)
        assertEquals(2400, rect.bottom)
    }

    @Test
    fun `frameRect rejects invalid sizes and empty selection`() {
        val box = RpaQuickFindBox(left = 10f, top = 10f, right = 20f, bottom = 20f)
        assertNull(RpaQuickFindGeometry.frameRect(box, 0, 100, 100, 100))
        assertNull(RpaQuickFindGeometry.frameRect(box, 100, 0, 100, 100))
        assertNull(RpaQuickFindGeometry.frameRect(box, 100, 100, 0, 100))
        assertNull(RpaQuickFindGeometry.frameRect(box, 100, 100, 100, 0))
        assertNull(
            RpaQuickFindGeometry.frameRect(
                selection = RpaQuickFindBox(5f, 5f, 5f, 5f),
                frameWidth = 100,
                frameHeight = 100,
                viewWidth = 100,
                viewHeight = 100,
            ),
        )
    }

    @Test
    fun `circle bounding square is symmetric around center`() {
        val box = RpaQuickFindGeometry.circleBoundingSquare(
            centerX = 100f,
            centerY = 200f,
            radius = 30f,
        )

        assertEquals(70f, box.left, 0.0001f)
        assertEquals(170f, box.top, 0.0001f)
        assertEquals(130f, box.right, 0.0001f)
        assertEquals(230f, box.bottom, 0.0001f)
        assertEquals(100f, box.centerX, 0.0001f)
        assertEquals(200f, box.centerY, 0.0001f)
    }

    @Test
    fun `circle bounding square rejects degenerate radius`() {
        assertTrue(RpaQuickFindGeometry.circleBoundingSquare(10f, 10f, 0f).isEmpty)
        assertTrue(RpaQuickFindGeometry.circleBoundingSquare(10f, 10f, -5f).isEmpty)
        assertTrue(RpaQuickFindGeometry.circleBoundingSquare(10f, 10f, Float.NaN).isEmpty)
    }

    @Test
    fun `frameEllipse scales radius per axis`() {
        val ellipse = RpaQuickFindGeometry.frameEllipse(
            centerX = 50f,
            centerY = 50f,
            radius = 25f,
            frameWidth = 1000,
            frameHeight = 2000,
            viewWidth = 500,
            viewHeight = 1000,
        )

        requireNotNull(ellipse)
        assertEquals(100f, ellipse.centerX, 0.0001f)
        assertEquals(100f, ellipse.centerY, 0.0001f)
        assertEquals(50f, ellipse.radiusX, 0.0001f)
        assertEquals(50f, ellipse.radiusY, 0.0001f)
    }

    @Test
    fun `frameEllipse rejects invalid input`() {
        assertNull(
            RpaQuickFindGeometry.frameEllipse(10f, 10f, 5f, frameWidth = 0, frameHeight = 10, viewWidth = 10, viewHeight = 10),
        )
        assertNull(
            RpaQuickFindGeometry.frameEllipse(10f, 10f, 0f, frameWidth = 10, frameHeight = 10, viewWidth = 10, viewHeight = 10),
        )
        assertNull(
            RpaQuickFindGeometry.frameEllipse(10f, 10f, Float.NaN, frameWidth = 10, frameHeight = 10, viewWidth = 10, viewHeight = 10),
        )
    }
}
