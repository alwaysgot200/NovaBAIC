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

/**
 * Best-effort context window lookup for usage display and auto-compression.
 * Unknown models return null: the UI then shows raw token counts and skips
 * percentage-based decisions.
 */
object ModelContextWindows {

    private val patterns = listOf(
        "fable" to 1_000_000L,
        "sonnet-5" to 1_000_000L,
        "claude" to 200_000L,
        "gemini" to 1_000_000L,
        "deepseek" to 1_000_000L,
        "qwen" to 1_000_000L,
        "kimi-k3" to 1_048_576L,
        "kimi" to 262_144L,
        "moonshot" to 128_000L,
        "glm-5" to 1_000_000L,
        "glm" to 128_000L,
        "gpt-6" to 1_000_000L,
        "gpt-5" to 1_000_000L,
        "gpt-4o" to 128_000L,
        "gpt-4.1" to 128_000L,
        "o3" to 200_000L,
        "o4" to 200_000L,
        "minimax-m3" to 1_000_000L,
        "minimax" to 192_000L,
        "llama" to 128_000L,
        "mistral" to 128_000L,
        "step" to 128_000L,
    )

    fun forModel(model: String): Long? {
        val normalized = model.lowercase()
        return patterns.firstOrNull { normalized.contains(it.first) }?.second
    }
}
