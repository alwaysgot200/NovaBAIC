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

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun countsCjkPerCharacterAndLatinPerFour() {
        assertEquals(4, TokenEstimator.text("你好世界"))
        assertEquals(2, TokenEstimator.text("abcdefgh"))
        assertEquals(3, TokenEstimator.text("你好abcd"))
        assertEquals(0, TokenEstimator.text(""))
    }

    @Test
    fun messageEstimateIncludesToolCallsAndAttachments() {
        val plain = ChatMessage(role = ChatRole.USER, content = "hello")
        val rich = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = "hi",
            toolCalls = listOf(
                ToolCall(
                    id = "call_1",
                    name = "device_info",
                    argumentsJson = "{}",
                    result = "Android 15",
                ),
            ),
            attachments = listOf(
                Attachment(id = "a", kind = AttachmentKind.TEXT, mimeType = "text/plain", text = "note"),
            ),
        )
        assertTrue(TokenEstimator.message(rich) > TokenEstimator.message(plain))
    }

    @Test
    fun contextEstimateGrowsWithHistoryAndTools() {
        val messages = listOf(
            ChatMessage(role = ChatRole.USER, content = "hello"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "hi there"),
        )
        val specs = listOf(ToolSpec(name = "device_info", description = "device facts"))
        val base = TokenEstimator.estimate(messages)
        val withSystem = TokenEstimator.estimate(messages, systemPrompt = "You are helpful.")
        val withTools = TokenEstimator.estimate(messages, toolSpecs = specs)
        val withStream = TokenEstimator.estimate(messages, streamingText = "partial answer")
        assertTrue(withSystem > base)
        assertTrue(withTools > base)
        assertTrue(withStream > base)
    }

    @Test
    fun attachmentsAreOnlyCountedForTheLastUserTurn() {
        val image = Attachment(
            id = "img",
            kind = AttachmentKind.IMAGE,
            mimeType = "image/jpeg",
            fileName = "photo.jpg",
        )
        val olderWithImage = ChatMessage(
            role = ChatRole.USER,
            content = "look at this",
            attachments = listOf(image),
        )
        val newer = ChatMessage(role = ChatRole.USER, content = "and now this")
        val withoutAttachment = olderWithImage.copy(attachments = emptyList())

        val withImage = TokenEstimator.estimate(listOf(olderWithImage, newer))
        val withoutImage = TokenEstimator.estimate(listOf(withoutAttachment, newer))
        assertEquals(withoutImage, withImage, "older attachments must not be counted")
    }

    @Test
    fun imagesOnTheLastUserTurnAddTokens() {
        val image = Attachment(
            id = "img",
            kind = AttachmentKind.IMAGE,
            mimeType = "image/jpeg",
            fileName = "photo.jpg",
        )
        val plain = ChatMessage(role = ChatRole.USER, content = "look")
        val withImage = plain.copy(attachments = listOf(image))
        val delta = TokenEstimator.estimate(listOf(withImage)) - TokenEstimator.estimate(listOf(plain))
        assertTrue(delta >= 800, "image should add a rough token cost, delta=$delta")
    }

    @Test
    fun toolResultsAreNotDoubleCounted() {
        val call = ToolCall(
            id = "call_1",
            name = "device_info",
            argumentsJson = "{}",
            result = "x".repeat(400),
        )
        val assistantWithResult = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = "",
            toolCalls = listOf(call),
        )
        val toolMessage = ChatMessage(
            role = ChatRole.TOOL,
            content = "x".repeat(400),
            toolCallId = "call_1",
            toolName = "device_info",
        )
        val estimate = TokenEstimator.estimate(listOf(assistantWithResult, toolMessage))
        val resultOnlyOnce = TokenEstimator.estimate(listOf(toolMessage))
        // The 400-char result (~100 tokens) must appear once, not twice.
        assertTrue(
            estimate - resultOnlyOnce < 200,
            "result appears more than once: estimate=$estimate baseline=$resultOnlyOnce",
        )
    }
}
