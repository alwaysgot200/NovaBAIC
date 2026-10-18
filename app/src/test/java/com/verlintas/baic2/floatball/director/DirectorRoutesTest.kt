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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DirectorRoutes 路由规则表全分支（方案 §10 M0 验收：触发源 A/D 两路路由正确
 * + replan 阈值边界 + F7 action_hint 旁路阈值/独立性 + lastIncomingAt 为 null 的容错）。
 */
class DirectorRoutesTest {

    private fun input(
        trigger: DirectorTrigger,
        failStreak: Int = 0,
        retreats: Int = 0,
        dwellDays: Long = 0,
        lastIncomingAtMs: Long? = 1_000L,
        requestSmalltalk: Boolean = false,
    ) = DirectorInput(
        trigger = trigger,
        requestSmalltalk = requestSmalltalk,
        failStreak = failStreak,
        consecutiveRetreats = retreats,
        stageDwellDays = dwellDays,
        lastIncomingAtMs = lastIncomingAtMs,
        nowMs = 2_000L,
    )

    // ── 四触发源主路 ──────────────────────────────────────────────────────

    @Test
    fun incoming_defaultsToReply() {
        val d = DirectorRoutes.decide(input(DirectorTrigger.INCOMING))
        assertEquals(DirectorRoute.REPLY, d.route)
        assertEquals("incoming_default", d.reason)
        assertFalse(d.replanHint)
    }

    @Test
    fun manual_defaultsToReply() {
        val d = DirectorRoutes.decide(input(DirectorTrigger.MANUAL))
        assertEquals(DirectorRoute.REPLY, d.route)
        assertEquals("manual_default", d.reason)
        assertFalse(d.replanHint)
    }

    // ── M1：分屏「来点闲聊」手动保温入口 ─────────────────────────────────

    @Test
    fun manualSmalltalkEntry_routesSmallTalk() {
        val d = DirectorRoutes.decide(input(DirectorTrigger.MANUAL, requestSmalltalk = true))
        assertEquals(DirectorRoute.SMALL_TALK, d.route)
        assertEquals("manual_smalltalk_entry", d.reason)
        assertFalse(d.replanHint)
    }

    @Test
    fun requestSmalltalk_onlyAffectsManualTrigger() {
        val d = DirectorRoutes.decide(input(DirectorTrigger.INCOMING, requestSmalltalk = true))
        assertEquals(DirectorRoute.REPLY, d.route)
        assertEquals("incoming_default", d.reason)
    }

    @Test
    fun proactive_noopUntilM2() {
        val d = DirectorRoutes.decide(input(DirectorTrigger.PROACTIVE))
        assertEquals(DirectorRoute.NOOP, d.route)
        assertEquals("proactive_not_enabled_m0", d.reason)
    }

    @Test
    fun scheduled_brief() {
        val d = DirectorRoutes.decide(input(DirectorTrigger.SCHEDULED))
        assertEquals(DirectorRoute.BRIEF, d.route)
        assertEquals("scheduled_brief", d.reason)
    }

    // ── replan 信号阈值边界（观察性：不改路由） ──────────────────────────

    @Test
    fun failStreakAtThree_marksReplanButKeepsReply() {
        val d = DirectorRoutes.decide(input(DirectorTrigger.INCOMING, failStreak = 3))
        assertTrue(d.replanHint)
        assertEquals(DirectorRoute.REPLY, d.route)
        assertEquals("incoming_default", d.reason)
    }

    @Test
    fun failStreakBelowThree_noReplan() {
        assertFalse(DirectorRoutes.decide(input(DirectorTrigger.INCOMING, failStreak = 2)).replanHint)
    }

    @Test
    fun consecutiveRetreatsAtTwo_marksReplanButKeepsReply() {
        val d = DirectorRoutes.decide(input(DirectorTrigger.MANUAL, retreats = 2))
        assertTrue(d.replanHint)
        assertEquals(DirectorRoute.REPLY, d.route)
        assertEquals("manual_default", d.reason)
    }

    @Test
    fun consecutiveRetreatsBelowTwo_noReplan() {
        assertFalse(DirectorRoutes.decide(input(DirectorTrigger.MANUAL, retreats = 1)).replanHint)
    }

    @Test
    fun bothBelowThresholds_noReplan() {
        val d = DirectorRoutes.decide(input(DirectorTrigger.INCOMING, failStreak = 2, retreats = 1))
        assertFalse(d.replanHint)
    }

    @Test
    fun replanSignal_appliesToEveryTrigger() {
        for (trigger in DirectorTrigger.values()) {
            assertTrue(
                "failStreak>=3 应在 $trigger 上标记 replan",
                DirectorRoutes.decide(input(trigger, failStreak = 3)).replanHint,
            )
            assertTrue(
                "retreats>=2 应在 $trigger 上标记 replan",
                DirectorRoutes.decide(input(trigger, retreats = 2)).replanHint,
            )
        }
    }

    // ── F7 action_hint 旁路（方案 §5.6：只落台账不占路由，阈值 14 天） ──────

    @Test
    fun stageDwellAtFourteen_marksActionHintButKeepsRoute() {
        val d = DirectorRoutes.decide(input(DirectorTrigger.INCOMING, dwellDays = 14))
        assertTrue(d.actionHint)
        assertEquals(DirectorRoute.REPLY, d.route)
        assertEquals("incoming_default", d.reason)
    }

    @Test
    fun stageDwellBelowThreshold_noActionHint() {
        assertFalse(DirectorRoutes.decide(input(DirectorTrigger.INCOMING, dwellDays = 13)).actionHint)
    }

    /** replan 管话术路线、actionHint 管口头之外的推进（§5.6 旁路），两信号互不牵连。 */
    @Test
    fun actionHint_independentOfReplanHint() {
        val failOnly = DirectorRoutes.decide(input(DirectorTrigger.INCOMING, failStreak = 3))
        assertTrue(failOnly.replanHint)
        assertFalse(failOnly.actionHint)
        val dwellOnly = DirectorRoutes.decide(input(DirectorTrigger.INCOMING, dwellDays = 14))
        assertFalse(dwellOnly.replanHint)
        assertTrue(dwellOnly.actionHint)
    }

    /** 旁路信号对所有触发源一视同仁（落库不挑路），与 replanSignal 的全源覆盖口径对称。 */
    @Test
    fun actionHint_appliesToEveryTrigger() {
        for (trigger in DirectorTrigger.values()) {
            assertTrue(
                "驻留≥14 天应在 $trigger 上标记 actionHint",
                DirectorRoutes.decide(input(trigger, dwellDays = 14)).actionHint,
            )
        }
    }

    // ── 容错 ─────────────────────────────────────────────────────────────

    @Test
    fun nullLastIncomingAt_tolerated() {
        val d = DirectorRoutes.decide(input(DirectorTrigger.INCOMING, lastIncomingAtMs = null))
        assertEquals(DirectorRoute.REPLY, d.route)
        assertEquals("incoming_default", d.reason)
        assertFalse(d.replanHint)
    }
}
