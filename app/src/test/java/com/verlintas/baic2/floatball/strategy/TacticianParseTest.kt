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

package com.verlintas.baic2.floatball.strategy

import com.verlintas.baic2.floatball.advisor.strategy.Tactician
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TacticianParseTest {

    @Test
    fun plainJson_parsedAndNormalized() {
        val plan = Tactician.parsePlan(
            """{"mission":"约周末咖啡","actions":["问周六有空吗 "," 问周六有空吗"],"forbidden":[""],"successWhen":"答应见面"}""",
        )!!
        assertEquals("约周末咖啡", plan.mission)
        assertEquals(listOf("问周六有空吗"), plan.actions) // trim + 空串过滤 + 去重
        assertEquals(emptyList<String>(), plan.forbidden)
        assertEquals("答应见面", plan.successWhen)
    }

    @Test
    fun jsonFence_stripped() {
        val plan = Tactician.parsePlan("```json\n{\"mission\":\"先共情\"}\n```")!!
        assertEquals("先共情", plan.mission)
    }

    @Test
    fun surroundedByExplanation_firstBracesExtracted() {
        val raw = "好的，我的计划是：{\"mission\":\"轻推一下\",\"actions\":[\"发个表情包\"]} 希望对你有帮助"
        val plan = Tactician.parsePlan(raw)!!
        assertEquals("轻推一下", plan.mission)
        assertEquals(listOf("发个表情包"), plan.actions)
    }

    @Test
    fun blankMission_returnsNull() {
        assertNull(Tactician.parsePlan("{\"mission\":\"   \"}"))
        assertNull(Tactician.parsePlan(""))
        assertNull(Tactician.parsePlan(null))
    }

    @Test
    fun brokenJson_returnsNull() {
        assertNull(Tactician.parsePlan("{\"mission\":未闭合"))
        assertNull(Tactician.parsePlan("完全没有 JSON"))
    }
}
