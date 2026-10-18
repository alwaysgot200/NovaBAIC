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

package com.verlintas.baic2.floatball.advisor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 我方人设段的关系侧面拼装（F3 纯函数 [mySectionLines]）：全局 my_* 行为底，
 * 档案 selfFacet 标签并列追加一行；空 facet 原样返回全局行。
 */
class ChatBriefBuilderTest {

    @Test
    fun mySectionLines_emptyFacetKeepsBaseLines() {
        val base = listOf("我的身份：乙方项目经理", "我的说话风格：专业克制")
        assertEquals(base, mySectionLines(base, emptyList()))
        // 全空白标签等同空 facet。
        assertEquals(base, mySectionLines(base, listOf("  ", "")))
    }

    @Test
    fun mySectionLines_appendsSingleFacetRow() {
        val base = listOf("我的身份：乙方项目经理")
        val lines = mySectionLines(base, listOf("专业", "可靠"))
        assertEquals(2, lines.size)
        assertEquals("我的身份：乙方项目经理", lines[0])
        assertEquals("$MY_FACET_ROW_LABEL: 专业、可靠", lines[1])
    }

    @Test
    fun mySectionLines_trimsDedupesAndDropsBlanks() {
        // 空白剔除、去重、保留出现序。
        val lines = mySectionLines(emptyList(), listOf(" 专业 ", "", "专业", "靠谱", "  "))
        assertEquals(1, lines.size)
        assertEquals("$MY_FACET_ROW_LABEL: 专业、靠谱", lines[0])
    }
}
