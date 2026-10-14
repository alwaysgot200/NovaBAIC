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

/**
 * Cross-session regression suite for the memory protocol: the behaviours this
 * project promised (no duplicates, explicit corrections, forgotten stays
 * forgotten, time travel) are exercised end to end over the pure engine.
 */
class MemoryRegressionTest {

    private val now = 1_700_000_000_000L
    private var nextId = 1L
    private val notes = mutableListOf<Note>()
    private val revisions = mutableMapOf<Long, MutableList<NoteRevision>>()
    private val suppressed = mutableListOf<Note>()
    private val holds = mutableListOf<MemoryHold>()

    private fun write(
        content: String,
        kind: NoteKind = NoteKind.FACT,
        at: Long = now,
    ): MemoryConsolidator.Decision {
        val decision = MemoryConsolidator.decide(content, notes, suppressed, holds)
        when (decision) {
            is MemoryConsolidator.Decision.Stored -> notes += Note(
                id = nextId++,
                kind = kind,
                content = content,
                createdAt = at,
                updatedAt = at,
            )
            is MemoryConsolidator.Decision.Merged -> {
                val index = notes.indexOfFirst { it.id == decision.noteId }
                val previous = notes[index]
                revisions.getOrPut(previous.id) { mutableListOf() } +=
                    NoteRevision(content = previous.content, importance = previous.importance, replacedAt = at)
                notes[index] = previous.copy(content = content, updatedAt = at)
            }
            else -> Unit
        }
        return decision
    }

    private fun forget(note: Note) {
        notes.removeAll { it.id == note.id }
        suppressed += note.copy(suppressed = true)
    }

    /** Current recall. */
    private fun recall(query: String, at: Long = now): List<Note> {
        val terms = MemoryText.terms(query)
        return MemoryScoring.rank(notes, terms, at)
            .filter { terms.isEmpty() || it.hits > 0 }
            .map { it.note }
    }

    /** As-of recall: content reconstructed from before-images, like the app. */
    private fun recallAsOf(query: String, at: Long): List<Note> {
        val terms = MemoryText.terms(query)
        val reconstructed = notes.map { note ->
            val history = revisions[note.id].orEmpty().sortedByDescending { it.replacedAt }
            val (content, importance) = MemoryHistory.contentAsOf(
                currentContent = note.content,
                currentImportance = note.importance,
                revisions = history,
                at = at,
            )
            note.copy(content = content, importance = importance)
        }
        return MemoryScoring.rank(reconstructed, terms, at)
            .filter { terms.isEmpty() || it.hits > 0 }
            .map { it.note }
    }

    @Test
    fun identicalFactsAreWrittenOnce() {
        write("用户喜欢冰拿铁")
        val second = write("用户喜欢冰拿铁")
        assertTrue(second is MemoryConsolidator.Decision.Duplicate)
        assertEquals(1, notes.size)
    }

    @Test
    fun correctionsReplaceInPlaceAndKeepHistory() {
        write("用户在杭州")
        val decision = write("用户住在杭州")
        assertTrue(decision is MemoryConsolidator.Decision.Merged)
        assertEquals(1, notes.size)
        assertEquals("用户住在杭州", notes.first().content)

        // Time travel sees the version that was true before the correction.
        val before = recallAsOf("杭州", at = now - 1)
        assertEquals("用户在杭州", before.first().content)
        val after = recall("杭州", at = now + 1)
        assertEquals("用户住在杭州", after.first().content)
    }

    @Test
    fun contradictionsAreSurfacedNotSilentlyKeptOrMerged() {
        write("用户在杭州")
        val decision = write("用户在上海")
        assertTrue(decision is MemoryConsolidator.Decision.Stored)
        assertEquals(1, decision.conflicts.size)
        assertEquals(2, notes.size)
    }

    @Test
    fun forgottenFactsCannotBeRelearned() {
        write("用户在杭州")
        forget(notes.first())
        val decision = write("用户在杭州")
        assertTrue(decision is MemoryConsolidator.Decision.Suppressed)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun holdsBlockRecordingEvenWhenNobodyAskedAgain() {
        holds += MemoryHold(id = 1, content = "银行卡密码是8877")
        val decision = write("我的银行卡密码是8877")
        assertTrue(decision is MemoryConsolidator.Decision.Held)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun expiredFactsStayRetrievableButRankLower() {
        notes += Note(
            id = nextId++,
            content = "用户目前在乌兰浩特",
            expiresAt = now + 3_600_000L,
            createdAt = now - 1_000L,
            updatedAt = now - 1_000L,
        )
        notes += Note(
            id = nextId++,
            content = "用户曾在乌兰浩特出差",
            expiresAt = now - 3_600_000L,
            createdAt = now - 1_000L,
            updatedAt = now - 1_000L,
        )
        val hits = recall("乌兰浩特")
        assertEquals(2, hits.size)
        assertEquals("用户目前在乌兰浩特", hits.first().content)
        assertTrue(hits.first().isExpired(now).not())
        assertTrue(hits.last().isExpired(now))
    }
}
