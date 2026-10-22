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

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为线索提取（EmotionCueExtractor，E-d）：折叠/间隔/追问/洗白/深夜/索取/
 * 窗口与样本标志。采集纪律单测锁死——不可得的线索传 false/null，绝不臆测。
 */
class EmotionCueExtractorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun hourMs(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    // ── 折叠与清洗 ────────────────────────────────────────────────────────

    @Test
    fun empty_input_yields_empty_extraction() {
        val out = EmotionCueExtractor.extract(emptyList(), zone)
        assertEquals(0, out.window.size)
        assertEquals(0, out.samples.size)
        assertNull(out.cue.replyIntervalMs)
        assertFalse(out.cue.hasActiveQuestion)
        assertFalse(out.cue.hasFollowUp)
        assertFalse(out.cue.nightInitiative)
    }

    @Test
    fun consecutive_same_text_collapses_to_latest_timestamp() {
        // 同屏重复抓帧会产生连续同文行：折叠防短确认 streak 虚增（原则 3 假阳性）。
        val out = EmotionCueExtractor.extract(
            listOf(
                EmotionCueExtractor.IncomingSample("好的", hourMs(22, 10, 0)),
                EmotionCueExtractor.IncomingSample("好的", hourMs(22, 10, 5)),
                EmotionCueExtractor.IncomingSample("好的", hourMs(22, 10, 9)),
            ),
            zone,
        )
        assertEquals(1, out.samples.size)
        assertEquals(hourMs(22, 10, 9), out.samples[0].timestampMs)
        assertEquals(0, out.window.size)
    }

    @Test
    fun blank_texts_are_dropped() {
        val out = EmotionCueExtractor.extract(
            listOf(
                EmotionCueExtractor.IncomingSample("   ", hourMs(22, 10, 0)),
                EmotionCueExtractor.IncomingSample("在吗", hourMs(22, 10, 1)),
            ),
            zone,
        )
        assertEquals(1, out.samples.size)
        assertEquals("在吗", out.samples[0].text)
    }

    // ── 回复间隔（原则 2 原料）────────────────────────────────────────────

    @Test
    fun reply_interval_is_gap_to_previous_incoming() {
        val out = EmotionCueExtractor.extract(
            listOf(
                EmotionCueExtractor.IncomingSample("先看看", hourMs(22, 10, 0)),
                EmotionCueExtractor.IncomingSample("然后呢", hourMs(22, 12, 0)),
            ),
            zone,
        )
        assertEquals(2 * 60 * 60 * 1000L, out.cue.replyIntervalMs)
    }

    @Test
    fun reply_interval_null_when_timestamp_missing_or_rewound() {
        val missing = EmotionCueExtractor.extract(
            listOf(
                EmotionCueExtractor.IncomingSample("先看看", 0L),
                EmotionCueExtractor.IncomingSample("然后呢", hourMs(22, 12, 0)),
            ),
            zone,
        )
        assertNull(missing.cue.replyIntervalMs)

        // 时钟回拨：间隔为负不可信，按不可得处理。
        val rewound = EmotionCueExtractor.extract(
            listOf(
                EmotionCueExtractor.IncomingSample("先看看", hourMs(22, 12, 0)),
                EmotionCueExtractor.IncomingSample("然后呢", hourMs(22, 10, 0)),
            ),
            zone,
        )
        assertNull(rewound.cue.replyIntervalMs)
    }

    // ── 追问 / 洗白（原则 4）─────────────────────────────────────────────

    @Test
    fun question_marks_flag_active_question_in_cue_window_and_samples() {
        val out = EmotionCueExtractor.extract(
            listOf(
                EmotionCueExtractor.IncomingSample("这个怎么弄？", hourMs(22, 10, 0)),
                EmotionCueExtractor.IncomingSample("还有个事", hourMs(22, 10, 3)),
                EmotionCueExtractor.IncomingSample("明天几点 in?", hourMs(22, 10, 6)),
            ),
            zone,
        )
        assertTrue(out.cue.hasActiveQuestion)
        assertTrue(out.window[0].hasActiveQuestion)
        assertFalse(out.window[1].hasActiveQuestion)
        assertTrue(out.samples[0].hasActiveQuestion)
        assertFalse(out.samples[1].hasActiveQuestion)
        assertTrue(out.samples[2].hasActiveQuestion)
    }

    @Test
    fun follow_up_wash_fires_after_short_confirm() {
        // 原则 4：紧邻前条短确认 + 本条实质 → 洗白触发器（清零冷淡计数）。
        val out = EmotionCueExtractor.extract(
            listOf(
                EmotionCueExtractor.IncomingSample("嗯嗯", hourMs(22, 10, 0)),
                EmotionCueExtractor.IncomingSample("对了那个方案你看了吗", hourMs(22, 10, 3)),
            ),
            zone,
        )
        assertTrue(out.cue.hasFollowUp)
        assertTrue(out.samples[1].hasFollowUp)
        assertFalse(out.samples[0].hasFollowUp)
    }

    @Test
    fun follow_up_absent_between_substantive_or_short_confirms() {
        val substantive = EmotionCueExtractor.extract(
            listOf(
                EmotionCueExtractor.IncomingSample("先看下方案", hourMs(22, 10, 0)),
                EmotionCueExtractor.IncomingSample("再看下排期", hourMs(22, 10, 3)),
            ),
            zone,
        )
        assertFalse(substantive.cue.hasFollowUp)

        // 连续短确认不算洗白（不然原则 3 永远不成立）。
        val shorts = EmotionCueExtractor.extract(
            listOf(
                EmotionCueExtractor.IncomingSample("嗯嗯", hourMs(22, 10, 0)),
                EmotionCueExtractor.IncomingSample("好的", hourMs(22, 10, 3)),
            ),
            zone,
        )
        assertFalse(shorts.cue.hasFollowUp)
    }

    // ── 深夜发起 ─────────────────────────────────────────────────────────

    @Test
    fun night_initiative_flags_small_hours_only() {
        val night = EmotionCueExtractor.extract(
            listOf(EmotionCueExtractor.IncomingSample("睡了吗", hourMs(22, 2, 30))),
            zone,
        )
        assertTrue(night.cue.nightInitiative)

        val noon = EmotionCueExtractor.extract(
            listOf(EmotionCueExtractor.IncomingSample("在吗", hourMs(22, 12, 0))),
            zone,
        )
        assertFalse(noon.cue.nightInitiative)

        // 时间戳不可得 → 绝不臆测深夜。
        val unknown = EmotionCueExtractor.extract(
            listOf(EmotionCueExtractor.IncomingSample("睡了吗", 0L)),
            zone,
        )
        assertFalse(unknown.cue.nightInitiative)
    }

    // ── 主动索取资料（复用 HandoffRules 实体强模式）───────────────────────

    @Test
    fun requested_material_hits_entity_strong_pattern() {
        val ask = EmotionCueExtractor.extract(
            listOf(EmotionCueExtractor.IncomingSample("把上次的照片发给我一下", hourMs(22, 12, 0))),
            zone,
        )
        assertTrue(ask.cue.requestedMaterial)

        val plain = EmotionCueExtractor.extract(
            listOf(EmotionCueExtractor.IncomingSample("今天天气不错", hourMs(22, 12, 0))),
            zone,
        )
        assertFalse(plain.cue.requestedMaterial)
    }

    // ── 窗口 / 样本口径 ──────────────────────────────────────────────────

    @Test
    fun window_excludes_current_while_samples_include_it() {
        val out = EmotionCueExtractor.extract(
            listOf(
                EmotionCueExtractor.IncomingSample("嗯嗯", hourMs(22, 10, 0)),
                EmotionCueExtractor.IncomingSample("好的", hourMs(22, 10, 3)),
                EmotionCueExtractor.IncomingSample("对了再问问你", hourMs(22, 10, 6)),
            ),
            zone,
        )
        assertEquals(2, out.window.size)
        assertEquals(3, out.samples.size)
        assertEquals("对了再问问你", out.samples.last().text)
        // 基线样本的洗白标志逐条独立标注（末条=当前来信，前条是短确认）。
        assertFalse(out.samples[0].hasFollowUp)
        assertFalse(out.samples[1].hasFollowUp)
        assertTrue(out.samples[2].hasFollowUp)
    }
}
