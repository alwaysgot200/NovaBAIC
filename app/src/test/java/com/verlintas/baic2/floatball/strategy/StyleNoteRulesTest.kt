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

import com.verlintas.baic2.floatball.advisor.strategy.StyleNoteRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [StyleNoteRules] 的确定性裁剪与宽容错单测（P3 §八）。 */
class StyleNoteRulesTest {

    @Test
    fun parses_plain_json() {
        val out = StyleNoteRules.parse("""{"do":["先共情","说事实"],"dont":["讲大道理"]}""")
        assertEquals(listOf("先共情", "说事实"), out?.dos)
        assertEquals(listOf("讲大道理"), out?.dont)
    }

    @Test
    fun strips_code_fence_and_prose() {
        val raw = """
            好的，结果如下：
            ```json
            {"do": ["慢一点"],"dont": []}
            ```
            请确认
        """.trimIndent()
        assertEquals(listOf("慢一点"), StyleNoteRules.parse(raw)?.dos)
    }

    @Test
    fun truncates_to_three_items_per_side_and_dedupes() {
        val raw = """{"do":["a","b","c","d","a"],"dont":["x","y","z","w"]}"""
        val out = StyleNoteRules.parse(raw)
        assertEquals(listOf("a", "b", "c"), out?.dos)
        assertEquals(listOf("x", "y", "z"), out?.dont)
    }

    @Test
    fun clips_overlong_item_to_20_chars() {
        val item = "在她加班的时候不要讲任何大道理先问问对方吃饭了没有"
        val out = StyleNoteRules.parse("""{"do":["$item"],"dont":[]}""")
        assertEquals(20, out?.dos?.single()?.length)
    }

    @Test
    fun rejects_blank_and_garbage() {
        assertNull(StyleNoteRules.parse(null))
        assertNull(StyleNoteRules.parse(""))
        assertNull(StyleNoteRules.parse("不是 json"))
        // 裁剪后两组全空 = 失败。
        assertNull(StyleNoteRules.parse("""{"do":["  ",""],"dont":[]}"""))
        // 标量冒充数组 = 结构不可信，整体判失败（fail-closed，不做半侧保留）。
        assertNull(StyleNoteRules.parse("""{"do":"oops","dont":["ok"]}"""))
    }
}
