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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import com.verlintas.baic2.device.api.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnBubbleLocatorTest {

    private val gridWidth = 100
    private val gridHeight = 100
    private val step = 10
    private val frameWidth = gridWidth * step
    private val frameHeight = gridHeight * step

    private fun grid(vararg blocks: IntArray): BooleanArray {
        val green = BooleanArray(gridWidth * gridHeight)
        for (block in blocks) {
            val (left, top, right, bottom) = block
            for (y in top until bottom) {
                for (x in left until right) green[y * gridWidth + x] = true
            }
        }
        return green
    }

    private fun boxAt(green: BooleanArray): List<BubbleBox> =
        OwnBubbleLocator.boxesFromGrid(green, gridWidth, gridHeight, step, frameWidth, frameHeight)

    @Test
    fun greenBubble_producesBoxAndOwnsLinesInside() {
        val boxes = boxAt(grid(intArrayOf(30, 20, 80, 26)))
        assertEquals(1, boxes.size)
        assertEquals(300, boxes[0].left)
        assertEquals(200, boxes[0].top)
        assertEquals(800, boxes[0].right)
        assertEquals(260, boxes[0].bottom)

        val inside = OcrLine("这条是我发出去的", 320, 210, 760, 250)
        val outside = OcrLine("这条是对方发来的", 60, 210, 280, 250)
        assertTrue(OwnBubbleLocator.isOwn(boxes, inside))
        assertFalse(OwnBubbleLocator.isOwn(boxes, outside))
    }

    @Test
    fun sendButtonSizedGreen_isIgnored() {
        // 微信发送键 ≈90px 宽，小于 0.15W(=150px)：不能把整个聊天正文误判我方。
        val boxes = boxAt(grid(intArrayOf(88, 88, 97, 96)))
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun bottomImeGreenKey_isIgnored() {
        // 键盘底部大片绿色功能键：起点 ≥ 0.90H，不算正文气泡。
        val boxes = boxAt(grid(intArrayOf(20, 92, 80, 99)))
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun textHoleInsideBubble_stillOneConnectedBox() {
        // 气泡内黑色文字把绿色打成环；外接盒仍覆盖整个气泡（行中心可命中）。
        val green = grid(intArrayOf(20, 10, 60, 16))
        for (y in 12 until 14) {
            for (x in 24 until 56) green[y * gridWidth + x] = false
        }
        val boxes = boxAt(green)
        assertEquals(1, boxes.size)
        assertTrue(OwnBubbleLocator.isOwn(boxes, OcrLine("气泡中间的文字", 250, 120, 550, 145)))
    }

    @Test
    fun twoSeparateBubbles_twoBoxes() {
        val boxes = boxAt(
            grid(
                intArrayOf(20, 10, 60, 16),
                intArrayOf(30, 40, 85, 46),
            ),
        )
        assertEquals(2, boxes.size)
        assertTrue(boxes.any { it.top == 100 })
        assertTrue(boxes.any { it.top == 400 })
    }

    @Test
    fun malformedGrid_returnsEmpty() {
        assertTrue(
            OwnBubbleLocator.boxesFromGrid(BooleanArray(4), 0, 0, step, frameWidth, frameHeight)
                .isEmpty(),
        )
    }

    @Test
    fun covers_lineCoveredByBubble_isOwn() {
        // 面积覆盖率兜底（2026-10-27）：行盒 90% 落在气泡盒内 → 判我方。
        val box = BubbleBox(left = 300, top = 200, right = 800, bottom = 260)
        val line = OcrLine("这条是我发出去的", 320, 210, 760, 250)
        with(OwnBubbleLocator) { assertTrue(box.covers(line, 0.6f)) }
        assertTrue(OwnBubbleLocator.isOwn(listOf(box), line))
    }

    @Test
    fun covers_adjacentOtherBubble_notOwn() {
        // 相邻的对方白气泡行：与绿气泡横向仅少量交叠，不得被误杀。
        val box = BubbleBox(left = 300, top = 200, right = 800, bottom = 260)
        val line = OcrLine("这条是对方发来的", 60, 200, 300, 260)
        with(OwnBubbleLocator) { assertFalse(box.covers(line, 0.6f)) }
        assertFalse(OwnBubbleLocator.isOwn(listOf(box), line))
    }

    @Test
    fun covers_partialOverlapBelowRatio_notOwn() {
        // 行盒跨出气泡外较多（覆盖率 5/9 ≈ 56%）→ 不判我方。
        val box = BubbleBox(left = 300, top = 200, right = 800, bottom = 260)
        val line = OcrLine("跨出气泡的正文", 0, 200, 900, 260)
        with(OwnBubbleLocator) { assertFalse(box.covers(line, 0.6f)) }
    }
}
