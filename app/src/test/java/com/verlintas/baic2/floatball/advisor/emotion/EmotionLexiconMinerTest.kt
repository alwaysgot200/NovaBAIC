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

package com.verlintas.baic2.floatball.advisor.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挖词产出解析（E-g 慢车道人审流）：三形态宽松解码、标签白名单过滤、
 * 词条/理由截断、批内去重与批量上限。模型只提案，解析层把好准入关。
 */
class EmotionLexiconMinerTest {

    // ── 三形态解码 ─────────────────────────────────────────────────────

    @Test
    fun parse_termsObjectForm() {
        val out = EmotionMinedTermParser.parse(
            """{"terms":[{"term":"绝绝子","labels":["joy"],"reason":"夸张式赞美"}]}""",
        )
        assertEquals(1, out.size)
        assertEquals("绝绝子", out[0].term)
        assertEquals(listOf(EmotionLabels.JOY), out[0].labels)
        assertEquals("夸张式赞美", out[0].reason)
    }

    @Test
    fun parse_bareArrayForm() {
        val out = EmotionMinedTermParser.parse(
            """[{"term":"啊这","labels":["hesitation"],"reason":"尴尬犹豫"}]""",
        )
        assertEquals(1, out.size)
        assertEquals("啊这", out[0].term)
    }

    @Test
    fun parse_singleObjectForm() {
        val out = EmotionMinedTermParser.parse(
            """{"term":"无语","labels":["disgust"],"reason":"嫌弃"}""",
        )
        assertEquals(1, out.size)
        assertEquals("无语", out[0].term)
    }

    @Test
    fun parse_singleObjectWithEmptyLabels_stillDecodes() {
        val out = EmotionMinedTermParser.parse(
            """{"term":"好耶","labels":[],"reason":""}""",
        )
        assertEquals(1, out.size)
        assertEquals("好耶", out[0].term)
        assertTrue(out[0].labels.isEmpty())
    }

    @Test
    fun parse_stripsMarkdownFence() {
        val out = EmotionMinedTermParser.parse(
            """
            ```json
            {"terms":[{"term":"栓Q","labels":["disgust"],"reason":"反讽"}]}
            ```
            """.trimIndent(),
        )
        assertEquals(1, out.size)
        assertEquals("栓Q", out[0].term)
    }

    @Test
    fun parse_malformedReturnsEmpty() {
        assertTrue(EmotionMinedTermParser.parse("").isEmpty())
        assertTrue(EmotionMinedTermParser.parse("完全不是 JSON 的输出").isEmpty())
        assertTrue(EmotionMinedTermParser.parse("""{"terms":[]}""").isEmpty())
    }

    // ── 准入把关 ───────────────────────────────────────────────────────

    @Test
    fun parse_filtersUnknownLabels() {
        val out = EmotionMinedTermParser.parse(
            """{"terms":[{"term":"无语","labels":["disgust","不存在的标签","JOY"],"reason":"嫌弃"}]}""",
        )
        assertEquals(listOf(EmotionLabels.DISGUST), out[0].labels)
    }

    @Test
    fun parse_truncatesOverlongTermAndReason() {
        val longTerm = "一二三四五六七八九十一二三四五" // 15 字 > 12
        val out = EmotionMinedTermParser.parse(
            """{"terms":[{"term":"$longTerm","labels":[],"reason":"${"理".repeat(80)}"}]}""",
        )
        assertEquals(EmotionMinedTermParser.TERM_MAX, out[0].term.length)
        assertEquals(EmotionMinedTermParser.REASON_MAX, out[0].reason.length)
    }

    @Test
    fun parse_dedupesWithinBatchAndCapsAtBatchMax() {
        val items = (1..8).joinToString(",") { i ->
            """{"term":"词$i","labels":["low"],"reason":"r$i"}"""
        }
        val dup = """{"term":"词1","labels":["low"],"reason":"重复"}"""
        val out = EmotionMinedTermParser.parse("""{"terms":[$items,$dup]}""")
        assertEquals(EmotionMinedTermParser.BATCH_MAX, out.size)
        assertEquals(out.size, out.map { it.term }.distinct().size)
    }

    @Test
    fun parse_dropsEmptyTerm() {
        val out = EmotionMinedTermParser.parse(
            """{"terms":[{"term":"   ","labels":["joy"],"reason":"空词"},{"term":"好耶","labels":["joy"],"reason":""}]}""",
        )
        assertEquals(listOf("好耶"), out.map { it.term })
    }
}
