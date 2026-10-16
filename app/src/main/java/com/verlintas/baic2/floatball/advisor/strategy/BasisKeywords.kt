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
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor.strategy

/**
 * 依据关键词（P3 §九，纯函数）：本回合实际进提示词的画像/目标显示值
 * （ChatBriefBuilder.curatedTexts）裁成 ≤[LIMIT] 个短词，随气泡展示
 * 「依据：X、Y · 记错了」。只做展示用裁剪，不参与提示词。
 */
object BasisKeywords {

    const val LIMIT = 3
    const val MIN_CHARS = 2
    const val MAX_KEYWORD_CHARS = 10

    private val PAREN = Regex("""（[^）]*）|\([^)]*\)""")

    fun from(curated: List<String>): List<String> =
        curated.asSequence()
            .map { it.replace(PAREN, "").trim() }
            .filter { it.length >= MIN_CHARS }
            .map { it.take(MAX_KEYWORD_CHARS) }
            .distinct()
            .take(LIMIT)
            .toList()
}
