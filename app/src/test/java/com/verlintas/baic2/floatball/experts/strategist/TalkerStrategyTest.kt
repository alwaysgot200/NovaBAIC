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

package com.verlintas.baic2.floatball.experts.strategist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 聊天手段策略存储模型（v12，T1-a，总方案 §三/§四）：编解码畸形回退、档位归一、
 * 维度取值三个纯函数面的锁死——画像页所见即所存、军师触发判定消费侧不因
 * 脏数据抛异常（防御旧数据/手改库）。
 */
class TalkerStrategyTest {

    // ── normalizeTalkerLevel：未知/空值一律回默认档 assess（D1「全部自评」）──

    @Test
    fun normalize_unknownOrBlankFallsBackToAssess() {
        assertEquals(TALKER_ASSESS, normalizeTalkerLevel(null))
        assertEquals(TALKER_ASSESS, normalizeTalkerLevel(""))
        assertEquals(TALKER_ASSESS, normalizeTalkerLevel("auto"))
        assertEquals(TALKER_ASSESS, normalizeTalkerLevel("FREE "))
    }

    @Test
    fun normalize_explicitLevelsPassThrough() {
        assertEquals(TALKER_NEVER, normalizeTalkerLevel(TALKER_NEVER))
        assertEquals(TALKER_ASSESS, normalizeTalkerLevel(TALKER_ASSESS))
        assertEquals(TALKER_FREE, normalizeTalkerLevel(TALKER_FREE))
    }

    // ── decode：null/畸形/缺键全部回默认档，绝不抛 ─────────────────────────

    @Test
    fun decode_nullAndMalformedReturnDefaults() {
        val defaults = TalkerStrategy()
        assertEquals(defaults, TalkerStrategyCodec.decode(null))
        assertEquals(defaults, TalkerStrategyCodec.decode(""))
        assertEquals(defaults, TalkerStrategyCodec.decode("not json {"))
        assertEquals(defaults, TalkerStrategyCodec.decode("{\"lying\": 123}"))
    }

    @Test
    fun decode_missingKeysFillDefaults() {
        val decoded = TalkerStrategyCodec.decode("""{"promising":"never"}""")
        assertEquals(TALKER_ASSESS, decoded.lying)
        assertEquals(TALKER_NEVER, decoded.promising)
        assertEquals(TALKER_ASSESS, decoded.exemplifying)
    }

    @Test
    fun decode_unknownLevelValuesAreNormalized() {
        val decoded = TalkerStrategyCodec.decode(
            """{"lying":"maybe","promising":"free","exemplifying":"never"}""",
        )
        assertEquals(TALKER_ASSESS, decoded.lying)
        assertEquals(TALKER_FREE, decoded.promising)
        assertEquals(TALKER_NEVER, decoded.exemplifying)
    }

    @Test
    fun decode_ignoresUnknownKeys() {
        val decoded = TalkerStrategyCodec.decode(
            """{"lying":"free","future":"whatever"}""",
        )
        assertEquals(TALKER_FREE, decoded.lying)
    }

    // ── encode：落库前归一 + 编解码回环 ──────────────────────────────────

    @Test
    fun encodeNormalizesBeforeWrite() {
        val encoded = TalkerStrategyCodec.encode(
            TalkerStrategy(lying = "weird", promising = TALKER_FREE, exemplifying = TALKER_NEVER),
        )
        val decoded = TalkerStrategyCodec.decode(encoded)
        assertEquals(TALKER_ASSESS, decoded.lying)
        assertEquals(TALKER_FREE, decoded.promising)
        assertEquals(TALKER_NEVER, decoded.exemplifying)
    }

    @Test
    fun roundTripExplicitAssessIsStable() {
        // 画像页所见即所存：显式 assess 存取后不塌缩成缺键语义差异。
        val strategy = TalkerStrategy(lying = TALKER_ASSESS, promising = TALKER_ASSESS, exemplifying = TALKER_FREE)
        assertEquals(strategy, TalkerStrategyCodec.decode(TalkerStrategyCodec.encode(strategy)))
    }

    // ── levelOf：维度键映射，未知维度回默认档 ────────────────────────────

    @Test
    fun levelOfMapsDimKeys() {
        val strategy = TalkerStrategy(lying = TALKER_NEVER, promising = TALKER_FREE, exemplifying = TALKER_ASSESS)
        assertEquals(TALKER_NEVER, strategy.levelOf(TALKER_DIM_LYING))
        assertEquals(TALKER_FREE, strategy.levelOf(TALKER_DIM_PROMISING))
        assertEquals(TALKER_ASSESS, strategy.levelOf(TALKER_DIM_EXEMPLIFYING))
        assertEquals(TALKER_ASSESS, strategy.levelOf("privacy"))
    }
}
