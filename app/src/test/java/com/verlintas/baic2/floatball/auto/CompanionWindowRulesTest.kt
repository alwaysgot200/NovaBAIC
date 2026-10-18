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

package com.verlintas.baic2.floatball.auto

import com.verlintas.baic2.floatball.advisor.data.ROUTE_NOOP
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_AUTO
import com.verlintas.baic2.floatball.director.DirectorRoutes
import java.util.Calendar
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3-b 陪伴窗口纯函数面（方案 §8.1 W1~W4 / §8.2 拟人节奏 / §13.2 三档互斥）：
 * 随机延迟 8~90s 均匀分布、单窗口上限判定、三档互斥全分支、窗口收尾汇总统计、
 * 夜间静默跨午夜判定（复用 M2-b [DirectorRoutes.inQuietWindow] 口径）。
 * Android 边界（引擎/通知/DB）不在 JVM 单测范围。
 */
class CompanionWindowRulesTest {

    // ── 拟人节奏：随机延迟 8~90s 均匀分布 ──

    @Test
    fun humanPace_allSamplesWithinEightToNinetySeconds() {
        val random = Random(42)
        repeat(2_000) {
            val delay = HumanPace.randomDelayMs(random)
            assertTrue("delay=$delay 越界", delay in HumanPace.MIN_DELAY_MS..HumanPace.MAX_DELAY_MS)
        }
    }

    @Test
    fun humanPace_spreadsAcrossRange_notConstant() {
        val random = Random(7)
        val samples = (0 until 500).map { HumanPace.randomDelayMs(random) }
        // 均匀分布下两端 10% 区间都应有明显命中（纯区间检查，不做统计断言）。
        val lowBand = samples.count { it < HumanPace.MIN_DELAY_MS + 8_200 }
        val highBand = samples.count { it > HumanPace.MAX_DELAY_MS - 8_200 }
        assertTrue("低端样本过少: $lowBand", lowBand > 20)
        assertTrue("高端样本过少: $highBand", highBand > 20)
    }

    @Test
    fun humanPace_deterministicWithSeed() {
        assertEquals(
            HumanPace.randomDelayMs(Random(99)),
            HumanPace.randomDelayMs(Random(99)),
        )
    }

    @Test
    fun humanPace_invertedBoundsCorrected() {
        val random = Random(1)
        repeat(100) {
            val delay = HumanPace.randomDelayMs(random, minMs = 90_000L, maxMs = 8_000L)
            assertTrue(delay in 8_000L..90_000L)
        }
    }

    @Test
    fun humanPace_equalBoundsReturnsThatValue() {
        assertEquals(12_345L, HumanPace.randomDelayMs(Random(3), minMs = 12_345L, maxMs = 12_345L))
    }

    // ── 单窗口发送上限 ──

    @Test
    fun quota_belowLimit_allowed() {
        assertTrue(WindowQuota.sendAllowed(sentCount = 5, maxSends = 6))
    }

    @Test
    fun quota_atLimit_blocked() {
        assertFalse(WindowQuota.sendAllowed(sentCount = 6, maxSends = 6))
    }

    @Test
    fun quota_aboveLimit_blocked() {
        assertFalse(WindowQuota.sendAllowed(sentCount = 9, maxSends = 6))
    }

    @Test
    fun quota_zeroMeansUnlimited() {
        assertTrue(WindowQuota.sendAllowed(sentCount = 999, maxSends = 0))
    }

    // ── 三档互斥：全分支 ──

    private val manual = GearClaim(AdvisorGear.MANUAL_CONTINUOUS, 7L)
    private val companion = GearClaim(AdvisorGear.COMPANION_WINDOW, 7L)
    private val draftSame = GearClaim(AdvisorGear.DRAFT_IN_TRANSIT, 7L)
    private val draftOther = GearClaim(AdvisorGear.DRAFT_IN_TRANSIT, 9L)
    private val none = GearClaim(AdvisorGear.NONE, null)

    @Test
    fun gate_requestedNone_neverBlocks() {
        assertNull(ModeGate.vetoReason(companion, GearClaim(AdvisorGear.NONE, 7L)))
    }

    @Test
    fun gate_currentNone_neverBlocks() {
        assertNull(ModeGate.vetoReason(none, companion))
        assertNull(ModeGate.vetoReason(none, manual))
        assertNull(ModeGate.vetoReason(none, draftSame))
    }

    @Test
    fun gate_sameGear_manual_duplicatesBlocked() {
        assertEquals(
            ModeGate.REASON_MANUAL_ACTIVE,
            ModeGate.vetoReason(manual, GearClaim(AdvisorGear.MANUAL_CONTINUOUS, 9L)),
        )
    }

    @Test
    fun gate_sameGear_companion_duplicatesBlocked() {
        assertEquals(
            ModeGate.REASON_COMPANION_ACTIVE,
            ModeGate.vetoReason(companion, GearClaim(AdvisorGear.COMPANION_WINDOW, 9L)),
        )
    }

    @Test
    fun gate_windowClass_crossGear_blockedGlobally_regardlessOfContact() {
        assertEquals(
            ModeGate.REASON_MANUAL_ACTIVE,
            ModeGate.vetoReason(manual, GearClaim(AdvisorGear.COMPANION_WINDOW, 9L)),
        )
        assertEquals(
            ModeGate.REASON_COMPANION_ACTIVE,
            ModeGate.vetoReason(companion, GearClaim(AdvisorGear.MANUAL_CONTINUOUS, 9L)),
        )
    }

    @Test
    fun gate_companionOccupied_draftRequestSameContact_blocked() {
        assertEquals(
            ModeGate.REASON_CONTACT_OCCUPIED,
            ModeGate.vetoReason(companion, draftSame),
        )
    }

    @Test
    fun gate_manualOccupied_draftRequestSameContact_blocked() {
        assertEquals(ModeGate.REASON_CONTACT_OCCUPIED, ModeGate.vetoReason(manual, draftSame))
    }

    @Test
    fun gate_draftInTransit_blocksWindowOnSameContact() {
        assertEquals(
            ModeGate.REASON_DRAFT_TRANSIT,
            ModeGate.vetoReason(draftSame, companion),
        )
        assertEquals(ModeGate.REASON_DRAFT_TRANSIT, ModeGate.vetoReason(draftSame, manual))
    }

    @Test
    fun gate_draftInTransit_sameContactDuplicate_blocked() {
        assertEquals(ModeGate.REASON_DRAFT_TRANSIT, ModeGate.vetoReason(draftSame, draftSame))
    }

    @Test
    fun gate_draftInTransit_differentContacts_allowed() {
        assertNull(ModeGate.vetoReason(draftSame, draftOther))
    }

    @Test
    fun gate_draftInTransit_unownedSessions_treatedAsSameContact() {
        val unowned = GearClaim(AdvisorGear.DRAFT_IN_TRANSIT, null)
        assertEquals(ModeGate.REASON_DRAFT_TRANSIT, ModeGate.vetoReason(unowned, unowned))
    }

    @Test
    fun gate_draftInTransit_windowOnOtherContact_allowed() {
        assertNull(ModeGate.vetoReason(draftOther, GearClaim(AdvisorGear.COMPANION_WINDOW, 7L)))
    }

    // ── 窗口收尾汇总统计 ──

    @Test
    fun tally_incrementsAllBuckets() {
        val tally = WindowTallyAccumulator()
        tally.onDraft()
        tally.onDraft()
        tally.onDraft()
        tally.onSent()
        tally.onSent()
        tally.onBlocked()
        tally.onDegraded()
        assertEquals(WindowTally(drafts = 3, sent = 2, blocked = 1, degraded = 1), tally.snapshot())
    }

    @Test
    fun tally_resetClearsForNextWindow() {
        val tally = WindowTallyAccumulator()
        tally.onDraft()
        tally.onSent()
        tally.reset()
        assertEquals(WindowTally(), tally.snapshot())
    }

    @Test
    fun tally_snapshotIsIsolatedCopy() {
        val tally = WindowTallyAccumulator()
        val before = tally.snapshot()
        tally.onDraft()
        assertEquals(0, before.drafts)
        assertEquals(1, tally.snapshot().drafts)
    }

    // ── 档位生命周期台账（方案 §12 director_logs 记录档位，F5）──

    @Test
    fun ledger_rowComposesAutoTriggerAndNoopRoute() {
        val row = CompanionLedgerEvents.row(contactId = 7L, reason = CompanionLedgerEvents.START, nowMs = 123L)
        assertEquals(7L, row.contactId)
        assertEquals(TRIGGER_AUTO, row.trigger)
        assertEquals(ROUTE_NOOP, row.route)
        assertEquals("companion_start", row.reason)
        assertEquals(123L, row.createdAt)
        // 生命周期事件非回合决策：无回合锚点、无重规划信号。
        assertEquals(null, row.turnMessageId)
        assertFalse(row.replanHint)
    }

    @Test
    fun ledger_endReasonCarriesWhy() {
        assertEquals(
            "companion_end:window_expired",
            CompanionLedgerEvents.endReason("window_expired"),
        )
        assertEquals(
            "companion_end:service_stop",
            CompanionLedgerEvents.endReason("service_stop"),
        )
    }

    @Test
    fun ledger_vetoedReasonCarriesModeGateCode() {
        assertEquals(
            "companion_vetoed:gear_manual_continuous_active",
            CompanionLedgerEvents.vetoedReason(ModeGate.REASON_MANUAL_ACTIVE),
        )
        assertEquals(
            "companion_vetoed:gear_draft_in_transit",
            CompanionLedgerEvents.vetoedReason(ModeGate.REASON_DRAFT_TRANSIT),
        )
    }

    @Test
    fun ledger_rowForVetoUsesComposedReason() {
        val reason = CompanionLedgerEvents.vetoedReason(ModeGate.REASON_COMPANION_ACTIVE)
        val row = CompanionLedgerEvents.row(9L, reason, 456L)
        assertEquals("companion_vetoed:gear_companion_active", row.reason)
        assertEquals(9L, row.contactId)
        assertEquals(TRIGGER_AUTO, row.trigger)
    }

    // ── 夜间静默：复用 M2-b quiet 口径（跨午夜）──

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun quietWindow_defaultRange_crossMidnight_lateNightSilent() {
        // 22:30~08:30 跨午夜：深夜 23:00 与凌晨 08:00 都在静默内。
        assertTrue(DirectorRoutes.inQuietWindow(at(23, 0), "22:30", "08:30"))
        assertTrue(DirectorRoutes.inQuietWindow(at(8, 0), "22:30", "08:30"))
        assertTrue(DirectorRoutes.inQuietWindow(at(0, 30), "22:30", "08:30"))
        // 边界：起点/终点本身计为静默（与 DirectorRoutes 闭区间口径一致）。
        assertTrue(DirectorRoutes.inQuietWindow(at(22, 30), "22:30", "08:30"))
        assertTrue(DirectorRoutes.inQuietWindow(at(8, 30), "22:30", "08:30"))
    }

    @Test
    fun quietWindow_defaultRange_daytimeActive() {
        assertFalse(DirectorRoutes.inQuietWindow(at(8, 31), "22:30", "08:30"))
        assertFalse(DirectorRoutes.inQuietWindow(at(22, 29), "22:30", "08:30"))
        assertFalse(DirectorRoutes.inQuietWindow(at(12, 0), "22:30", "08:30"))
    }

    @Test
    fun quietWindow_sameStartEnd_meansNoSilence() {
        assertFalse(DirectorRoutes.inQuietWindow(at(23, 0), "22:30", "22:30"))
    }

    @Test
    fun quietWindow_invalidTime_failsOpen() {
        assertFalse(DirectorRoutes.inQuietWindow(at(23, 0), "abc", "08:30"))
        assertFalse(DirectorRoutes.inQuietWindow(at(23, 0), "25:00", "08:30"))
    }

    @Test
    fun quietWindow_nonCrossingRange_works() {
        assertTrue(DirectorRoutes.inQuietWindow(at(13, 30), "13:00", "14:00"))
        assertFalse(DirectorRoutes.inQuietWindow(at(14, 1), "13:00", "14:00"))
    }
}
