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

import com.verlintas.baic2.floatball.advisor.strategy.GoalDriftDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GoalDriftDetector] 纯函数单测（2026-10-22 方案 F2：零交集/门槛/清零口径）。 */
class GoalDriftTest {

    private val now = 1_700_000_000_000L
    private val oldEnough = now - GoalDriftDetector.MIN_AGE_MS

    /** 与判据无任何共享词元的任务令文本（天气寒暄主题）。 */
    private val miss = List(10) { "今天问候对方近况并闲聊天气" }

    /** 与判据共享词元的任务令文本（提及「合同签订」）。 */
    private val hit = "本周继续推进合同签订的事项"

    private val criteria = "本周内完成合同签订与盖章存档"

    @Test
    fun `drifted when six consecutive missions share no criteria terms`() {
        val v = GoalDriftDetector.detect(
            l1Criteria = criteria,
            missionTexts = miss.take(6),
            l1CreatedAt = oldEnough,
            now = now,
        )
        assertTrue(v.drifted)
        assertEquals(6, v.consecutive)
    }

    @Test
    fun `not drifted when l1 younger than three days`() {
        val v = GoalDriftDetector.detect(
            l1Criteria = criteria,
            missionTexts = miss.take(6),
            l1CreatedAt = now - GoalDriftDetector.MIN_AGE_MS + 1,
            now = now,
        )
        assertFalse(v.drifted)
        assertEquals(0, v.consecutive)
    }

    @Test
    fun `not drifted when mission sample below six`() {
        val v = GoalDriftDetector.detect(
            l1Criteria = criteria,
            missionTexts = miss.take(5),
            l1CreatedAt = oldEnough,
            now = now,
        )
        assertFalse(v.drifted)
        assertEquals(0, v.consecutive)
    }

    @Test
    fun `blank criteria never drifts`() {
        val v = GoalDriftDetector.detect(
            l1Criteria = "   ",
            missionTexts = miss.take(6),
            l1CreatedAt = oldEnough,
            now = now,
        )
        assertFalse(v.drifted)
    }

    @Test
    fun `consecutive count resets when newest mission hits criteria`() {
        // 最新一条命中判据 → 连续归零，不提醒。
        val newestHit = listOf(hit) + miss.take(6)
        val v = GoalDriftDetector.detect(
            l1Criteria = criteria,
            missionTexts = newestHit,
            l1CreatedAt = oldEnough,
            now = now,
        )
        assertFalse(v.drifted)
        assertEquals(0, v.consecutive)

        // 命中夹在中间 → 只数最新连续段（2 < 6 不提醒）。
        val midHit = miss.take(2) + listOf(hit) + miss.take(6)
        val v2 = GoalDriftDetector.detect(
            l1Criteria = criteria,
            missionTexts = midHit,
            l1CreatedAt = oldEnough,
            now = now,
        )
        assertFalse(v2.drifted)
        assertEquals(2, v2.consecutive)
    }

    @Test
    fun `single shared term breaks the run`() {
        val v = GoalDriftDetector.detect(
            l1Criteria = criteria,
            missionTexts = miss.take(5) + listOf("闲聊中带过一句合同签订"),
            l1CreatedAt = oldEnough,
            now = now,
        )
        assertFalse(v.drifted)
        assertEquals(5, v.consecutive)
    }
}
