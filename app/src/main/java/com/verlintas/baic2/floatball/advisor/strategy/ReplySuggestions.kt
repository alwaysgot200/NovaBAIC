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

package com.verlintas.baic2.floatball.advisor.strategy

/**
 * 参谋回复气泡的编号建议解析（P3 §九，纯函数）：
 * 参谋按输出要求给 2~3 条「1. / 1、 / 1) / 1．」编号的建议回复，
 * 每条可能跨多行并带意图/风险标注；解析后供行内「✓采纳 / 长按纠偏」。
 */
object ReplySuggestions {

    /**
     * @param number 1 起编号（与模型输出序号一致，直接写入 adoptedIndex）。
     * @param text 该条建议正文（含跨行标注，已 trim）。
     */
    data class Item(val number: Int, val text: String)

    private val PLAN_BLOCK = Regex("""(?s)<plan>.*?</plan>""")
    private val FIRST_HEAD = Regex("""(?:^|\n)\s*1\s*[.、)）．]\s*""")

    /**
     * 从参谋回复正文解析编号建议。
     * @return 至少 2 条（1 起编号、序号连续）才返回；否则返回 null（UI 不显示行内按钮，
     *   整段复制仍可用）；至多 [MAX_ITEMS] 条。
     */
    fun parse(body: String?): List<Item>? {
        val text = body?.replace(PLAN_BLOCK, "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val first = FIRST_HEAD.find(text) ?: return null
        val items = mutableListOf<Item>()
        var expected = 1
        var searchFrom = first.range.first
        while (items.size < MAX_ITEMS) {
            val head = headRegex(expected).find(text, searchFrom) ?: break
            val next = headRegex(expected + 1).find(text, head.range.last + 1)
            val end = next?.range?.start ?: text.length
            val itemText = text.substring(head.range.last + 1, end).trim()
            if (itemText.isEmpty()) break
            items.add(Item(expected, itemText))
            if (next == null) break
            expected++
            searchFrom = next.range.first
        }
        return items.takeIf { it.size >= 2 }
    }

    private fun headRegex(number: Int): Regex =
        Regex("""(?:^|\n)\s*${number}\s*[.、)）．]\s*""")

    /** 第一条编号之前的引言/分析（可能为空）。 */
    fun preamble(body: String?): String {
        val text = body?.replace(PLAN_BLOCK, "") ?: return ""
        val first = FIRST_HEAD.find(text) ?: return text.trim()
        return text.substring(0, first.range.first).trim()
    }

    const val MAX_ITEMS = 3
}
