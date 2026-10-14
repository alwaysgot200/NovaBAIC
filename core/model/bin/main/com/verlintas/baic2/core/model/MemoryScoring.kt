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

import java.util.Locale
import kotlin.math.ln

/**
 * How strongly a note answers a set of cues: matched terms times a blend of
 * importance, pinning, use count and a forgetting curve over age. Deliberately
 * simple and pure so recall ordering is testable.
 */
data class ScoredNote(
    val note: Note,
    val score: Double,
    val hits: Int,
    /** Which cues actually matched, so the agent can see why it recalled this. */
    val matchedCues: List<String> = emptyList(),
    /** True when the note surfaced through an association, not the cues. */
    val spread: Boolean = false,
    /** The seed this note was awakened from, and the link weight. */
    val spreadFrom: Long? = null,
    val linkWeight: Double = 0.0,
    /** 1 = direct associate of a cue hit, 2 = associate of an associate. */
    val spreadHops: Int = 1,
)

object MemoryScoring {

    private const val DAY_MS = 86_400_000.0

    /** Strength multiplies the stability (retention half-life) of a trace. */
    private const val STABILITY_DAYS = 10.0
    const val MAX_STRENGTH = 5.0
    private const val TOUCH_GAIN = 0.6

    /** Expired facts stay recallable but lose most of their ranking weight. */
    const val EXPIRED_FACTOR = 0.35

    /** How much of a trace survives right now, governed by strength. */
    fun retrievability(note: Note, now: Long): Double {
        val anchor = maxOf(note.lastAccessedAt, note.updatedAt, note.createdAt)
        val ageDays = (now - anchor).coerceAtLeast(0L) / DAY_MS
        val stability = STABILITY_DAYS * note.strength.coerceIn(0.5, MAX_STRENGTH)
        return kotlin.math.exp(-ageDays / stability)
    }

    fun score(note: Note, terms: List<String>, now: Long): ScoredNote {
        val normalized = MemoryText.normalize(note.content)
        val matched = terms.filter { normalized.contains(it.lowercase(Locale.ROOT)) }
        if (terms.isNotEmpty() && matched.isEmpty()) return ScoredNote(note, 0.0, 0)
        val hitFactor = if (terms.isEmpty()) 1.0 else matched.size.toDouble() / terms.size
        val importance = 0.5 + note.importance.coerceIn(1, 5) * 0.15
        val pinned = if (note.pinned) 0.6 else 0.0
        val accessed = minOf(0.3, ln(1.0 + note.accessCount) / 10.0)
        // A stale location fact must not outrank a fresh one, but it stays
        // retrievable: it is history, not garbage.
        val freshness = if (note.isExpired(now)) EXPIRED_FACTOR else 1.0
        val score = hitFactor *
            (importance + pinned + accessed) *
            (0.4 + 0.6 * retrievability(note, now)) *
            sourceFactor(note.source) *
            freshness
        return ScoredNote(note, score, matched.size, matched)
    }

    /**
     * Source monitoring: what the user said himself outweighs the model's own
     * inference, which outweighs scraped external text.
     */
    fun sourceFactor(source: NoteSource): Double = when (source) {
        NoteSource.USER -> 1.0
        NoteSource.ASSISTANT -> 0.9
        NoteSource.EXTERNAL -> 0.75
        NoteSource.UNKNOWN -> 0.85
    }

    fun rank(notes: List<Note>, terms: List<String>, now: Long): List<ScoredNote> =
        notes.asSequence()
            .map { score(it, terms, now) }
            .filter { it.hits > 0 || terms.isEmpty() }
            .sortedWith(
                compareByDescending<ScoredNote> { it.score }
                    .thenByDescending { it.note.pinned }
                    .thenByDescending { it.note.updatedAt },
            )
            .toList()

    /** A linked trace lights up weaker than its cue, scaled by link weight. */
    fun spreadScore(source: Double, weight: Double): Double =
        source * 0.35 * (weight / (weight + 1.0))

    fun reinforceStrength(current: Double): Double =
        (current + TOUCH_GAIN).coerceAtMost(MAX_STRENGTH)

    /**
     * Episodic ranking weight per speaker. A user message is the person's own
     * trace and counts double: assistant replies echo the question's
     * vocabulary, so without this "the more you are answered, the less you
     * can retrieve what you yourself said".
     */
    const val USER_CUE_WEIGHT = 2.0

    fun messageScore(role: ChatRole, matchedCues: Int): Double =
        matchedCues * if (role == ChatRole.USER) USER_CUE_WEIGHT else 1.0
}
