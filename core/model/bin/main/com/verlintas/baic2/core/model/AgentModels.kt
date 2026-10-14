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

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderId {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GEMINI,
}

/**
 * Persisted agent configuration. The API key is never part of this model:
 * it is stored separately in encrypted form and only decrypted into
 * [ProviderConfig] at request time.
 */
@Serializable
data class Agent(
    val id: Long = 0L,
    val name: String,
    val provider: ProviderId = ProviderId.OPENAI_COMPATIBLE,
    val baseUrl: String,
    val model: String,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val reasoning: Boolean = false,
    val systemPrompt: String = "",
    val isDefault: Boolean = false,
)

/**
 * Runtime configuration including the plaintext API key. Never persisted,
 * never logged.
 */
data class ProviderConfig(
    val provider: ProviderId,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val reasoning: Boolean = false,
)
