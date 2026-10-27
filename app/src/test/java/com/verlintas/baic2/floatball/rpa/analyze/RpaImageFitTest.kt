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

package com.verlintas.baic2.floatball.rpa.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 冻结帧 fitCenter 与「画布 ↔ 截图」双向往算。 */
class RpaImageFitTest {

    @Test
    fun `tall image is letterboxed horizontally`() {
        val fit = RpaImageFit.fit(
            imageWidth = 100,
            imageHeight = 200,
            viewWidth = 1000,
            viewHeight = 1000,
        )

        assertTrue(fit.valid)
        assertEquals(5f, fit.scale, 0.0001f)
        assertEquals(250f, fit.left, 0.0001f)
        assertEquals(0f, fit.top, 0.0001f)
        assertEquals(750f, fit.right, 0.0001f)
        assertEquals(1000f, fit.bottom, 0.0001f)
    }

    @Test
    fun `wide image is letterboxed vertically`() {
        val fit = RpaImageFit.fit(
            imageWidth = 200,
            imageHeight = 100,
            viewWidth = 1000,
            viewHeight = 1000,
        )

        assertEquals(5f, fit.scale, 0.0001f)
        assertEquals(0f, fit.left, 0.0001f)
        assertEquals(250f, fit.top, 0.0001f)
    }

    @Test
    fun `image and view points round trip`() {
        val fit = RpaImageFit.fit(100, 200, 1000, 1000)
        val viewPoint = fit.imageToView(50f, 100f)
        assertEquals(500f, viewPoint.x, 0.0001f)
        assertEquals(500f, viewPoint.y, 0.0001f)

        val imagePoint = fit.viewToImage(viewPoint.x, viewPoint.y)
        assertEquals(50f, imagePoint.x, 0.0001f)
        assertEquals(100f, imagePoint.y, 0.0001f)
    }

    @Test
    fun `letterbox points map outside image bounds`() {
        val fit = RpaImageFit.fit(100, 200, 1000, 1000)
        val leftMarginPoint = fit.viewToImage(100f, 500f)
        assertEquals(-30f, leftMarginPoint.x, 0.0001f)
    }

    @Test
    fun `degenerate geometry returns empty fit`() {
        assertEquals(RpaImageFit.Empty, RpaImageFit.fit(0, 200, 1000, 1000))
        assertEquals(RpaImageFit.Empty, RpaImageFit.fit(100, 0, 1000, 1000))
        assertEquals(RpaImageFit.Empty, RpaImageFit.fit(100, 200, 0, 1000))
        assertEquals(RpaImageFit.Empty, RpaImageFit.fit(100, 200, 1000, 0))
        assertFalse(RpaImageFit.Empty.valid)
    }

    @Test
    fun `same aspect ratio fills the view exactly`() {
        val fit = RpaImageFit.fit(540, 960, 1080, 1920)
        assertTrue(fit.valid)
        assertEquals(2f, fit.scale, 0.0001f)
        assertEquals(0f, fit.left, 0.0001f)
        assertEquals(0f, fit.top, 0.0001f)
        assertEquals(1080f, fit.right, 0.0001f)
        assertEquals(1920f, fit.bottom, 0.0001f)
    }

    @Test
    fun `upscaling small projection is allowed`() {
        val fit = RpaImageFit.fit(300, 600, 1000, 1000)
        // 高比宽更受限：1000/600，而不是 1000/300。
        assertEquals(1000f / 600f, fit.scale, 0.0001f)
        assertEquals(250f, fit.left, 0.0001f)
        assertEquals(0f, fit.top, 0.0001f)
        assertTrue(fit.valid)
    }
}
