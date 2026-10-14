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
 * Cheap, provider-independent token estimate used for the context counter when
 * the provider does not report usage (most OpenAI-compatible streaming
 * endpoints) and for auto-compression thresholds.
 *
 * Rules of thumb: CJK characters are roughly one token each, while Latin text
 * averages ~4 characters per token. Every wire object carries framing overhead.
 */
object TokenEstimator {

    private const val MESSAGE_OVERHEAD = 4L
    private const val TOOL_OVERHEAD = 8L
    private const val ATTACHMENT_OVERHEAD = 16L

    /** Rough per-image cost; only the latest user turn ever sends images. */
    private const val IMAGE_TOKENS = 800L

    fun estimate(
        messages: List<ChatMessage>,
        systemPrompt: String = "",
        toolSpecs: List<ToolSpec> = emptyList(),
        streamingText: String = "",
    ): Long {
        var total = if (systemPrompt.isBlank()) 0L else text(systemPrompt) + MESSAGE_OVERHEAD
        // Attachments only travel with the newest user turn (older ones are
        // stripped before sending), so only that turn pays for them.
        val attachmentCarrier = messages.indexOfLast { it.role == ChatRole.USER }
        messages.forEachIndexed { index, message ->
            total += message(message, includeAttachments = index == attachmentCarrier)
        }
        toolSpecs.forEach { spec ->
            total += TOOL_OVERHEAD + text(spec.name) + text(spec.description) + text(spec.parametersJson)
        }
        total += text(streamingText)
        return total
    }

    fun message(message: ChatMessage, includeAttachments: Boolean = true): Long {
        var total = MESSAGE_OVERHEAD + text(message.content)
        message.thinking?.let { total += text(it) }
        message.toolCalls.forEach { call ->
            // Tool results are counted once, via the persisted TOOL messages.
            total += TOOL_OVERHEAD + text(call.name) + text(call.argumentsJson)
        }
        if (includeAttachments) {
            message.attachments.forEach { attachment ->
                total += ATTACHMENT_OVERHEAD + text(attachment.fileName.orEmpty()) + text(attachment.text.orEmpty())
                if (attachment.kind == AttachmentKind.IMAGE) total += IMAGE_TOKENS
            }
        }
        return total
    }

    fun text(value: String): Long {
        if (value.isEmpty()) return 0L
        var cjk = 0L
        var other = 0L
        value.forEach { character ->
            if (isCjk(character)) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    private fun isCjk(character: Char): Boolean {
        val code = character.code
        return code in 0x3040..0x30FF ||
            code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xAC00..0xD7AF ||
            code in 0xF900..0xFAFF ||
            code in 0xFF00..0xFFEF
    }
}
