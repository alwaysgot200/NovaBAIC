/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.strategy

import com.verlintas.baic2.floatball.advisor.FieldOption
import com.verlintas.baic2.floatball.advisor.PersonaFieldDef
import com.verlintas.baic2.floatball.advisor.PersonaFieldType
import com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity
import com.verlintas.baic2.floatball.advisor.strategy.PersonaCandidate
import com.verlintas.baic2.floatball.advisor.strategy.PersonaSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaSelectTest {

    private val now = 100L * PersonaSelector.DAY_MS

    private fun def(
        key: String,
        label: String = key,
        salience: String? = PersonaFieldDef.SALIENCE_NORMAL,
        staleAfterDays: Int? = null,
        options: List<FieldOption>? = null,
    ) = PersonaFieldDef(
        key = key,
        owner = "their",
        group = "g",
        type = PersonaFieldType.TEXT,
        label = label,
        salience = salience,
        staleAfterDays = staleAfterDays,
        options = options,
    )

    private fun candidate(
        key: String,
        label: String = key,
        salience: String? = PersonaFieldDef.SALIENCE_NORMAL,
        staleAfterDays: Int? = null,
        updatedAt: Long = now,
        options: List<FieldOption>? = null,
        value: String = "v-$key",
    ) = PersonaCandidate(
        def = def(key, label, salience, staleAfterDays, options),
        entity = PersonaFieldEntity(
            contactId = 1L,
            field = key,
            value = value,
            confirmed = true,
            updatedAt = updatedAt,
        ),
        displayed = value,
    )

    @Test
    fun `caps at limit six per side`() {
        val candidates = (1..9).map { candidate("f$it", salience = PersonaFieldDef.SALIENCE_NORMAL) }
        val picked = PersonaSelector.select(candidates, emptyList(), limit = 6, now = now)
        assertEquals(6, picked.size)
    }

    @Test
    fun `taboo fields are never cropped even beyond limit`() {
        val candidates = (1..8).map { candidate("f$it") } +
            candidate("taboos", label = "雷区禁忌", salience = PersonaFieldDef.SALIENCE_HIGH) +
            candidate("my_taboo", label = "我不能说的话", salience = PersonaFieldDef.SALIENCE_HIGH)
        val picked = PersonaSelector.select(candidates, emptyList(), limit = 6, now = now)
        val keys = picked.map { it.candidate.def.key }.toSet()
        assertTrue("their taboos must survive", "taboos" in keys)
        assertTrue("my taboos must survive", "my_taboo" in keys)
        assertEquals(6, picked.size)
    }

    @Test
    fun `signal relevant fields rank before salience`() {
        // budget 是 low salience 且更旧，但信号「客户说出具体预算」弱关联命中其 label → 排前。
        val budget = candidate(
            "budget",
            label = "预算",
            salience = PersonaFieldDef.SALIENCE_LOW,
            updatedAt = now - 10 * PersonaSelector.DAY_MS,
        )
        val others = (1..6).map {
            candidate("generic$it", label = "普通字段$it", salience = PersonaFieldDef.SALIENCE_HIGH)
        }
        val picked = PersonaSelector.select(
            others + budget,
            signals = listOf("对方主动说出具体预算范围"),
            limit = 3,
            now = now,
        )
        assertEquals("budget", picked.first().candidate.def.key)
    }

    @Test
    fun `salience high beats normal and newer updatedAt wins within rank`() {
        val low = candidate("low", salience = PersonaFieldDef.SALIENCE_LOW)
        val normalOld = candidate("normalOld", updatedAt = now - 5 * PersonaSelector.DAY_MS)
        val normalNew = candidate("normalNew", updatedAt = now)
        val high = candidate("high", salience = PersonaFieldDef.SALIENCE_HIGH)
        val picked = PersonaSelector.select(
            listOf(low, normalOld, normalNew, high),
            emptyList(),
            limit = 3,
            now = now,
        )
        assertEquals(listOf("high", "normalNew", "normalOld"), picked.map { it.candidate.def.key })
    }

    @Test
    fun `stale fields kept but flagged with stale wording`() {
        val fresh = candidate("city", label = "城市", staleAfterDays = 365, updatedAt = now)
        val old = candidate(
            "occupation",
            label = "职业",
            staleAfterDays = 180,
            updatedAt = now - 200 * PersonaSelector.DAY_MS,
        )
        val noTtl = candidate("values", label = "价值观")
        val picked = PersonaSelector.select(listOf(fresh, old, noTtl), emptyList(), now = now)
        val byKey = picked.associateBy { it.candidate.def.key }
        assertFalse(byKey.getValue("city").stale)
        assertTrue("超过 staleAfterDays 必须标 stale", byKey.getValue("occupation").stale)
        assertFalse("未声明时效的字段永不 stale", byKey.getValue("values").stale)
    }

    @Test
    fun `relevance matches option label inside signal phrase`() {
        val intentStage = candidate(
            "intentStage",
            label = "意向阶段",
            options = listOf(
                FieldOption(code = "compare", label = "比价"),
                FieldOption(code = "deal", label = "成交"),
            ),
            value = "compare",
        )
        val picked = PersonaSelector.select(
            listOf(intentStage),
            signals = listOf("对方开始比价并要求正式报价"),
            now = now,
        )
        assertEquals(1, picked.size)
    }
}
