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

class AppModeTest {

    @Test
    fun chatHasNoTools() {
        assertFalse(AppMode.CHAT.toolsVisible)
    }

    @Test
    fun chatPlusIsReadOnly() {
        assertTrue(AppMode.CHAT_PLUS.toolsVisible)
        assertTrue(AppMode.CHAT_PLUS.readOnlyOnly)
        assertFalse(AppMode.CHAT_PLUS.requiresConfirmation)
    }

    @Test
    fun actRequiresConfirmation() {
        assertTrue(AppMode.ACT.requiresConfirmation)
        assertFalse(AppMode.ACT.readOnlyOnly)
    }

    @Test
    fun maxIsAutonomous() {
        assertTrue(AppMode.MAX.toolsVisible)
        assertFalse(AppMode.MAX.readOnlyOnly)
        assertFalse(AppMode.MAX.requiresConfirmation)
    }
}

class RunBudgetTest {

    @Test
    fun budgetsGrowWithAutonomy() {
        val modes = AppMode.entries
        val rounds = modes.map { RunBudget.forMode(it).maxRounds }
        assertEquals(rounds.sorted(), rounds)
    }

    @Test
    fun chatKeepsAMemoryOnlyToolBudget() {
        val chat = RunBudget.forMode(AppMode.CHAT)
        assertTrue(chat.maxToolCalls in 1..10)
        assertTrue(chat.maxToolCalls < RunBudget.forMode(AppMode.CHAT_PLUS).maxToolCalls)
    }

    @Test
    fun maxBudgetIsBounded() {
        val budget = RunBudget.forMode(AppMode.MAX)
        assertTrue(budget.maxRounds in 1..100)
        assertTrue(budget.maxToolCalls in 1..500)
        assertTrue(budget.maxWallClockMs > 0)
    }
}
