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
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelContextWindowsTest {

    @Test
    fun knownFamiliesResolve() {
        assertEquals(200_000L, ModelContextWindows.forModel("claude-sonnet-4-5"))
        assertEquals(200_000L, ModelContextWindows.forModel("claude-opus-5-5"))
        assertEquals(1_000_000L, ModelContextWindows.forModel("claude-sonnet-5-5"))
        assertEquals(1_000_000L, ModelContextWindows.forModel("claude-fable-5-1"))
        assertEquals(1_000_000L, ModelContextWindows.forModel("gemini-2.5-flash"))
        assertEquals(1_000_000L, ModelContextWindows.forModel("gpt-6-astra"))
        assertEquals(128_000L, ModelContextWindows.forModel("gpt-4o-mini"))
        assertEquals(1_000_000L, ModelContextWindows.forModel("qwen3.7-plus"))
        assertEquals(1_048_576L, ModelContextWindows.forModel("kimi-k3"))
        assertEquals(262_144L, ModelContextWindows.forModel("kimi-k2.6"))
        assertEquals(1_000_000L, ModelContextWindows.forModel("glm-5.3"))
        assertEquals(128_000L, ModelContextWindows.forModel("glm-4.7"))
        assertEquals(1_000_000L, ModelContextWindows.forModel("MiniMax-M3"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(200_000L, ModelContextWindows.forModel("Claude-Opus"))
    }

    @Test
    fun unknownModelReturnsNull() {
        assertNull(ModelContextWindows.forModel("my-local-model"))
    }
}
