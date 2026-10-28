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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 悬浮球靠边收起的纯几何判定（动画/窗口逻辑在 FloatBallService）。 */
class FloatEdgeTuckTest {

    private val screenWidth = 1080
    private val ballSize = 140
    private val proximity = 63 // 24dp @ 2.625x

    @Test
    fun `ball in screen middle never tucks`() {
        assertNull(FloatEdgeTuck.sideFor(400, screenWidth, ballSize, proximity))
        assertNull(FloatEdgeTuck.sideFor(540, screenWidth, ballSize, proximity))
    }

    @Test
    fun `left and right edges are detected within proximity`() {
        assertEquals(
            FloatEdgeTuck.Side.LEFT,
            FloatEdgeTuck.sideFor(0, screenWidth, ballSize, proximity),
        )
        assertEquals(
            FloatEdgeTuck.Side.LEFT,
            FloatEdgeTuck.sideFor(proximity, screenWidth, ballSize, proximity),
        )
        assertEquals(
            FloatEdgeTuck.Side.RIGHT,
            FloatEdgeTuck.sideFor(
                screenWidth - ballSize - proximity,
                screenWidth,
                ballSize,
                proximity,
            ),
        )
        assertEquals(
            FloatEdgeTuck.Side.RIGHT,
            FloatEdgeTuck.sideFor(screenWidth - ballSize, screenWidth, ballSize, proximity),
        )
    }

    @Test
    fun `just outside proximity does not tuck`() {
        assertNull(FloatEdgeTuck.sideFor(proximity + 1, screenWidth, ballSize, proximity))
        assertNull(
            FloatEdgeTuck.sideFor(
                screenWidth - ballSize - proximity - 1,
                screenWidth,
                ballSize,
                proximity,
            ),
        )
    }

    @Test
    fun `both edges near picks the closer side`() {
        // 窄屏 + 大球：左右都进入阈值，应选离屏缘更近的一侧（相等取左）。
        assertEquals(
            FloatEdgeTuck.Side.LEFT,
            FloatEdgeTuck.sideFor(10, 120, 100, 24),
        )
        assertEquals(
            FloatEdgeTuck.Side.RIGHT,
            FloatEdgeTuck.sideFor(15, 120, 100, 24),
        )
    }

    @Test
    fun `tucked x leaves exactly the sliver visible on left`() {
        val sliver = 30
        val x = FloatEdgeTuck.tuckedX(FloatEdgeTuck.Side.LEFT, screenWidth, ballSize, sliver)
        assertEquals(sliver - ballSize, x)
        assertEquals(sliver, x + ballSize)
    }

    @Test
    fun `tucked x leaves exactly the sliver visible on right`() {
        val sliver = 30
        val x = FloatEdgeTuck.tuckedX(FloatEdgeTuck.Side.RIGHT, screenWidth, ballSize, sliver)
        assertEquals(screenWidth - sliver, x)
        assertEquals(sliver, screenWidth - x)
    }

    @Test
    fun `sliver wider than ball leaves the whole ball on screen`() {
        assertEquals(
            0,
            FloatEdgeTuck.tuckedX(FloatEdgeTuck.Side.LEFT, screenWidth, ballSize, ballSize + 10),
        )
        assertEquals(
            screenWidth - ballSize,
            FloatEdgeTuck.tuckedX(
                FloatEdgeTuck.Side.RIGHT,
                screenWidth,
                ballSize,
                ballSize + 10,
            ),
        )
    }

    @Test
    fun `edge x keeps the ball fully visible on the same side`() {
        assertEquals(
            0,
            FloatEdgeTuck.edgeX(FloatEdgeTuck.Side.LEFT, screenWidth, ballSize),
        )
        assertEquals(
            screenWidth - ballSize,
            FloatEdgeTuck.edgeX(FloatEdgeTuck.Side.RIGHT, screenWidth, ballSize),
        )
    }

    @Test
    fun `portrait tucked ball maps onto the same landscape edge`() {
        // 竖屏 720 宽收起在右缘：逻辑 x=616 距右缘 16dp，旋转后应落到 1280-88。
        val portraitWidth = 720
        val landscapeWidth = 1280
        val ball = 88
        val side = FloatEdgeTuck.sideFor(
            logicalX = portraitWidth - ball - 16,
            screenWidth = portraitWidth,
            ballSize = ball,
            proximity = 16,
        )
        assertEquals(FloatEdgeTuck.Side.RIGHT, side)
        assertEquals(
            1192,
            FloatEdgeTuck.edgeX(requireNotNull(side), landscapeWidth, ball),
        )
        assertEquals(
            0,
            FloatEdgeTuck.edgeX(FloatEdgeTuck.Side.LEFT, landscapeWidth, ball),
        )
    }

    @Test
    fun `edge x never leaves the screen narrower than the ball`() {
        assertEquals(
            0,
            FloatEdgeTuck.edgeX(FloatEdgeTuck.Side.RIGHT, 40, ballSize),
        )
    }

    @Test
    fun `degenerate geometry never identifies a side`() {
        assertNull(FloatEdgeTuck.sideFor(0, 0, ballSize, proximity))
        assertNull(FloatEdgeTuck.sideFor(0, -1, ballSize, proximity))
        assertNull(FloatEdgeTuck.sideFor(0, screenWidth, 0, proximity))
        assertNull(FloatEdgeTuck.sideFor(0, screenWidth, ballSize, -1))
    }

    @Test
    fun `zero proximity only matches a flush edge`() {
        assertEquals(FloatEdgeTuck.Side.LEFT, FloatEdgeTuck.sideFor(0, screenWidth, ballSize, 0))
        assertNull(FloatEdgeTuck.sideFor(1, screenWidth, ballSize, 0))
    }
}
