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
 * Renders memory into the two places it is allowed to cost tokens: the small
 * always-on block in the system prompt, and the curator's inventory view.
 */
object MemoryPrompt {
    private const val MAX_CONTEXT_CHARS = 2_400
    private const val MAX_INVENTORY_LINE = 240
    private const val MAX_INVENTORY_CHARS = 6_000

    /**
     * The always-on block: core memory plus the handful of notes primed by the
     * current message. Returns null when there is nothing worth saying.
     */
    fun context(core: CoreMemory?, primed: List<Note>, now: Long): String? {
        val parts = mutableListOf<String>()
        val user = core?.user?.trim().orEmpty()
        val ongoing = core?.context?.trim().orEmpty()
        val stamp = core?.updatedAt?.takeIf { it > 0 }
            ?.let { " (updated ${MemoryText.dateOnly(it)})" }
            .orEmpty()
        if (user.isNotEmpty()) parts += "About the user$stamp:\n$user"
        if (ongoing.isNotEmpty()) parts += "Currently ongoing$stamp:\n$ongoing"
        if (primed.isNotEmpty()) {
            // Notes that share a frame but disagree are flagged for both sides,
            // so the model asks instead of silently trusting one of them.
            val conflicts = conflictMarks(primed)
            parts += "Notes primed by the current message:\n" + primed.joinToString("\n") { note ->
                val flag = conflicts[note.id]?.let { ids ->
                    " ! possible conflict with " + ids.joinToString(", ") { "#$it" }
                }.orEmpty()
                "- (${noteMeta(note)}${expiryMeta(note, now)}, " +
                    "${MemoryText.dateOnly(note.whenAt ?: note.updatedAt)})$flag " +
                    note.content.replace('\n', ' ').trim()
            }
        }
        if (parts.isEmpty()) return null
        return "What you already remember:\n\n" +
            parts.joinToString("\n\n").take(MAX_CONTEXT_CHARS)
    }

    private fun noteMeta(note: Note): String = buildString {
        append(note.kind.wire())
        if (note.source != NoteSource.USER) append(", ").append(note.source.wire())
        if (note.entities.isNotEmpty()) {
            append(", ").append(note.entities.joinToString("/") { "@$it" })
        }
    }

    /** Marks stale/expiring facts so the model never presents them as current. */
    private fun expiryMeta(note: Note, now: Long): String = when {
        note.expiresAt == null -> ""
        note.isExpired(now) -> ", EXPIRED ${MemoryText.dateOnly(note.expiresAt)} - historical, verify before use"
        else -> ", valid until ${MemoryText.dateOnly(note.expiresAt)}"
    }

    /** id -> conflicting note ids, only within the same kind. */
    private fun conflictMarks(notes: List<Note>): Map<Long, List<Long>> {
        if (notes.size < 2) return emptyMap()
        val marks = LinkedHashMap<Long, MutableList<Long>>()
        for (i in notes.indices) {
            for (j in i + 1 until notes.size) {
                val a = notes[i]
                val b = notes[j]
                if (a.kind != b.kind || !MemoryConflict.isConflict(a.content, b.content)) continue
                marks.getOrPut(a.id) { mutableListOf() }.add(b.id)
                marks.getOrPut(b.id) { mutableListOf() }.add(a.id)
            }
        }
        return marks
    }

    /** One line per note for the curator, ids included so it can revise them. */
    fun inventory(notes: List<Note>): String =
        notes.joinToString("\n") { note ->
            "#${note.id} ${note.kind.wire()} i${note.importance}" +
                (if (note.pinned) " pinned" else "") +
                (if (note.source != NoteSource.USER) " src:${note.source.wire()}" else "") +
                (if (note.entities.isNotEmpty()) {
                    " @${note.entities.joinToString("@")}"
                } else {
                    ""
                }) +
                (if (note.expiresAt != null) {
                    " expires:${MemoryText.dateOnly(note.expiresAt)}"
                } else {
                    ""
                }) +
                " (${MemoryText.dateOnly(note.updatedAt)}): " +
                note.content.replace('\n', ' ').take(MAX_INVENTORY_LINE)
        }
            .take(MAX_INVENTORY_CHARS)
            .ifBlank { "(none)" }

    /**
     * Sleep rehearsal feed: valuable traces whose retrievability is fading -
     * the curator decides keep (reinforce), revise or forget.
     */
    fun fading(notes: List<Note>, now: Long): String =
        notes.joinToString("\n") { note ->
            val recall = (MemoryScoring.retrievability(note, now) * 100).toInt()
            "#${note.id} i${note.importance} recall $recall% (${note.kind.wire()}): " +
                note.content.replace('\n', ' ').take(MAX_INVENTORY_LINE)
        }.ifBlank { "(none)" }

    /** The curator sees the current core verbatim so it can preserve it. */
    fun coreBlocks(core: CoreMemory?): String = buildString {
        append("[user] ").append(core?.user?.trim().orEmpty().ifBlank { "(empty)" })
        append('\n')
        append("[context] ").append(core?.context?.trim().orEmpty().ifBlank { "(empty)" })
    }

    /** Holds the curator must respect: nothing matching these may be recorded. */
    fun holds(holds: List<MemoryHold>): String =
        holds.joinToString("\n") { "#${it.id}: ${it.content}" }.ifBlank { "(none)" }
}
