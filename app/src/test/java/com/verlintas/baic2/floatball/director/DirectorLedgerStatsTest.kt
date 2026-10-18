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

package com.verlintas.baic2.floatball.director

import com.verlintas.baic2.floatball.advisor.data.DirectorLogEntity
import com.verlintas.baic2.floatball.advisor.data.ROUTE_BRIEF
import com.verlintas.baic2.floatball.advisor.data.ROUTE_NOOP
import com.verlintas.baic2.floatball.advisor.data.ROUTE_REPLY
import com.verlintas.baic2.floatball.advisor.data.ROUTE_SMALL_TALK
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_INCOMING
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_MANUAL
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_PROACTIVE
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_SCHEDULED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * director_logs 对账统计纯函数（M2-b，方案 §13.1 派发后监视器落点）：
 * 窗口过滤、触发源×路由聚合、四触发源覆盖度。
 */
class DirectorLedgerStatsTest {

    private val now = 1_000_000_000L

    private fun log(
        trigger: String,
        route: String,
        createdAt: Long,
        contactId: Long = 1L,
    ) = DirectorLogEntity(
        contactId = contactId,
        trigger = trigger,
        route = route,
        reason = "test",
        createdAt = createdAt,
    )

    @Test
    fun emptyLedger_emptySummary() {
        val s = DirectorLedgerStats.summarize(emptyList(), now)
        assertEquals(0, s.total)
        assertTrue(s.cells.isEmpty())
        assertTrue(s.triggersSeen.isEmpty())
    }

    @Test
    fun groupsByTriggerAndRoute() {
        val s = DirectorLedgerStats.summarize(
            listOf(
                log(TRIGGER_INCOMING, ROUTE_REPLY, now - 100),
                log(TRIGGER_INCOMING, ROUTE_REPLY, now - 200),
                log(TRIGGER_INCOMING, ROUTE_REPLY, now - 300),
                log(TRIGGER_MANUAL, ROUTE_REPLY, now - 400),
                log(TRIGGER_PROACTIVE, ROUTE_SMALL_TALK, now - 500),
                log(TRIGGER_PROACTIVE, ROUTE_NOOP, now - 600),
                log(TRIGGER_SCHEDULED, ROUTE_BRIEF, now - 700),
            ),
            now,
        )
        assertEquals(7, s.total)
        assertEquals(4, s.triggersSeen.size)
        val incomingReply = s.cells.first { it.trigger == TRIGGER_INCOMING && it.route == ROUTE_REPLY }
        assertEquals(3, incomingReply.count)
        val proactiveSmalltalk = s.cells.first { it.trigger == TRIGGER_PROACTIVE && it.route == ROUTE_SMALL_TALK }
        assertEquals(1, proactiveSmalltalk.count)
    }

    @Test
    fun windowExcludesOlderEntries() {
        val s = DirectorLedgerStats.summarize(
            listOf(
                log(TRIGGER_INCOMING, ROUTE_REPLY, now - DirectorLedgerStats.DEFAULT_WINDOW_MS - 1),
                log(TRIGGER_INCOMING, ROUTE_REPLY, now - DirectorLedgerStats.DEFAULT_WINDOW_MS),
                log(TRIGGER_INCOMING, ROUTE_REPLY, now - 1),
            ),
            now,
        )
        assertEquals(2, s.total)
    }

    @Test
    fun cellsSortedByCountDescending() {
        val s = DirectorLedgerStats.summarize(
            listOf(
                log(TRIGGER_MANUAL, ROUTE_REPLY, now - 100),
                log(TRIGGER_INCOMING, ROUTE_REPLY, now - 200),
                log(TRIGGER_INCOMING, ROUTE_REPLY, now - 300),
            ),
            now,
        )
        assertEquals(ROUTE_REPLY, s.cells.first().route)
        assertEquals(TRIGGER_INCOMING, s.cells.first().trigger)
        assertEquals(2, s.cells.first().count)
    }

    @Test
    fun cellLine_readable() {
        val s = DirectorLedgerStats.summarize(
            listOf(log(TRIGGER_PROACTIVE, ROUTE_SMALL_TALK, now - 100)),
            now,
        )
        assertEquals("proactive/smalltalk=1", s.cellLine())
    }

    @Test
    fun contactIdsDoNotMixCounts() {
        val s = DirectorLedgerStats.summarize(
            listOf(
                log(TRIGGER_PROACTIVE, ROUTE_SMALL_TALK, now - 100, contactId = 1),
                log(TRIGGER_PROACTIVE, ROUTE_SMALL_TALK, now - 200, contactId = 2),
            ),
            now,
        )
        // 对账是全局面分布，不按联系人拆分（频控计数另走 countSince 按人查询）。
        assertEquals(2, s.total)
    }
}
