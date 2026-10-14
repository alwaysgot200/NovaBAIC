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

class MemoryHistoryTest {

    // Timeline: C0 from t0; at t1 replaced by C1; at t2 replaced by C2 (now).
    private val revisions = listOf(
        NoteRevision(id = 2, content = "在上海", importance = 3, replacedAt = 2_000L),
        NoteRevision(id = 1, content = "在杭州", importance = 4, replacedAt = 1_000L),
    )

    @Test
    fun beforeAnyReplacementYieldsOldestBeforeImage() {
        val (content, importance) = MemoryHistory.contentAsOf("在乌兰浩特", 5, revisions, at = 500L)
        assertEquals("在杭州", content)
        assertEquals(4, importance)
    }

    @Test
    fun betweenReplacementsYieldsTheContentValidThen() {
        val (content, _) = MemoryHistory.contentAsOf("在乌兰浩特", 5, revisions, at = 1_500L)
        assertEquals("在上海", content)
    }

    @Test
    fun afterTheLastReplacementYieldsCurrent() {
        val (content, importance) = MemoryHistory.contentAsOf("在乌兰浩特", 5, revisions, at = 9_999L)
        assertEquals("在乌兰浩特", content)
        assertEquals(5, importance)
    }

    @Test
    fun noHistoryYieldsCurrent() {
        val (content, importance) = MemoryHistory.contentAsOf("喜欢拿铁", 3, emptyList(), at = 9_999L)
        assertEquals("喜欢拿铁", content)
        assertEquals(3, importance)
    }
}
