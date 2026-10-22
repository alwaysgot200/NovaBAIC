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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基线统计 + 滚动重算 + 偏离计算（E-b）：滚动窗口只取 30 天内样本、统计口径与判定
 * 引擎 trim 一致、偏离阈值严格不等号（与 EmotionJudge 基线证据逐字节对齐）、
 * 样本不足绝不臆测（原则 2）。
 */
class EmotionBaselineTest {

    private val nowMs = 1_700_000_000_000L
    private val minute = 60_000L
    private val day = 86_400_000L

    private fun sample(
        text: String,
        at: Long,
        question: Boolean = false,
        follow: Boolean = false,
    ) = EmotionMessageSample(text, at, question, follow)

    private fun judgeBaseline(
        avgLen: Double = 30.0,
        avgIntervalMs: Long = 60_000,
        samples: Int = 50,
    ) = EmotionJudge.Baseline(avgLen, avgIntervalMs, 0.2, 0.3, samples)

    // ── 统计 ────────────────────────────────────────────────────────────

    @Test
    fun calculate_fullStatsAcrossWindow() {
        val baseline = EmotionBaselineCalculator.calculate(
            listOf(
                sample("好的", nowMs - 4 * minute),
                sample("这是一条比较长的消息内容哦", nowMs - 3 * minute), // 13 字
                sample("嗯", nowMs - 2 * minute, follow = true), // 1 字 + 主动追加
                sample("对了那个文件发我下", nowMs - minute), // 9 字
            ),
            nowMs = nowMs,
        )
        assertEquals(4, baseline.sampleCount)
        assertEquals(6.25, baseline.avgLen, 1e-9) // (2+13+1+9)/4
        assertEquals(60_000L, baseline.avgIntervalMs) // 三段相邻间隔均 1 分钟
        assertEquals(0.5, baseline.shortReplyRatio, 1e-9) // "好的"+"嗯"
        assertEquals(0.25, baseline.activeFollowRatio, 1e-9) // 仅第 3 条追加分
        assertEquals(nowMs, baseline.updatedAt)
    }

    @Test
    fun calculate_rollingWindowDropsAgedSamples() {
        val recent = (1..10).map { sample("一二三四五六七八九十", nowMs - it * minute) } // 各 10 字
        val aged = listOf(
            sample("老消息一整句话占很多很多字数", nowMs - 31 * day),
            sample("更老的消息也一样占很多很多字", nowMs - 40 * day),
        )
        val baseline = EmotionBaselineCalculator.calculate(aged + recent, nowMs = nowMs)
        assertEquals(10, baseline.sampleCount)
        assertEquals(10.0, baseline.avgLen, 1e-9) // 窗口外样本不参与统计
        assertTrue(baseline.toJudgeBaseline().usable) // 10 样本恰好达可用线
    }

    @Test
    fun calculate_windowDaysIsConfigurable() {
        val samples = listOf(
            sample("旧消息", nowMs - 2 * day),
            sample("新消息", nowMs - day / 2),
        )
        assertEquals(1, EmotionBaselineCalculator.calculate(samples, nowMs = nowMs, windowDays = 1).sampleCount)
        assertEquals(2, EmotionBaselineCalculator.calculate(samples, nowMs = nowMs).sampleCount)
    }

    @Test
    fun calculate_singleSample_noInterval() {
        val baseline = EmotionBaselineCalculator.calculate(
            listOf(sample("好的", nowMs - minute)),
            nowMs = nowMs,
        )
        assertEquals(1, baseline.sampleCount)
        assertEquals(2.0, baseline.avgLen, 1e-9)
        assertEquals(0L, baseline.avgIntervalMs) // 无相邻对
        assertEquals(1.0, baseline.shortReplyRatio, 1e-9)
        assertFalse(baseline.toJudgeBaseline().usable) // 样本不足不判偏离
    }

    @Test
    fun calculate_emptyReturnsZeroUnusableBaseline() {
        val baseline = EmotionBaselineCalculator.calculate(emptyList(), nowMs = nowMs)
        assertEquals(0, baseline.sampleCount)
        assertEquals(0.0, baseline.avgLen, 1e-9)
        assertEquals(0L, baseline.avgIntervalMs)
        assertEquals(nowMs, baseline.updatedAt)
        assertFalse(baseline.toJudgeBaseline().usable)
    }

    @Test
    fun calculate_unsortedInputMatchesSorted() {
        val samples = listOf(
            sample("第三条消息比较长一些", nowMs - 3 * minute),
            sample("一", nowMs - minute),
            sample("第二条", nowMs - 2 * minute),
        )
        val sorted = EmotionBaselineCalculator.calculate(samples.sortedBy { it.timestampMs }, nowMs = nowMs)
        val shuffled = EmotionBaselineCalculator.calculate(samples.shuffled(), nowMs = nowMs)
        assertEquals(sorted, shuffled)
    }

    @Test
    fun calculate_shortReplyExcludesQuestionsAndBlanks() {
        val baseline = EmotionBaselineCalculator.calculate(
            listOf(
                sample("在吗？", nowMs - 4 * minute), // 提问是主动参与，不算短回复
                sample("  ", nowMs - 3 * minute), // 空白不算
                sample("好的", nowMs - 2 * minute),
                sample("嗯好", nowMs - minute),
            ),
            nowMs = nowMs,
        )
        assertEquals(0.5, baseline.shortReplyRatio, 1e-9) // 仅 "好的"+"嗯好"
    }

    @Test
    fun toJudgeBaseline_mapsAllFields() {
        val baseline = EmotionBaselineCalculator.calculate(
            (1..12).map { sample("消息内容示例", nowMs - it * minute) },
            nowMs = nowMs,
        )
        val judge = baseline.toJudgeBaseline()
        assertEquals(baseline.avgLen, judge.avgLen, 1e-9)
        assertEquals(baseline.avgIntervalMs, judge.avgIntervalMs)
        assertEquals(baseline.shortReplyRatio, judge.shortReplyRatio, 1e-9)
        assertEquals(baseline.activeFollowRatio, judge.activeFollowRatio, 1e-9)
        assertEquals(baseline.sampleCount, judge.sampleCount)
    }

    // ── 偏离计算（阈值唯一来源，与判定引擎基线证据同口径） ────────────────

    @Test
    fun deviation_flagsLengthDropAndIntervalStretch() {
        val dev = EmotionBaselineCalculator.deviation(2, 15 * minute, judgeBaseline())
        assertTrue(dev.lengthDrop)
        assertTrue(dev.intervalStretch)
        assertFalse(dev.lengthRise)
        assertFalse(dev.quickReply)
        assertEquals(2.0 / 30.0, dev.lenRatio!!, 1e-9)
        assertEquals(15.0, dev.gapRatio!!, 1e-9)
    }

    @Test
    fun deviation_flagsLongReplyAndQuickReply() {
        val dev = EmotionBaselineCalculator.deviation(60, 10_000, judgeBaseline())
        assertTrue(dev.lengthRise)
        assertTrue(dev.quickReply)
        assertFalse(dev.lengthDrop)
        assertFalse(dev.intervalStretch)
        assertEquals(2.0, dev.lenRatio!!, 1e-9)
        assertEquals(10_000.0 / 60_000.0, dev.gapRatio!!, 1e-9)
    }

    @Test
    fun deviation_withinBaseline_noFlags() {
        // 话少型基线（情形 D）："好的" 2 字 + 40 秒间隔全在基线内，零信号。
        val dev = EmotionBaselineCalculator.deviation(2, 40_000, judgeBaseline(avgLen = 4.0, avgIntervalMs = 30_000))
        assertFalse(dev.lengthDrop)
        assertFalse(dev.lengthRise)
        assertFalse(dev.intervalStretch)
        assertFalse(dev.quickReply)
        assertEquals(0.5, dev.lenRatio!!, 1e-9)
    }

    @Test
    fun deviation_unusableBaseline_allEmpty() {
        listOf(
            judgeBaseline(samples = 9), // 样本量不足
            judgeBaseline(avgLen = 0.0, samples = 50), // 均长无意义
        ).forEach { baseline ->
            val dev = EmotionBaselineCalculator.deviation(2, 15 * minute, baseline)
            assertNull(dev.lenRatio)
            assertNull(dev.gapRatio)
            assertFalse(dev.lengthDrop)
            assertFalse(dev.lengthRise)
            assertFalse(dev.intervalStretch)
            assertFalse(dev.quickReply)
        }
    }

    @Test
    fun deviation_missingLenOrGap_nullRatioNoFlag() {
        val dev = EmotionBaselineCalculator.deviation(0, null, judgeBaseline())
        assertNull(dev.lenRatio) // 空消息不判篇幅
        assertNull(dev.gapRatio) // 间隔不可得不臆测
        assertFalse(dev.lengthDrop)
        assertFalse(dev.lengthRise)
        assertFalse(dev.intervalStretch)
        assertFalse(dev.quickReply)
    }

    @Test
    fun deviation_thresholdBoundaries_areStrict() {
        val base = judgeBaseline(avgLen = 10.0, avgIntervalMs = 10_000)
        // 恰在门槛上不触发（判定引擎用严格不等号，边界必须锁死）
        val atBoundary = EmotionBaselineCalculator.deviation(3, 18_000, base)
        assertFalse(atBoundary.lengthDrop) // 3 = 10×0.3
        assertFalse(atBoundary.intervalStretch) // 18000 = 10000×1.8
        val atRiseBoundary = EmotionBaselineCalculator.deviation(15, 4_000, base)
        assertFalse(atRiseBoundary.lengthRise) // 15 = 10×1.5
        assertFalse(atRiseBoundary.quickReply) // 4000 = 10000×0.4
        // 过界即触发
        val past = EmotionBaselineCalculator.deviation(2, 18_001, base)
        assertTrue(past.lengthDrop)
        assertTrue(past.intervalStretch)
    }
}
