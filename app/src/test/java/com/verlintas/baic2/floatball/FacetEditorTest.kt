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

import com.verlintas.baic2.floatball.advisor.FacetEditor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 我方侧面标签编辑纯函数（VA-2 U5，方案 §2.4 facet 编辑器）：
 * 输入拆分 / 并集去重保序 / 精确移除 / 库选切换语义。
 */
class FacetEditorTest {

    @Test
    fun parseInputSplitsOnAllSeparatorsAndTrims() {
        assertEquals(
            listOf("博学", "风趣", "会吃喝", "会花钱"),
            FacetEditor.parseInput(" 博学，风趣、会吃喝;会花钱\n"),
        )
    }

    @Test
    fun parseInputDropsBlankSegments() {
        assertEquals(emptyList<String>(), FacetEditor.parseInput("，，、；"))
        assertEquals(listOf("办事周到"), FacetEditor.parseInput(" 办事周到 "))
    }

    @Test
    fun addTagsMergesWithDedupPreservingOrder() {
        val merged = FacetEditor.addTags(listOf("博学", "风趣"), "风趣、会吃喝")
        // 既有顺序不动，新标签追加尾部，重复不二次出现。
        assertEquals(listOf("博学", "风趣", "会吃喝"), merged)
    }

    @Test
    fun addTagsWithEmptyInputKeepsCurrent() {
        assertEquals(listOf("博学"), FacetEditor.addTags(listOf("博学"), "，"))
    }

    @Test
    fun removeTagOnlyRemovesExactMatch() {
        assertEquals(listOf("博学"), FacetEditor.removeTag(listOf("博学", "风趣"), "风趣"))
        // 精确匹配：移除「博学」不影响前缀超集「博学家长」。
        assertEquals(
            listOf("博学家长"),
            FacetEditor.removeTag(listOf("博学", "博学家长"), "博学"),
        )
    }

    @Test
    fun toggleTagAddsThenRemoves() {
        val added = FacetEditor.toggleTag(listOf("博学"), "风趣")
        assertEquals(listOf("博学", "风趣"), added)
        assertEquals(listOf("博学"), FacetEditor.toggleTag(added, "风趣"))
        // addTag（非 toggle）才具备幂等语义：重复添加同一枚不产生重复项。
        assertEquals(listOf("博学", "风趣"), FacetEditor.addTag(added, "风趣"))
    }

    @Test
    fun addTagIgnoresBlankAndDuplicates() {
        assertEquals(listOf("博学"), FacetEditor.addTag(listOf("博学"), "  "))
        assertEquals(listOf("博学"), FacetEditor.addTag(listOf("博学"), "博学"))
        assertEquals(listOf("博学", "风趣"), FacetEditor.addTag(listOf("博学"), " 风趣 "))
    }
}
