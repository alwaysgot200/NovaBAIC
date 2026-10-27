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

package com.verlintas.baic2.floatball.auto

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动档分段耗时账（2026-10-27「回复慢」归因）：段名规范化、`auto_timing:` 一行
 * 渲染口径、段数上限、同步/挂起段的计时与异常记账。纯 JVM，零 Android 依赖。
 */
class AutoTimingTest {

    // ── render：台账/日志同一行口径 ──

    @Test
    fun render_empty_isBlank() {
        assertEquals("", AutoTiming.render(emptyList()))
    }

    @Test
    fun render_prefixAndMilliseconds() {
        assertEquals(
            "auto_timing:frame=12ms,ocr=230ms",
            AutoTiming.render(listOf("frame" to 12L, "ocr" to 230L)),
        )
    }

    @Test
    fun render_negativeClampedToZero_andNamesSanitized() {
        assertEquals(
            "auto_timing:llm_1=0ms,send_shizuku=5ms",
            AutoTiming.render(listOf("LLM 1" to -7L, "send-shizuku" to 5L)),
        )
    }

    // ── sanitize：台账 reason 里不带特殊字符 ──

    @Test
    fun sanitize_lowercasesAndReplacesSpecials() {
        assertEquals("fg_shizuku", AutoTiming.sanitize("fg Shizuku"))
        assertEquals("a_b_c", AutoTiming.sanitize("A:B/C"))
    }

    @Test
    fun sanitize_emptyFallsBackToStage_andTruncates() {
        assertEquals("stage", AutoTiming.sanitize("   "))
        assertEquals("stage", AutoTiming.sanitize(""))
        assertEquals(24, AutoTiming.sanitize("segment_with_a_very_long_name_beyond_limit").length)
    }

    @Test
    fun indexed_numbersFromOne() {
        assertEquals("llm1", AutoTiming.indexed("llm", 0))
        assertEquals("llm3", AutoTiming.indexed("LLM", 2))
    }

    // ── TimingTrace：累加、上限、同步/挂起段计时 ──

    @Test
    fun trace_addAndRenderInOrder() {
        val trace = TimingTrace().add("prep", 3L).add("llm", 800L)
        assertEquals(listOf("prep" to 3L, "llm" to 800L), trace.snapshot())
        assertEquals("auto_timing:prep=3ms,llm=800ms", trace.render())
    }

    @Test
    fun trace_emptyRendersBlank() {
        assertTrue(TimingTrace().isEmpty)
        assertEquals("", TimingTrace().render())
    }

    @Test
    fun trace_capsStageCount() {
        val trace = TimingTrace()
        repeat(AutoTiming.MAX_STAGES + 6) { trace.add("s$it", 1L) }
        assertEquals(AutoTiming.MAX_STAGES, trace.snapshot().size)
        // 超限后首个被写入的仍是 s0，末位是上限内的最后一段。
        assertEquals("s0", trace.snapshot().first().first)
        assertEquals("s${AutoTiming.MAX_STAGES - 1}", trace.snapshot().last().first)
    }

    @Test
    fun stage_returnsBlockValue_andRecordsElapsed() {
        val trace = TimingTrace()
        val value = trace.stage("ocr") {
            Thread.sleep(20L)
            "ok"
        }
        assertEquals("ok", value)
        val (name, ms) = trace.snapshot().single()
        assertEquals("ocr", name)
        assertTrue("20ms sleep 至少记到 10ms，实测 $ms", ms >= 10L)
    }

    @Test
    fun stage_throwingBlock_stillRecordsElapsed() {
        val trace = TimingTrace()
        assertThrows(IllegalStateException::class.java) {
            trace.stage<Unit>("frame") { throw IllegalStateException("boom") }
        }
        // 异常照常抛出，但耗时先记账（finally）：排障能看到「卡在哪一段」。
        assertEquals("frame", trace.snapshot().single().first)
    }

    @Test
    fun stageSuspend_returnsBlockValue_andRecordsElapsed() = runBlocking {
        val trace = TimingTrace()
        val value = trace.stageSuspend("llm") {
            delay(20L)
            42
        }
        assertEquals(42, value)
        val (name, ms) = trace.snapshot().single()
        assertEquals("llm", name)
        assertTrue("20ms delay 至少记到 10ms，实测 $ms", ms >= 10L)
    }

    @Test
    fun stageSuspend_throwingBlock_stillRecordsElapsed() {
        val trace = TimingTrace()
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                trace.stageSuspend<Unit>("send") { throw IllegalStateException("boom") }
            }
        }
        assertEquals("send", trace.snapshot().single().first)
    }
}
