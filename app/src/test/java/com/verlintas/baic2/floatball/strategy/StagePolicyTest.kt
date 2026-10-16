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

import com.verlintas.baic2.floatball.advisor.ScenarioPack
import com.verlintas.baic2.floatball.advisor.ScenarioStage
import com.verlintas.baic2.floatball.advisor.data.StageStateEntity
import com.verlintas.baic2.floatball.advisor.strategy.StagePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StagePolicyTest {

    private val now = 1_000_000L

    private val pack = ScenarioPack(
        code = "dating",
        label = "恋爱",
        stages = listOf(
            ScenarioStage("a", "甲站", advanceSignals = listOf("s1", "s2", "s3")),
            ScenarioStage("b", "乙站", advanceSignals = listOf("s4")),
            ScenarioStage("c", "丙站", advanceSignals = emptyList()),
        ),
    )

    @Test
    fun initial_entersFirstStageWithEmptyHits() {
        val s = StagePolicy.initial(contactId = 9L, pack = pack, now = now)!!
        assertEquals("a", s.stageCode)
        assertEquals(9L, s.contactId)
        assertEquals("{}", s.advanceHits)
        assertEquals(now, s.enteredAt)
    }

    @Test
    fun maybeAdvance_belowThreshold_stays() {
        // 甲站 3 个信号，阈值 max(1,3-1)=2；只命中 1 种不跳。
        var s = StagePolicy.initial(1L, pack, now)!!
        s = s.copy(advanceHits = StagePolicy.bumpHits(s.advanceHits, "s1"))
        assertNull(StagePolicy.maybeAdvance(s, pack))
    }

    @Test
    fun maybeAdvance_thresholdReached_advancesAndApplyResets() {
        var s = StagePolicy.initial(1L, pack, now)!!
        s = s.copy(advanceHits = StagePolicy.bumpHits(s.advanceHits, "s1"))
        s = s.copy(advanceHits = StagePolicy.bumpHits(s.advanceHits, "s2"))
        val adv = StagePolicy.maybeAdvance(s, pack)!!
        assertEquals("b", adv.nextCode)
        val moved = StagePolicy.applyAdvance(s, adv, now + 100)
        assertEquals("b", moved.stageCode)
        assertEquals("{}", moved.advanceHits)
        assertEquals(now + 100, moved.enteredAt)
    }

    @Test
    fun maybeAdvance_terminalStage_staysForever() {
        val terminal = StageStateEntity(1L, "dating", "c", now, "{}")
        assertNull(StagePolicy.maybeAdvance(terminal, pack))
    }

    @Test
    fun repeatedSameKeyCountsOnce_manualMoveResetsAndValidates() {
        var s = StagePolicy.initial(1L, pack, now)!!
        repeat(3) {
            s = s.copy(advanceHits = StagePolicy.bumpHits(s.advanceHits, "s1"))
        }
        // 同一信号命中 3 次仍只有 1 种 distinct key，不跃迁。
        assertNull(StagePolicy.maybeAdvance(s, pack))

        val moved = StagePolicy.manualMove(s, pack, "c", now + 50)
        assertEquals("c", moved.stageCode)
        assertEquals("{}", moved.advanceHits)
        assertEquals(StagePolicy.MANUAL_EVIDENCE, moved.evidence)

        // 越界站码原样返回。
        assertEquals(s, StagePolicy.manualMove(s, pack, "not-a-station", now + 60))
    }
}
