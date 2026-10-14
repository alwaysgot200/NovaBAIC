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
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryConsolidatorTest {

    private fun note(id: Long, content: String) = Note(id = id, content = content)

    @Test
    fun holdsWinOverEverything() {
        val decision = MemoryConsolidator.decide(
            content = "我的银行卡密码是8877",
            existing = listOf(note(1, "用户喜欢拿铁")),
            holds = listOf(MemoryHold(id = 9, content = "银行卡密码是8877")),
        )
        assertEquals(MemoryConsolidator.Decision.Held(9), decision)
    }

    @Test
    fun suppressedTracesCannotBeRelearned() {
        val decision = MemoryConsolidator.decide(
            content = "用户住在杭州",
            existing = emptyList(),
            suppressed = listOf(note(7, "用户住在杭州").copy(suppressed = true)),
        )
        assertEquals(MemoryConsolidator.Decision.Suppressed(7), decision)
    }

    @Test
    fun exactDuplicateIsRefused() {
        val decision = MemoryConsolidator.decide(
            content = "用户喜欢冰拿铁",
            existing = listOf(note(1, "用户喜欢冰拿铁")),
        )
        assertEquals(MemoryConsolidator.Decision.Duplicate(1), decision)
    }

    @Test
    fun nearDuplicateReconsolidatesInPlace() {
        val decision = MemoryConsolidator.decide(
            content = "用户住在杭州",
            existing = listOf(note(1, "用户在杭州")),
        )
        assertEquals(MemoryConsolidator.Decision.Merged(1, "用户在杭州"), decision)
    }

    @Test
    fun newFactCarriesSimilarIdsAndConflicts() {
        val decision = MemoryConsolidator.decide(
            content = "用户在上海",
            existing = listOf(note(1, "用户在杭州"), note(2, "用户喜欢拿铁")),
        )
        val stored = decision as MemoryConsolidator.Decision.Stored
        assertEquals(listOf(1L), stored.similarIds)
        assertEquals(1, stored.conflicts.size)
        assertEquals(1L, stored.conflicts.first().noteId)
        assertTrue(stored.conflicts.first().reason.isNotEmpty())
    }

    @Test
    fun unrelatedFactHasNoSimilarIdsOrConflicts() {
        val decision = MemoryConsolidator.decide(
            content = "用户喜欢深色主题",
            existing = listOf(note(1, "用户住在杭州")),
        )
        val stored = decision as MemoryConsolidator.Decision.Stored
        assertTrue(stored.similarIds.isEmpty())
        assertTrue(stored.conflicts.isEmpty())
    }

    @Test
    fun extensionIsSimilarButNotAConflict() {
        val decision = MemoryConsolidator.decide(
            content = "用户喜欢咖啡和茶",
            existing = listOf(note(1, "用户喜欢咖啡")),
        )
        val stored = decision as MemoryConsolidator.Decision.Stored
        assertEquals(listOf(1L), stored.similarIds)
        assertTrue(stored.conflicts.isEmpty())
    }
}
