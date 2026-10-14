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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryConflictTest {

    @Test
    fun sameFrameDifferentValueConflicts() {
        assertTrue(MemoryConflict.isConflict("用户在杭州", "用户在上海"))
        assertTrue(MemoryConflict.isConflict("会议定在3点", "会议定在4点"))
        assertTrue(MemoryConflict.isConflict("She lives in Shanghai", "She lives in Hangzhou"))
    }

    @Test
    fun polarityFlipConflicts() {
        assertTrue(MemoryConflict.isConflict("她不去学校", "她去学校"))
        assertTrue(MemoryConflict.isConflict("I don't like coffee", "I like coffee"))
    }

    @Test
    fun enrichmentIsNotAConflict() {
        assertFalse(MemoryConflict.isConflict("用户喜欢咖啡", "用户喜欢咖啡厅"))
        assertFalse(MemoryConflict.isConflict("用户喜欢冰拿铁", "用户喜欢冰拿铁和深色主题"))
    }

    @Test
    fun differentSubjectsAreNotConflicts() {
        assertFalse(MemoryConflict.isConflict("今天天气很好", "明天要去北京"))
        assertFalse(MemoryConflict.isConflict("", "用户在上海"))
        assertFalse(MemoryConflict.isConflict("用户在杭州", "用户在杭州"))
    }

    @Test
    fun verdictExplainsWhy() {
        val verdict = MemoryConflict.check("用户在杭州", "用户在上海")
        assertTrue(verdict.conflicting)
        assertTrue(verdict.reason.orEmpty().contains("value differs"))
        assertEquals(false, MemoryConflict.check("今天天气很好", "明天要去北京").conflicting)
    }
}
