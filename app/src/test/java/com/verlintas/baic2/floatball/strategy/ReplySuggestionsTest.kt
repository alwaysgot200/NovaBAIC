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
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.strategy

import com.verlintas.baic2.floatball.advisor.strategy.BasisKeywords
import com.verlintas.baic2.floatball.advisor.strategy.ReplySuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3 §九：编号建议解析 + 依据关键词裁剪。 */
class ReplySuggestionsTest {

    @Test
    fun `parses dotted numbered items with multiline annotations`() {
        val body = """
            对方在意预算，建议先稳住情绪。

            1. 我理解你的顾虑，价格这块我们可以再聊聊
            （意图：共情；风险：无）
            2. 你方便说下心理价位吗？我去申请
        """.trimIndent()

        val items = ReplySuggestions.parse(body)
        assertEquals(2, items?.size)
        assertEquals(1, items!![0].number)
        assertTrue(items[0].text.startsWith("我理解你的顾虑"))
        assertTrue(items[0].text.contains("意图：共情"))
        assertEquals(2, items[1].number)
        assertTrue(ReplySuggestions.preamble(body).startsWith("对方在意预算"))
    }

    @Test
    fun `accepts chinese comma and parenthesis numbering up to three`() {
        val body = "1、第一条\n2）第二条\n3．第三条\n4. 不应出现"
        val items = ReplySuggestions.parse(body)
        assertEquals(3, items?.size)
        assertEquals(listOf(1, 2, 3), items!!.map { it.number })
        assertTrue(items[2].text.contains("第三条"))
    }

    @Test
    fun `returns null when fewer than two items`() {
        assertNull(ReplySuggestions.parse("只有一段话，没有编号建议。"))
        assertNull(ReplySuggestions.parse("1. 只有一条"))
        assertNull(ReplySuggestions.parse(null))
    }

    @Test
    fun `strips plan block before parsing`() {
        val body = "<plan>内部计划\n1. 不该被解析</plan>\n1. 正式建议一\n2. 正式建议二"
        val items = ReplySuggestions.parse(body)
        assertEquals(2, items?.size)
        assertTrue(items!![0].text.startsWith("正式建议一"))
    }

    @Test
    fun `basis keywords trim parentheses clip and limit`() {
        val basis = BasisKeywords.from(
            listOf(
                "在北京（早期信息）",
                "互联网大厂高级产品经理岗位",
                "A", // 不足 2 字丢弃
                "在北京（早期信息）", // 去括号后重复
                "预算三十万左右",
                "第四条不应入选",
            ),
        )
        assertEquals(3, basis.size)
        assertEquals("在北京", basis[0])
        assertTrue(basis[1].length <= BasisKeywords.MAX_KEYWORD_CHARS)
        assertEquals("预算三十万左右", basis[2])
    }
}
