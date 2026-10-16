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

import com.verlintas.baic2.floatball.advisor.strategy.CardItem
import com.verlintas.baic2.floatball.advisor.strategy.CardPayloads
import com.verlintas.baic2.floatball.advisor.strategy.StrategyCardPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardMergeTest {

    private fun item(text: String, source: String = CardItem.SOURCE_AI) = CardItem(text, source)

    @Test
    fun userItems_keptAtFrontAndAiReplaced() {
        val old = StrategyCardPayload(
            selfConstraints = listOf(item("旧AI句"), item("我改的句子", CardItem.SOURCE_USER)),
            taboos = listOf(item("我的忌口", CardItem.SOURCE_USER)),
            cardVersion = 1,
        )
        val newAi = StrategyCardPayload(
            selfConstraints = listOf(item("新AI句")),
            taboos = listOf(item("新红线")),
            cardVersion = 2,
        )
        val merged = CardPayloads.mergeRetained(old, newAi)
        assertEquals(listOf("我改的句子", "新AI句"), merged.selfConstraints.map { it.text })
        assertEquals(listOf("我的忌口", "新红线"), merged.taboos.map { it.text })
        assertFalse(merged.selfConstraints.any { it.text == "旧AI句" })
    }

    @Test
    fun userRoleLine_keptWhenEdited_otherwiseOverwritten() {
        val editedOld = StrategyCardPayload(
            roleLine = "我自己写的角色句",
            roleLineSource = CardItem.SOURCE_USER,
        )
        val merged1 = CardPayloads.mergeRetained(
            editedOld,
            StrategyCardPayload(roleLine = "AI 新角色句"),
        )
        assertEquals("我自己写的角色句", merged1.roleLine)
        assertEquals(CardItem.SOURCE_USER, merged1.roleLineSource)

        val aiOld = StrategyCardPayload(roleLine = "旧 AI 角色句")
        val merged2 = CardPayloads.mergeRetained(aiOld, StrategyCardPayload(roleLine = "AI 新角色句"))
        assertEquals("AI 新角色句", merged2.roleLine)
    }

    @Test
    fun nullOldOrFingerprintCollision_passthrough() {
        val newAi = StrategyCardPayload(selfConstraints = listOf(item("同一句")))
        assertEquals(newAi, CardPayloads.mergeRetained(null, newAi))

        val old = StrategyCardPayload(selfConstraints = listOf(item(" 同一句 ", CardItem.SOURCE_USER)))
        // 用户句与新 AI 句同指纹 → 不重复插入，以新 ai 列表为准。
        val merged = CardPayloads.mergeRetained(old, newAi)
        assertEquals(1, merged.selfConstraints.size)
        assertTrue(merged.selfConstraints.single().text == "同一句")
    }
}
