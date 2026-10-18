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

package com.verlintas.baic2.floatball.advisor

import com.verlintas.baic2.floatball.advisor.data.ENGAGEMENT_MUTE
import com.verlintas.baic2.floatball.advisor.data.ENGAGEMENT_PROACTIVE
import com.verlintas.baic2.floatball.advisor.data.ENGAGEMENT_REACTIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngagementRulesTest {

    @Test
    fun muteSilencesIncomingChain() {
        assertFalse(EngagementRules.incomingAllowed(ENGAGEMENT_MUTE))
        assertTrue(EngagementRules.incomingAllowed(ENGAGEMENT_REACTIVE))
        assertTrue(EngagementRules.incomingAllowed(ENGAGEMENT_PROACTIVE))
    }

    @Test
    fun onlyProactiveParticipatesInProactiveCheckpoint() {
        assertTrue(EngagementRules.proactiveEligible(ENGAGEMENT_PROACTIVE))
        assertFalse(EngagementRules.proactiveEligible(ENGAGEMENT_REACTIVE))
        assertFalse(EngagementRules.proactiveEligible(ENGAGEMENT_MUTE))
    }

    @Test
    fun unknownValuesNormalizeToReactive() {
        assertEquals(ENGAGEMENT_PROACTIVE, EngagementRules.normalize(ENGAGEMENT_PROACTIVE))
        assertEquals(ENGAGEMENT_REACTIVE, EngagementRules.normalize(ENGAGEMENT_REACTIVE))
        assertEquals(ENGAGEMENT_MUTE, EngagementRules.normalize(ENGAGEMENT_MUTE))
        assertEquals(ENGAGEMENT_REACTIVE, EngagementRules.normalize(null))
        assertEquals(ENGAGEMENT_REACTIVE, EngagementRules.normalize(""))
        assertEquals(ENGAGEMENT_REACTIVE, EngagementRules.normalize("unknown"))
    }
}
