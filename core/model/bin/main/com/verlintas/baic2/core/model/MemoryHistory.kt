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
 * Time travel over a note's revision history. Revisions are before-images
 * (saved when a note was rewritten), so the first replacement *after* a
 * moment holds the content that was valid at that moment.
 */
object MemoryHistory {

    fun contentAsOf(
        currentContent: String,
        currentImportance: Int,
        revisions: List<NoteRevision>,
        at: Long,
    ): Pair<String, Int> {
        val nextReplacement = revisions
            .filter { it.replacedAt > at }
            .minByOrNull { it.replacedAt }
            ?: return currentContent to currentImportance
        return nextReplacement.content to nextReplacement.importance
    }
}
