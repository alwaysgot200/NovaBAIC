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
import kotlin.test.assertTrue

class MemoryScoringTest {

    private val now = 1_000_000_000_000L

    private fun note(
        id: Long,
        content: String,
        importance: Int = 3,
        pinned: Boolean = false,
        ageDays: Long = 0,
        accessCount: Int = 0,
        strength: Double = 1.0,
        expiresAt: Long? = null,
    ) = Note(
        id = id,
        content = content,
        importance = importance,
        pinned = pinned,
        updatedAt = now - ageDays * 86_400_000L,
        accessCount = accessCount,
        strength = strength,
        expiresAt = expiresAt,
    )

    @Test
    fun expiredNotesLoseRankingWeightButStayRetrievable() {
        val fresh = note(1, "用户现在在乌兰浩特", expiresAt = now + 3_600_000L)
        val expired = note(2, "用户现在在乌兰浩特", expiresAt = now - 3_600_000L)
        val ranked = MemoryScoring.rank(
            listOf(expired, fresh),
            MemoryText.terms("乌兰浩特"),
            now,
        )
        assertEquals(1L, ranked.first().note.id)
        assertEquals(2L, ranked.last().note.id)
        assertTrue(ranked.last().score > 0.0)
    }

    @Test
    fun matchingNotesOutrankUnrelatedOnes() {
        val ranked = MemoryScoring.rank(
            listOf(note(1, "用户喜欢无糖燕麦拿铁"), note(2, "用户住在杭州")),
            MemoryText.terms("咖啡 拿铁"),
            now,
        )
        assertEquals(1L, ranked.first().note.id)
    }

    @Test
    fun unrelatedNotesAreFilteredOut() {
        val ranked = MemoryScoring.rank(
            listOf(note(2, "用户住在杭州")),
            MemoryText.terms("拿铁"),
            now,
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun importancePinsAndUseRaiseTheScore() {
        val base = MemoryScoring.score(note(1, "喜欢拿铁"), listOf("拿铁"), now).score
        val important = MemoryScoring.score(note(2, "喜欢拿铁", importance = 5), listOf("拿铁"), now).score
        val pinned = MemoryScoring.score(note(3, "喜欢拿铁", pinned = true), listOf("拿铁"), now).score
        val used = MemoryScoring.score(note(4, "喜欢拿铁", accessCount = 9), listOf("拿铁"), now).score
        assertTrue(important > base)
        assertTrue(pinned > base)
        assertTrue(used > base)
    }

    @Test
    fun theForgettingCurveFadesOldMemories() {
        val fresh = MemoryScoring.score(note(1, "喜欢拿铁"), listOf("拿铁"), now).score
        val old = MemoryScoring.score(note(2, "喜欢拿铁", ageDays = 90), listOf("拿铁"), now).score
        assertTrue(fresh > old)
    }

    @Test
    fun browseModeKeepsEverythingAndPrefersRecent() {
        val ranked = MemoryScoring.rank(
            listOf(note(1, "旧笔记", ageDays = 30), note(2, "新笔记")),
            emptyList(),
            now,
        )
        assertEquals(2, ranked.size)
        assertEquals(2L, ranked.first().note.id)
    }

    @Test
    fun retrievalStrengthSlowsForgetting() {
        val weak = MemoryScoring.score(note(1, "喜欢拿铁", ageDays = 30), listOf("拿铁"), now).score
        val strong = MemoryScoring.score(
            note(2, "喜欢拿铁", ageDays = 30, strength = 4.0),
            listOf("拿铁"),
            now,
        ).score
        assertTrue(strong > weak)
    }

    @Test
    fun spreadingActivationIsWeakerThanTheCueAndScalesWithWeight() {
        val direct = 1.0
        val thin = MemoryScoring.spreadScore(direct, 1.0)
        val thick = MemoryScoring.spreadScore(direct, 4.0)
        assertTrue(thin < direct)
        assertTrue(thick in thin..direct)
    }

    @Test
    fun reconsolidationRaisesStrengthButSaturates() {
        assertEquals(1.6, MemoryScoring.reinforceStrength(1.0), 1e-9)
        assertEquals(MemoryScoring.MAX_STRENGTH, MemoryScoring.reinforceStrength(4.9), 1e-9)
    }

    @Test
    fun userTracesOutweighAssistantEchoes() {
        assertEquals(2.0, MemoryScoring.messageScore(ChatRole.USER, 1), 1e-9)
        assertEquals(1.0, MemoryScoring.messageScore(ChatRole.ASSISTANT, 1), 1e-9)
        // A user message matching one cue ties an assistant matching two -
        // the tiebreak then prefers the user's own trace.
        assertEquals(
            MemoryScoring.messageScore(ChatRole.USER, 1),
            MemoryScoring.messageScore(ChatRole.ASSISTANT, 2),
        )
    }

    @Test
    fun sourceMonitoringWeightsTrust() {
        assertTrue(
            MemoryScoring.sourceFactor(NoteSource.USER) >
                MemoryScoring.sourceFactor(NoteSource.ASSISTANT),
        )
        assertTrue(
            MemoryScoring.sourceFactor(NoteSource.ASSISTANT) >
                MemoryScoring.sourceFactor(NoteSource.EXTERNAL),
        )
    }
}
