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
 * Detects when two notes share the same frame but disagree on the value:
 * "她在杭州" vs "她在上海" is a contradiction, "她喜欢咖啡" vs "她喜欢咖啡厅"
 * is an enrichment. Pure and explainable on purpose - the agent should say
 * "this conflicts with what I knew" instead of silently keeping both.
 */
object MemoryConflict {

    data class Verdict(val conflicting: Boolean, val reason: String? = null)

    /** Same frame required; below this much shared text they are just different facts. */
    private const val FRAME_RATIO = 0.5

    /** A value segment longer than this is prose, not a swap ("值不同" rarely fits). */
    private const val MAX_VALUE_CHARS = 8

    private val NEGATIONS = listOf(
        "不", "没", "无", "别", "未", "非",
        "not", "no", "never", "without",
        "isnt", "arent", "doesnt", "dont", "didnt", "cant", "wont",
    )

    fun isConflict(existing: String, incoming: String): Boolean = check(existing, incoming).conflicting

    fun check(existing: String, incoming: String): Verdict {
        val a = MemoryText.normalize(existing)
        val b = MemoryText.normalize(incoming)
        if (a.isEmpty() || b.isEmpty() || a == b) return Verdict(false)
        val prefix = commonPrefix(a, b)
        val suffix = commonSuffix(a, b, prefix)
        val middleA = a.substring(prefix, a.length - suffix)
        val middleB = b.substring(prefix, b.length - suffix)
        val shared = (prefix + suffix).toDouble() / maxOf(a.length, b.length)
        if (shared < FRAME_RATIO) return Verdict(false)
        // "她不去学校" vs "她去学校": same frame, polarity flipped.
        if (hasNegation(middleA) != hasNegation(middleB)) {
            return Verdict(true, "polarity differs")
        }
        // Both sides carry a short, different value in the same slot.
        if (middleA.isNotEmpty() && middleB.isNotEmpty() &&
            maxOf(middleA.length, middleB.length) <= MAX_VALUE_CHARS
        ) {
            return Verdict(true, "value differs (\"$middleA\" vs \"$middleB\")")
        }
        return Verdict(false)
    }

    private fun hasNegation(text: String): Boolean = NEGATIONS.any { text.contains(it) }

    private fun commonPrefix(a: String, b: String): Int {
        var index = 0
        while (index < a.length && index < b.length && a[index] == b[index]) index++
        return index
    }

    private fun commonSuffix(a: String, b: String, prefix: Int): Int {
        var length = 0
        while (length < a.length - prefix && length < b.length - prefix &&
            a[a.length - 1 - length] == b[b.length - 1 - length]
        ) {
            length++
        }
        return length
    }
}
