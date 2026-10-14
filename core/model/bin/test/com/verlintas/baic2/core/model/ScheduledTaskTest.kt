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

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduledTaskTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun dailyScheduleFiresTodayWhenStillAhead() {
        val from = at(2026, 9, 29, 7, 0)
        val next = nextScheduledTrigger("08:00", emptySet(), from)
        assertEquals(at(2026, 9, 29, 8, 0), next)
    }

    @Test
    fun dailyScheduleRollsToTomorrowWhenPast() {
        val from = at(2026, 9, 29, 9, 30)
        val next = nextScheduledTrigger("08:00", emptySet(), from)
        assertEquals(at(2026, 9, 30, 8, 0), next)
    }

    @Test
    fun weekdayFilterSkipsOtherDays() {
        // 2026-09-29 is a Tuesday; schedule Mondays only -> next week Monday.
        // Use a mid-week time so "same day" cannot match.
        val from = at(2026, 9, 29, 12, 0)
        val next = nextScheduledTrigger("08:00", setOf(1), from)!!
        val calendar = Calendar.getInstance().apply { timeInMillis = next }
        val isoDay = calendar.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
        assertEquals(1, isoDay)
        assertTrue(next > from)
    }

    @Test
    fun invalidInputReturnsNull() {
        assertNull(nextScheduledTrigger("25:00", emptySet(), 0L))
        assertNull(nextScheduledTrigger("8", emptySet(), 0L))
        assertNull(nextScheduledTrigger("08:xx", emptySet(), 0L))
    }
}
