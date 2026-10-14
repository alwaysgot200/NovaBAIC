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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolTrustTest {

    @Test
    fun markerRoundTrips() {
        val wrapped = ToolTrust.wrap("page text")
        assertTrue(ToolTrust.isUntrusted(wrapped))
        assertFalse(ToolTrust.isUntrusted("ordinary message"))
    }

    @Test
    fun windowScanSurvivesPrefixesAndSummaryCarriers() {
        val aged = ChatMessage(
            role = ChatRole.TOOL,
            content = "[tool result from 3 h ago - re-check before relying on it]\n" +
                ToolTrust.wrap("page"),
        )
        val summary = ChatMessage(role = ChatRole.ASSISTANT, content = ToolTrust.wrap("summary"))
        assertTrue(
            ToolTrust.windowIsTainted(
                listOf(ChatMessage(role = ChatRole.USER, content = "hi"), aged),
            ),
        )
        assertTrue(ToolTrust.windowIsTainted(listOf(summary)))
        assertFalse(
            ToolTrust.windowIsTainted(listOf(ChatMessage(role = ChatRole.USER, content = "hi"))),
        )
    }
}
