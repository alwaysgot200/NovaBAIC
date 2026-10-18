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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动维持频率的风格分档（V-C 方案 §4.5 表）：严肃/领导低频、亲熟热情高频、
 * 其余标准；反馈冷淡只降一档（上限语义，不因热度回升）。
 */
class ProactiveStyleTiersTest {

    @Test
    fun conservativeTier_byIdentityStyleOrCloseness() {
        assertEquals(ProactiveStyleTiers.CONSERVATIVE, ProactiveStyleTiers.tierOf("领导", null, null, null))
        assertEquals(ProactiveStyleTiers.CONSERVATIVE, ProactiveStyleTiers.tierOf(null, "严肃", null, null))
        assertEquals(ProactiveStyleTiers.CONSERVATIVE, ProactiveStyleTiers.tierOf(null, null, 0, null))
        assertEquals(ProactiveStyleTiers.CONSERVATIVE, ProactiveStyleTiers.tierOf(null, null, 1, null))
    }

    @Test
    fun frequentTier_byWarmStyleOrCloseBond() {
        assertEquals(ProactiveStyleTiers.FREQUENT, ProactiveStyleTiers.tierOf(null, "热情", null, null))
        assertEquals(ProactiveStyleTiers.FREQUENT, ProactiveStyleTiers.tierOf(null, "幽默", null, null))
        assertEquals(ProactiveStyleTiers.FREQUENT, ProactiveStyleTiers.tierOf(null, null, 2, null))
        assertEquals(ProactiveStyleTiers.FREQUENT, ProactiveStyleTiers.tierOf(null, null, 3, null))
    }

    @Test
    fun standardTier_isDefault() {
        assertEquals(ProactiveStyleTiers.STANDARD, ProactiveStyleTiers.tierOf(null, null, null, null))
        assertEquals(ProactiveStyleTiers.STANDARD, ProactiveStyleTiers.tierOf("客户", "随和", null, null))
    }

    @Test
    fun coldHeat_demotesOneTierOnly() {
        assertEquals(ProactiveStyleTiers.STANDARD, ProactiveStyleTiers.tierOf(null, "热情", null, 10))
        assertEquals(ProactiveStyleTiers.CONSERVATIVE, ProactiveStyleTiers.tierOf(null, null, null, 10))
        // 已最低档不再降（上限语义）。
        assertEquals(ProactiveStyleTiers.CONSERVATIVE, ProactiveStyleTiers.tierOf("领导", "严肃", 0, 10))
    }

    @Test
    fun heatBoundaryAndNeutral() {
        // 30 不算冷（<30 才降）；热度 null 按 NEUTRAL=50 不降。
        assertEquals(ProactiveStyleTiers.FREQUENT, ProactiveStyleTiers.tierOf(null, "热情", null, 30))
        assertEquals(ProactiveStyleTiers.FREQUENT, ProactiveStyleTiers.tierOf(null, "热情", null, null))
    }

    /** 热度高分不抬档：分档是身份上限语义，只降不升。 */
    @Test
    fun hotHeat_neverPromotes() {
        assertEquals(ProactiveStyleTiers.CONSERVATIVE, ProactiveStyleTiers.tierOf("领导", null, null, 95))
    }

    @Test
    fun defaultPolicy_carriesTierValues() {
        val now = 1_000_000L
        val conservative = ProactiveStyleTiers.defaultPolicy(7L, "领导", null, null, null, now)
        assertEquals(7L, conservative.contactId)
        assertEquals(true, conservative.enabled)
        assertEquals(1, conservative.dailyCap)
        assertEquals(48, conservative.minGapHours)
        assertEquals("22:30", conservative.quietStart)
        assertEquals("08:30", conservative.quietEnd)
        assertEquals(true, conservative.preferSmalltalk)
        assertEquals(now, conservative.updatedAt)

        val frequent = ProactiveStyleTiers.defaultPolicy(8L, null, "热情", null, 90, now)
        assertEquals(3, frequent.dailyCap)
        assertEquals(8, frequent.minGapHours)

        val standard = ProactiveStyleTiers.defaultPolicy(9L, null, null, null, null, now)
        assertEquals(2, standard.dailyCap)
        assertEquals(24, standard.minGapHours)
        assertTrue(standard.enabled)
    }
}
