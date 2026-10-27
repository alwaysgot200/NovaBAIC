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

package com.verlintas.baic2.floatball.auto

import com.verlintas.baic2.device.api.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 手势粘贴通道纯逻辑（2026-10-27 真机「自动回复发不出去」修复，第三种执行面）：
 * 输入栏锚点阶梯 [A11yGestureInjector.inputRowCandidates]、长按菜单行合并
 * [A11yGestureInjector.mergeRows]、菜单项定位 [A11yGestureInjector.pickMenuKeyword]。
 * 手势派发/剪贴板/截图 OCR 等 Android 边界不在此测，由真机复测（`auto_timing:send_gesture`）覆盖。
 */
class A11yGestureInjectorTest {

    private companion object {
        /** 真机（HyperOS 1080×2460）实测帧高。 */
        const val H = 2460
    }

    // ── 输入栏锚点阶梯：像素绿键行 → OCR「发送」行 → 键盘展开比例 → 键盘收起比例 ──

    @Test
    fun inputRowCandidates_greenThenOcrThenRatios() {
        val rows = A11yGestureInjector.inputRowCandidates(
            height = H,
            greenSendYs = listOf(1381),
            ocrSendY = 1382,
        )
        // 1382 = 0.562H（Sogou QWERTY 展开态输入行中线）；2302 = 0.936H（键盘收起输入栏）。
        assertEquals(listOf(1381, 1382, 2302), rows)
    }

    @Test
    fun inputRowCandidates_clipsStatusBarAndBottomEdge() {
        // 顶栏/状态栏与贴底一像素都不该成为长按目标。
        val rows = A11yGestureInjector.inputRowCandidates(
            height = H,
            greenSendYs = listOf(100, 2450),
            ocrSendY = null,
        )
        assertEquals(listOf(1382, 2302), rows)
    }

    @Test
    fun inputRowCandidates_dedupsSameRow() {
        // 绿键与 OCR「发送」同行时只留一个锚点：重复长按会白等一轮菜单轮询。
        val rows = A11yGestureInjector.inputRowCandidates(
            height = H,
            greenSendYs = listOf(1382),
            ocrSendY = 1382,
        )
        assertEquals(listOf(1382, 2302), rows)
    }

    @Test
    fun inputRowCandidates_invalidHeight_empty() {
        assertEquals(emptyList<Int>(), A11yGestureInjector.inputRowCandidates(0, listOf(1), 2))
        assertEquals(emptyList<Int>(), A11yGestureInjector.inputRowCandidates(-1, emptyList(), null))
    }

    // ── 长按菜单行合并（ML Kit 常把「粘贴」拆成两行） ──

    @Test
    fun mergeRows_joinsSplitWordAndKeepsWordCenter() {
        // 真机菜单形态：左「粘」右「贴」，两个行框高度接近、横向相邻。
        val merged = A11yGestureInjector.mergeRows(
            lines = listOf(
                OcrLine("粘", 100, 1260, 130, 1300),
                OcrLine("贴", 132, 1262, 162, 1302),
            ),
            tolerancePx = 24,
        )
        assertEquals(1, merged.size)
        assertEquals("粘贴", merged[0].text)
        // 行框并集 → 点击中心回到整词中心（模式 B 的粘贴链路靠这个坐标）。
        assertEquals(131, merged[0].centerX)
        assertEquals(1281, merged[0].centerY)
    }

    @Test
    fun mergeRows_keepsVerticallySeparatedRows() {
        val merged = A11yGestureInjector.mergeRows(
            lines = listOf(
                OcrLine("粘贴", 100, 1260, 160, 1300),
                OcrLine("全选", 100, 2260, 160, 2300),
            ),
            tolerancePx = 24,
        )
        assertEquals(2, merged.size)
        assertEquals("粘贴", merged[0].text)
        assertEquals("全选", merged[1].text)
    }

    @Test
    fun mergeRows_singleLineUntouched() {
        val only = listOf(OcrLine("粘贴", 100, 1260, 160, 1300))
        assertEquals(only, A11yGestureInjector.mergeRows(only, 24))
    }

    // ── 菜单项定位：只在屏幕下半区找、离锚点最近、并列取最左 ──

    @Test
    fun pickMenuKeyword_ignoresChatBodyAndPicksNearest() {
        val lines = listOf(
            // 聊天气泡正文里出现的同名词（zone 以上）：不参与长按菜单判定。
            OcrLine("粘贴", 60, 600, 120, 640),
            // 真机菜单项「粘贴」中心 (112,1274)。
            OcrLine("粘贴", 90, 1254, 140, 1294),
            OcrLine("粘贴", 480, 1480, 540, 1520),
        )
        val hit = A11yGestureInjector.pickMenuKeyword(lines, H, A11yGestureInjector.KEYWORD_PASTE, 1382)
        assertEquals(115, hit?.centerX)
        assertEquals(1274, hit?.centerY)
    }

    @Test
    fun pickMenuKeyword_tiePicksLeftmost() {
        val lines = listOf(
            OcrLine("粘贴", 300, 1254, 360, 1294),
            OcrLine("粘贴", 90, 1254, 140, 1294),
        )
        val hit = A11yGestureInjector.pickMenuKeyword(lines, H, A11yGestureInjector.KEYWORD_PASTE, 1274)
        assertEquals(115, hit?.centerX)
    }

    @Test
    fun pickMenuKeyword_matchesMergedAndSplitForms() {
        // mergeRows 之后「粘贴」是一行；未合并时「粘」+「贴」两行都含 needle 时也会命中。
        val merged = A11yGestureInjector.mergeRows(
            lines = listOf(
                OcrLine("粘", 100, 1260, 130, 1300),
                OcrLine("贴", 132, 1262, 162, 1302),
            ),
            tolerancePx = 24,
        )
        assertEquals(
            131,
            A11yGestureInjector.pickMenuKeyword(merged, H, A11yGestureInjector.KEYWORD_PASTE, 1300)?.centerX,
        )
    }

    @Test
    fun pickMenuKeyword_clearInputKeywords() {
        val lines = listOf(
            OcrLine("全选", 90, 1254, 160, 1294),
            OcrLine("剪切", 320, 1254, 390, 1294),
            OcrLine("粘贴", 560, 1254, 630, 1294),
        )
        assertEquals(
            125,
            A11yGestureInjector.pickMenuKeyword(lines, H, A11yGestureInjector.KEYWORD_SELECT_ALL, 1274)?.centerX,
        )
        assertEquals(
            355,
            A11yGestureInjector.pickMenuKeyword(lines, H, A11yGestureInjector.KEYWORD_CUT, 1274)?.centerX,
        )
    }

    @Test
    fun pickMenuKeyword_missOrInvalid_false() {
        val lines = listOf(OcrLine("粘贴", 90, 1254, 140, 1294))
        assertNull(A11yGestureInjector.pickMenuKeyword(lines, H, A11yGestureInjector.KEYWORD_CUT, 1274))
        assertNull(A11yGestureInjector.pickMenuKeyword(lines, H, "  ", 1274))
        assertNull(A11yGestureInjector.pickMenuKeyword(lines, 0, A11yGestureInjector.KEYWORD_PASTE, 1274))
        assertNull(A11yGestureInjector.pickMenuKeyword(emptyList(), H, A11yGestureInjector.KEYWORD_PASTE, 1274))
    }
}