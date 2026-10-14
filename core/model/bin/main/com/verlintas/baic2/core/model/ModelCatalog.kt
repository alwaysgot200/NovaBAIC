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
 * Curated metadata for well-known models. It gives the agent wizard sensible
 * defaults (model, temperature, max tokens, reasoning) and the context meter an
 * accurate window, without trusting whatever the gateway reports.
 *
 * Unknown models fall back to [ModelContextWindows]' pattern matching.
 */
data class ModelEntry(
    val id: String,
    val label: String,
    val provider: ProviderId,
    val family: String,
    val contextWindow: Long,
    val temperature: Double = 0.7,
    val maxTokens: Int = 4_096,
    val supportsReasoning: Boolean = false,
)

object ModelCatalog {

    val entries: List<ModelEntry> = listOf(
        // OpenAI (GPT-6 / GPT-5.6 tiers; 1M context)
        ModelEntry(
            "gpt-6-astra",
            "GPT-6 Astra",
            ProviderId.OPENAI_COMPATIBLE,
            "openai",
            1_000_000,
            supportsReasoning = true,
        ),
        ModelEntry(
            "gpt-5.6-sol",
            "GPT-5.6 Sol",
            ProviderId.OPENAI_COMPATIBLE,
            "openai",
            1_000_000,
            supportsReasoning = true,
        ),
        ModelEntry(
            "gpt-5.6-terra",
            "GPT-5.6 Terra",
            ProviderId.OPENAI_COMPATIBLE,
            "openai",
            1_000_000,
            supportsReasoning = true,
        ),
        ModelEntry(
            "gpt-5.6-luna",
            "GPT-5.6 Luna",
            ProviderId.OPENAI_COMPATIBLE,
            "openai",
            1_000_000,
            supportsReasoning = true,
        ),

        // Anthropic (Fable 5.1 / Opus 5.5 / Sonnet 5.5 / Haiku 4.5)
        ModelEntry(
            "claude-fable-5-1",
            "Claude Fable 5.1",
            ProviderId.ANTHROPIC,
            "anthropic",
            1_000_000,
            maxTokens = 8_192,
            supportsReasoning = true,
        ),
        ModelEntry(
            "claude-opus-5-5",
            "Claude Opus 5.5",
            ProviderId.ANTHROPIC,
            "anthropic",
            200_000,
            maxTokens = 8_192,
            supportsReasoning = true,
        ),
        ModelEntry(
            "claude-sonnet-5-5",
            "Claude Sonnet 5.5",
            ProviderId.ANTHROPIC,
            "anthropic",
            1_000_000,
            maxTokens = 8_192,
            supportsReasoning = true,
        ),
        ModelEntry("claude-haiku-4-5", "Claude Haiku 4.5", ProviderId.ANTHROPIC, "anthropic", 200_000, maxTokens = 8_192),

        // Gemini (3.x generation)
        ModelEntry("gemini-3.8-flash", "Gemini 3.8 Flash", ProviderId.GEMINI, "gemini", 1_048_576, supportsReasoning = true),
        ModelEntry("gemini-3.1-pro", "Gemini 3.1 Pro", ProviderId.GEMINI, "gemini", 1_048_576, supportsReasoning = true),

        // DeepSeek (V4.x; thinking mode defaults on)
        ModelEntry(
            "deepseek-flash",
            "DeepSeek Flash",
            ProviderId.OPENAI_COMPATIBLE,
            "deepseek",
            1_000_000,
            maxTokens = 64_000,
            supportsReasoning = true,
        ),
        ModelEntry(
            "deepseek-v4-pro",
            "DeepSeek V4 Pro",
            ProviderId.OPENAI_COMPATIBLE,
            "deepseek",
            1_000_000,
            maxTokens = 64_000,
            supportsReasoning = true,
        ),

        // Qwen (all 1M)
        ModelEntry("qwen3.8-max", "Qwen3.8 Max", ProviderId.OPENAI_COMPATIBLE, "qwen", 1_000_000, maxTokens = 8_192, supportsReasoning = true),
        ModelEntry("qwen3.7-plus", "Qwen3.7 Plus", ProviderId.OPENAI_COMPATIBLE, "qwen", 1_000_000, maxTokens = 8_192, supportsReasoning = true),
        ModelEntry("qwen3.8-flash", "Qwen3.8 Flash", ProviderId.OPENAI_COMPATIBLE, "qwen", 1_000_000, maxTokens = 8_192, supportsReasoning = true),

        // Moonshot / Kimi
        ModelEntry(
            "kimi-k3",
            "Kimi K3",
            ProviderId.OPENAI_COMPATIBLE,
            "moonshot",
            1_048_576,
            maxTokens = 65_536,
            supportsReasoning = true,
        ),
        ModelEntry("kimi-k2.7-code", "Kimi K2.7 Code", ProviderId.OPENAI_COMPATIBLE, "moonshot", 262_144, supportsReasoning = true),
        ModelEntry("kimi-k2.6", "Kimi K2.6", ProviderId.OPENAI_COMPATIBLE, "moonshot", 262_144, supportsReasoning = true),

        // GLM (5.x; reasoning always on)
        ModelEntry(
            "glm-5.3",
            "GLM-5.3",
            ProviderId.OPENAI_COMPATIBLE,
            "glm",
            1_000_000,
            maxTokens = 65_536,
            supportsReasoning = true,
        ),
        ModelEntry(
            "glm-5.2",
            "GLM-5.2",
            ProviderId.OPENAI_COMPATIBLE,
            "glm",
            1_000_000,
            maxTokens = 65_536,
            supportsReasoning = true,
        ),

        // MiniMax (M-series)
        ModelEntry("minimax-m3", "MiniMax M3", ProviderId.OPENAI_COMPATIBLE, "minimax", 1_000_000, supportsReasoning = true),
        ModelEntry("minimax-m2.7", "MiniMax M2.7", ProviderId.OPENAI_COMPATIBLE, "minimax", 192_000, supportsReasoning = true),
    )

    fun entryFor(provider: ProviderId, model: String): ModelEntry? {
        val normalized = model.lowercase()
        if (normalized.isBlank()) return null
        entries.firstOrNull { it.provider == provider && it.id == normalized }?.let { return it }
        entries.firstOrNull { it.provider == provider && normalized.startsWith(it.id) }?.let { return it }
        return entries.firstOrNull { it.id == normalized }
            ?: entries.firstOrNull { normalized.startsWith(it.id) }
    }

    fun modelsFor(provider: ProviderId, family: String? = null): List<ModelEntry> {
        val pool = entries.filter { it.provider == provider }
        if (family.isNullOrBlank()) return pool
        return pool.filter { it.family == family.lowercase() }
    }
}
