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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.rpa.model

/** 文本匹配口径（a11y 选择器 / OCR 定位 / 断言共用）。 */
object RpaTextMatch {

    fun matches(
        value: String?,
        pattern: String?,
        mode: RpaMatchMode,
        ignoreWhitespace: Boolean = false,
    ): Boolean {
        if (value == null || pattern.isNullOrEmpty()) return false
        val target = normalize(value, ignoreWhitespace)
        val needle = normalize(pattern, ignoreWhitespace)
        return when (mode) {
            RpaMatchMode.EXACT -> target == needle
            RpaMatchMode.CONTAINS -> target.contains(needle)
            RpaMatchMode.PREFIX -> target.startsWith(needle)
            RpaMatchMode.REGEX -> runCatching {
                Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(value)
            }.getOrDefault(false)
        }
    }

    private fun normalize(value: String, ignoreWhitespace: Boolean): String =
        (if (ignoreWhitespace) value.filterNot { it.isWhitespace() } else value.trim()).lowercase()
}
