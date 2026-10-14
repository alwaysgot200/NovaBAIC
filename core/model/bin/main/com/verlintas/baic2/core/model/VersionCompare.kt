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

/** True when [latest] (e.g. "v0.1.5") is a newer semver-ish tag than [current] ("0.1.4"). */
fun isVersionNewer(latest: String, current: String): Boolean {
    fun parts(value: String): List<Int> =
        value.trim().removePrefix("v")
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .mapNotNull { it.toIntOrNull() }
    val a = parts(latest)
    val b = parts(current)
    if (a.isEmpty() || b.isEmpty()) return false
    for (index in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(index) { 0 }
        val y = b.getOrElse(index) { 0 }
        if (x != y) return x > y
    }
    return false
}
