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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor

import com.verlintas.baic2.floatball.advisor.data.EmotionLexiconEntity
import com.verlintas.baic2.floatball.advisor.emotion.EmotionLibraries
import com.verlintas.baic2.floatball.advisor.emotion.EmotionLexiconEntry
import com.verlintas.baic2.floatball.advisor.emotion.parseEmotionLexiconSeed
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播种计划（E-c 用户改动保护）：只按 (term, library, scopeContactId) 身份补缺——
 * 用户调过的权重/停用/改注/自建词条绝不被覆盖或重复；seed 内部重复也只进一行；
 * 对真实资产幂等（补种一次后二次计划为空）。
 */
class EmotionLexiconSeederTest {

    private fun entry(
        term: String,
        library: Int = EmotionLibraries.TRIGGER,
        scopeContactId: Long? = null,
        polarity: Int = 1,
        weight: Float = 1f,
        source: String = "builtin",
        note: String = "",
    ) = EmotionLexiconEntry(
        term = term,
        library = library,
        polarity = polarity,
        scopeContactId = scopeContactId,
        weight = weight,
        source = source,
        note = note,
    )

    private fun row(
        term: String,
        library: Int = EmotionLibraries.TRIGGER,
        scopeContactId: Long? = null,
        weight: Float = 1f,
        enabled: Boolean = true,
        source: String = "builtin",
        note: String = "",
    ) = EmotionLexiconEntity(
        term = term,
        library = library,
        scopeContactId = scopeContactId,
        weight = weight,
        enabled = enabled,
        source = source,
        note = note,
    )

    @Test
    fun planSeed_insertsOnlyMissingIdentities() {
        val plan = EmotionLexiconSeeder.planSeed(
            existing = listOf(row("开心")),
            seed = listOf(entry("开心"), entry("难过")),
        )
        assertEquals(listOf("难过"), plan.map { it.term })
    }

    @Test
    fun planSeed_neverTouchesUserModifiedRows() {
        // 用户改过权重/停用/加注的内置词条：同身份即跳过，改动原样保留。
        val userRow = row("好的", weight = 2f, enabled = false, source = "builtin", note = "用户备注")
        val plan = EmotionLexiconSeeder.planSeed(
            existing = listOf(userRow),
            seed = listOf(entry("好的", polarity = 0)),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun planSeed_dedupsWithinSeed() {
        val plan = EmotionLexiconSeeder.planSeed(
            existing = emptyList(),
            seed = listOf(entry("呵呵"), entry("呵呵")),
        )
        assertEquals(1, plan.size)
    }

    @Test
    fun planSeed_scopeIsPartOfIdentity() {
        val plan = EmotionLexiconSeeder.planSeed(
            existing = listOf(row("随便", scopeContactId = 1)),
            seed = listOf(
                entry("随便", scopeContactId = null), // 全局 ≠ 联系人 1 限定
                entry("随便", scopeContactId = 1), // 已存在，跳过
                entry("随便", scopeContactId = 2), // 另一联系人，允许
            ),
        )
        assertEquals(listOf(null, 2L), plan.map { it.scopeContactId })
    }

    @Test
    fun planSeed_libraryIsPartOfIdentity() {
        val plan = EmotionLexiconSeeder.planSeed(
            existing = listOf(row("哈哈", library = EmotionLibraries.NET_SLANG)),
            seed = listOf(
                entry("哈哈", library = EmotionLibraries.NET_SLANG), // 已存在
                entry("哈哈", library = EmotionLibraries.TRIGGER), // 异库同词，允许
            ),
        )
        assertEquals(listOf(EmotionLibraries.TRIGGER), plan.map { it.library })
    }

    @Test
    fun planSeed_realAsset_isIdempotent() {
        val file = File("src/main/assets/advisor/emotion_lexicon.json")
        assertTrue("asset missing (cwd=${File(".").absolutePath})", file.exists())
        val seed = parseEmotionLexiconSeed(file.readText())
        assertTrue(seed.size >= 600)

        val first = EmotionLexiconSeeder.planSeed(existing = emptyList(), seed = seed)
        assertEquals("内置资产身份应无自重复", seed.size, first.size)

        // 补种后（全部身份在库）再计划为空；即便用户改了权重/停用也一样。
        val second = EmotionLexiconSeeder.planSeed(
            existing = first.map { it.copy(weight = 0.5f, enabled = false) },
            seed = seed,
        )
        assertTrue(second.isEmpty())
    }
}
