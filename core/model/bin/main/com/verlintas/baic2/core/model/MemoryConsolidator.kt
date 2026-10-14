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
 * The write-time memory protocol, extracted from storage so every rule is
 * regression-testable without a database: promises first (holds), then
 * inhibition (suppression), then pattern completion (duplicate / merge /
 * store with near-duplicates and suspected contradictions surfaced).
 */
object MemoryConsolidator {

    const val SUPPRESSION_SIMILARITY = 0.7
    const val MERGE_SIMILARITY = 0.8
    const val HINT_SIMILARITY = 0.55
    private const val MAX_SIMILAR = 3

    /** A stored note that may disagree with what is already known. */
    data class Conflict(
        val noteId: Long,
        val existingContent: String,
        val reason: String,
    )

    sealed interface Decision {
        /** The user asked that this never be recorded. */
        data class Held(val holdId: Long) : Decision

        /** The fact was explicitly forgotten; re-learning needs confirmation. */
        data class Suppressed(val noteId: Long) : Decision

        /** Exact-normalised duplicate: nothing to write. */
        data class Duplicate(val noteId: Long) : Decision

        /** Near-duplicate reconsolidated into the existing trace. */
        data class Merged(val noteId: Long, val previousContent: String) : Decision

        /** A genuinely new fact; near-duplicates and conflicts are reported. */
        data class Stored(
            val similarIds: List<Long>,
            val conflicts: List<Conflict>,
            val bestSimilarity: Double,
        ) : Decision
    }

    fun decide(
        content: String,
        existing: List<Note>,
        suppressed: List<Note> = emptyList(),
        holds: List<MemoryHold> = emptyList(),
    ): Decision {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Decision.Duplicate(0L)
        holds.firstOrNull { MemoryText.similarity(trimmed, it.content) >= SUPPRESSION_SIMILARITY }
            ?.let { return Decision.Held(it.id) }
        suppressed.firstOrNull { MemoryText.similarity(trimmed, it.content) >= SUPPRESSION_SIMILARITY }
            ?.let { return Decision.Suppressed(it.id) }

        var best: Note? = null
        var bestSimilarity = 0.0
        val band = ArrayList<Pair<Note, Double>>()
        existing.forEach { note ->
            val similarity = MemoryText.similarity(trimmed, note.content)
            if (similarity > bestSimilarity) {
                best = note
                bestSimilarity = similarity
            }
            if (similarity >= HINT_SIMILARITY && similarity < MERGE_SIMILARITY) {
                band += note to similarity
            }
        }
        best?.let { note ->
            if (bestSimilarity >= 1.0) return Decision.Duplicate(note.id)
            if (bestSimilarity >= MERGE_SIMILARITY) {
                return Decision.Merged(note.id, note.content)
            }
        }
        band.sortByDescending { it.second }
        val similar = band.take(MAX_SIMILAR).map { it.first }
        val conflicts = similar.mapNotNull { note ->
            val verdict = MemoryConflict.check(note.content, trimmed)
            if (verdict.conflicting) {
                Conflict(note.id, note.content, verdict.reason.orEmpty())
            } else {
                null
            }
        }
        return Decision.Stored(similar.map { it.id }, conflicts, bestSimilarity)
    }
}
