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

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROACTIVE 六分支频控全分支（M2-b/M2-c，方案 §5.5/§7.3）：判定顺序固定
 * disabled → quiet → incoming_recent → cap → gap → pass；静默时段覆盖跨午夜
 * 与两端边界；incoming 优先窗口（§7.3「对方先开口绝不抢话」）取 minGapHours
 * 同量级；策略未接入（null）保持 M0 台账口径（proactive_not_enabled_m0）。
 */
class ProactiveRoutesTest {

    /** 本机时区当日指定钟点的时间戳（与 inQuietWindow 同一时区口径）。 */
    private fun at(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun policy(
        enabled: Boolean = true,
        dailyCap: Int = 1,
        minGapHours: Int = 48,
        quietStart: String = "22:30",
        quietEnd: String = "08:30",
    ) = ProactivePolicySnapshot(enabled, dailyCap, minGapHours, quietStart, quietEnd)

    private fun input(
        nowMs: Long = at(12, 0),
        policySnapshot: ProactivePolicySnapshot? = policy(),
        countToday: Int = 0,
        lastProactiveAtMs: Long? = null,
        lastIncomingAtMs: Long? = null,
        requestSmalltalk: Boolean = false,
        failStreak: Int = 0,
    ) = DirectorInput(
        trigger = DirectorTrigger.PROACTIVE,
        requestSmalltalk = requestSmalltalk,
        failStreak = failStreak,
        nowMs = nowMs,
        proactivePolicy = policySnapshot,
        proactiveCountToday = countToday,
        lastProactiveAtMs = lastProactiveAtMs,
        lastIncomingAtMs = lastIncomingAtMs,
    )

    // ── M0 兼容 ──────────────────────────────────────────────────────────

    @Test
    fun policyNull_keepsM0Noop() {
        val d = DirectorRoutes.decide(input(policySnapshot = null))
        assertEquals(DirectorRoute.NOOP, d.route)
        assertEquals("proactive_not_enabled_m0", d.reason)
    }

    // ── 分支一：disabled ────────────────────────────────────────────────

    @Test
    fun disabled_noop() {
        val d = DirectorRoutes.decide(input(policySnapshot = policy(enabled = false)))
        assertEquals(DirectorRoute.NOOP, d.route)
        assertEquals("proactive_disabled", d.reason)
    }

    // ── 分支二：quiet（跨午夜 22:30~08:30）─────────────────────────────

    @Test
    fun quiet_lateNight() {
        val d = DirectorRoutes.decide(input(nowMs = at(23, 0)))
        assertEquals(DirectorRoute.NOOP, d.route)
        assertEquals("quiet_noop", d.reason)
    }

    @Test
    fun quiet_beforeDawn() {
        val d = DirectorRoutes.decide(input(nowMs = at(3, 0)))
        assertEquals("quiet_noop", d.reason)
    }

    @Test
    fun quiet_startBoundaryInclusive() {
        assertEquals("quiet_noop", DirectorRoutes.decide(input(nowMs = at(22, 30))).reason)
    }

    @Test
    fun quiet_endBoundaryInclusive() {
        assertEquals("quiet_noop", DirectorRoutes.decide(input(nowMs = at(8, 30))).reason)
    }

    @Test
    fun notQuiet_afterEndAndBeforeStart() {
        assertEquals("proactive_smalltalk", DirectorRoutes.decide(input(nowMs = at(8, 31))).reason)
        assertEquals("proactive_smalltalk", DirectorRoutes.decide(input(nowMs = at(22, 29))).reason)
    }

    @Test
    fun quiet_nonCrossingWindow() {
        val p = policy(quietStart = "01:00", quietEnd = "06:00")
        assertEquals("quiet_noop", DirectorRoutes.decide(input(nowMs = at(3, 0), policySnapshot = p)).reason)
        assertEquals("proactive_smalltalk", DirectorRoutes.decide(input(nowMs = at(6, 1), policySnapshot = p)).reason)
    }

    @Test
    fun quiet_malformedTimesFailOpen() {
        val p = policy(quietStart = "bad", quietEnd = "08:30")
        assertEquals("proactive_smalltalk", DirectorRoutes.decide(input(nowMs = at(23, 0), policySnapshot = p)).reason)
    }

    @Test
    fun quiet_takesPrecedenceOverCap() {
        val d = DirectorRoutes.decide(input(nowMs = at(23, 0), countToday = 5))
        assertEquals("quiet_noop", d.reason)
    }

    // ── 分支二·五：incoming_recent（§7.3 incoming 永远优先于 proactive）──

    @Test
    fun incomingRecent_noop() {
        val now = at(12, 0)
        val d = DirectorRoutes.decide(input(nowMs = now, lastIncomingAtMs = now - DirectorRoutes.HOUR_MS))
        assertEquals(DirectorRoute.NOOP, d.route)
        assertEquals("incoming_recent_noop", d.reason)
    }

    @Test
    fun incomingOlderThanGap_passes() {
        val now = at(12, 0)
        val d = DirectorRoutes.decide(
            input(nowMs = now, lastIncomingAtMs = now - 49 * DirectorRoutes.HOUR_MS),
        )
        assertEquals(DirectorRoute.SMALL_TALK, d.route)
        assertEquals("proactive_smalltalk", d.reason)
    }

    @Test
    fun incomingAtGapBoundary_passes() {
        // 窗口边界与 min_gap 同口径：严格小于拦截，恰好到达即放行。
        val now = at(12, 0)
        val d = DirectorRoutes.decide(
            input(nowMs = now, lastIncomingAtMs = now - 48 * DirectorRoutes.HOUR_MS),
        )
        assertEquals("proactive_smalltalk", d.reason)
    }

    @Test
    fun incomingNull_lastIncomingUnknownTolerated() {
        val d = DirectorRoutes.decide(input(lastIncomingAtMs = null))
        assertEquals("proactive_smalltalk", d.reason)
    }

    @Test
    fun incomingFutureClockSkew_treatedAsRecent() {
        val now = at(12, 0)
        val d = DirectorRoutes.decide(input(nowMs = now, lastIncomingAtMs = now + DirectorRoutes.HOUR_MS))
        assertEquals("incoming_recent_noop", d.reason)
    }

    @Test
    fun incomingRecent_usesPolicyGapWindow() {
        // 窗口随策略 minGapHours 收缩：24h 策略下来信 25h 前即可主动。
        val now = at(12, 0)
        val p = policy(minGapHours = 24)
        assertEquals(
            "proactive_smalltalk",
            DirectorRoutes.decide(
                input(nowMs = now, policySnapshot = p, lastIncomingAtMs = now - 25 * DirectorRoutes.HOUR_MS),
            ).reason,
        )
        assertEquals(
            "incoming_recent_noop",
            DirectorRoutes.decide(
                input(nowMs = now, policySnapshot = p, lastIncomingAtMs = now - 23 * DirectorRoutes.HOUR_MS),
            ).reason,
        )
    }

    @Test
    fun incomingRecent_takesPrecedenceOverCap() {
        val now = at(12, 0)
        val d = DirectorRoutes.decide(
            input(nowMs = now, lastIncomingAtMs = now - DirectorRoutes.HOUR_MS, countToday = 3),
        )
        assertEquals("incoming_recent_noop", d.reason)
    }

    @Test
    fun incomingRecent_takesPrecedenceOverGap() {
        val now = at(12, 0)
        val d = DirectorRoutes.decide(
            input(
                nowMs = now,
                lastIncomingAtMs = now - DirectorRoutes.HOUR_MS,
                lastProactiveAtMs = now - DirectorRoutes.HOUR_MS,
            ),
        )
        assertEquals("incoming_recent_noop", d.reason)
    }

    @Test
    fun quiet_takesPrecedenceOverIncomingRecent() {
        val now = at(23, 0)
        val d = DirectorRoutes.decide(
            input(nowMs = now, lastIncomingAtMs = now - DirectorRoutes.HOUR_MS),
        )
        assertEquals("quiet_noop", d.reason)
    }

    @Test
    fun incomingOld_proactiveStillRespectsOwnGap() {
        // 来信已出窗口、但我们上次主动更近：仍按自身频控拦。
        val now = at(12, 0)
        val d = DirectorRoutes.decide(
            input(
                nowMs = now,
                lastIncomingAtMs = now - 72 * DirectorRoutes.HOUR_MS,
                lastProactiveAtMs = now - 24 * DirectorRoutes.HOUR_MS,
            ),
        )
        assertEquals("min_gap_not_met", d.reason)
    }

    // ── 分支三：dailyCap ────────────────────────────────────────────────

    @Test
    fun capReached_noop() {
        val d = DirectorRoutes.decide(input(countToday = 1, policySnapshot = policy(dailyCap = 1)))
        assertEquals(DirectorRoute.NOOP, d.route)
        assertEquals("daily_cap_reached", d.reason)
    }

    @Test
    fun capBelowAllowed() {
        val d = DirectorRoutes.decide(input(countToday = 1, policySnapshot = policy(dailyCap = 2)))
        assertEquals(DirectorRoute.SMALL_TALK, d.route)
    }

    // ── 分支四：minGap ─────────────────────────────────────────────────

    @Test
    fun gapNotMet_noop() {
        val now = at(12, 0)
        val d = DirectorRoutes.decide(input(nowMs = now, lastProactiveAtMs = now - 23 * DirectorRoutes.HOUR_MS))
        assertEquals(DirectorRoute.NOOP, d.route)
        assertEquals("min_gap_not_met", d.reason)
    }

    @Test
    fun gapExactlyMet_passes() {
        val now = at(12, 0)
        val d = DirectorRoutes.decide(
            input(nowMs = now, lastProactiveAtMs = now - 48 * DirectorRoutes.HOUR_MS),
        )
        assertEquals(DirectorRoute.SMALL_TALK, d.route)
        assertEquals("proactive_smalltalk", d.reason)
    }

    @Test
    fun gapNull_lastProactiveUnknownTolerated() {
        val d = DirectorRoutes.decide(input(lastProactiveAtMs = null))
        assertEquals(DirectorRoute.SMALL_TALK, d.route)
    }

    @Test
    fun capTakesPrecedenceOverGap() {
        val now = at(12, 0)
        val d = DirectorRoutes.decide(
            input(
                nowMs = now,
                countToday = 3,
                lastProactiveAtMs = now - DirectorRoutes.HOUR_MS,
                policySnapshot = policy(dailyCap = 1),
            ),
        )
        assertEquals("daily_cap_reached", d.reason)
    }

    // ── 分支五：pass ────────────────────────────────────────────────────

    @Test
    fun allPassed_routesSmallTalk() {
        val d = DirectorRoutes.decide(input(nowMs = at(12, 0)))
        assertEquals(DirectorRoute.SMALL_TALK, d.route)
        assertEquals("proactive_smalltalk", d.reason)
        assertFalse(d.replanHint)
    }

    @Test
    fun requestSmalltalk_doesNotAffectProactive() {
        val d = DirectorRoutes.decide(input(requestSmalltalk = true))
        assertEquals("proactive_smalltalk", d.reason)
    }

    @Test
    fun replanHint_stillMarkedOnProactivePass() {
        val d = DirectorRoutes.decide(input(failStreak = 3))
        assertEquals(DirectorRoute.SMALL_TALK, d.route)
        assertTrue(d.replanHint)
    }

    // ── 静默窗口纯函数补充 ─────────────────────────────────────────────

    @Test
    fun inQuietWindow_sameStartEndMeansNoQuiet() {
        assertFalse(DirectorRoutes.inQuietWindow(at(23, 0), "22:30", "22:30"))
    }
}
