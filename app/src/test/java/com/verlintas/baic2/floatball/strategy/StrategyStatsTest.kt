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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.strategy

import com.verlintas.baic2.floatball.advisor.data.MISSION_ADVANCED
import com.verlintas.baic2.floatball.advisor.data.MISSION_HELD
import com.verlintas.baic2.floatball.advisor.data.MISSION_IGNORED
import com.verlintas.baic2.floatball.advisor.data.MISSION_INFORMATIONAL
import com.verlintas.baic2.floatball.advisor.data.MISSION_PENDING
import com.verlintas.baic2.floatball.advisor.data.MISSION_REGRESSED
import com.verlintas.baic2.floatball.advisor.data.TalkMissionEntity
import com.verlintas.baic2.floatball.advisor.strategy.RecapTier
import com.verlintas.baic2.floatball.advisor.strategy.StrategyStatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [StrategyStatsCalculator] 纯函数单测（2026-10-22 方案 F1：窗口/分母/档位口径）。 */
class StrategyStatsTest {

    private fun mission(
        result: String,
        adopted: Int? = null,
        id: Long = 0L,
    ) = TalkMissionEntity(id = id, conversationId = 1L, mission = "m$id", result = result, adoptedIndex = adopted)

    private fun list(vararg results: String) = results.mapIndexed { i, r -> mission(r, id = i.toLong()) }

    @Test
    fun `pending ignored informational are excluded from denominator`() {
        val stats = StrategyStatsCalculator.calculate(
            list(MISSION_PENDING, MISSION_IGNORED, MISSION_INFORMATIONAL, MISSION_ADVANCED),
            failStreak = 0,
        )
        assertEquals(1, stats.total)
        assertEquals(1, stats.succeeded)
        assertNull(stats.tier)
    }

    @Test
    fun `good tier when success rate at least seventy percent`() {
        val missions = list(
            MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED,
            MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED, MISSION_HELD,
            MISSION_HELD, MISSION_HELD,
        ) + listOf(
            mission(MISSION_ADVANCED, adopted = 1),
            mission(MISSION_ADVANCED, adopted = 2),
        )
        val stats = StrategyStatsCalculator.calculate(missions, failStreak = 0)
        assertEquals(12, stats.total)
        assertEquals(9, stats.succeeded)
        assertEquals(3, stats.failed)
        assertEquals(2, stats.adopted)
        assertEquals(RecapTier.GOOD, stats.tier)
    }

    @Test
    fun `mixed tier between forty and seventy percent`() {
        val stats = StrategyStatsCalculator.calculate(
            list(MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED,
                MISSION_HELD, MISSION_HELD, MISSION_HELD, MISSION_HELD, MISSION_HELD, MISSION_HELD),
            failStreak = 0,
        )
        assertEquals(10, stats.total)
        assertEquals(RecapTier.MIXED, stats.tier)
    }

    @Test
    fun `bad tier when success rate below forty percent`() {
        val stats = StrategyStatsCalculator.calculate(
            list(MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED,
                MISSION_REGRESSED, MISSION_REGRESSED, MISSION_REGRESSED, MISSION_REGRESSED,
                MISSION_REGRESSED, MISSION_REGRESSED, MISSION_REGRESSED),
            failStreak = 0,
        )
        assertEquals(10, stats.total)
        assertEquals(3, stats.succeeded)
        assertEquals(7, stats.failed)
        assertEquals(RecapTier.BAD, stats.tier)
    }

    @Test
    fun `bad tier when fail streak at least three even with high rate`() {
        val stats = StrategyStatsCalculator.calculate(
            list(MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED,
                MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED,
                MISSION_ADVANCED, MISSION_ADVANCED),
            failStreak = 3,
        )
        assertEquals(RecapTier.BAD, stats.tier)
    }

    @Test
    fun `window caps judged missions at twenty`() {
        // 列表按新 → 旧：尾部 5 条最旧的 REGRESSED 应被窗口裁掉。
        val results = List(25) { if (it >= 20) MISSION_REGRESSED else MISSION_ADVANCED }
        val stats = StrategyStatsCalculator.calculate(
            results.mapIndexed { i, r -> mission(r, id = i.toLong()) },
            failStreak = 0,
        )
        assertEquals(20, stats.total)
        assertEquals(20, stats.succeeded)
        assertEquals(0, stats.failed)
    }

    @Test
    fun `tier null when judged sample below minimum`() {
        val stats = StrategyStatsCalculator.calculate(
            list(MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED,
                MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED, MISSION_ADVANCED,
                MISSION_ADVANCED),
            failStreak = 0,
        )
        assertEquals(9, stats.total)
        assertNull(stats.tier)
    }

    @Test
    fun `held counts as failed not succeeded`() {
        val stats = StrategyStatsCalculator.calculate(
            list(MISSION_ADVANCED, MISSION_HELD, MISSION_HELD, MISSION_HELD, MISSION_HELD,
                MISSION_HELD, MISSION_HELD, MISSION_HELD, MISSION_HELD, MISSION_HELD),
            failStreak = 1,
        )
        assertEquals(10, stats.total)
        assertEquals(1, stats.succeeded)
        assertEquals(9, stats.failed)
        assertEquals(RecapTier.BAD, stats.tier)
    }
}
