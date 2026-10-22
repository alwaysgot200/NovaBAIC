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

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 情绪轨迹纯函数（E-g 慢车道，方案 §4.7）：7/30 日曲线、连续冷淡、温度走向、
 * 固定模板建议与情绪跟进。纪律：无事件不臆测、样本不足不出走向、跟进
 * 时效严格（3 天后才问、7 天过期、其后有新事件不翻旧账）。
 */
class EmotionTrendTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day = 86_400_000L

    /** 固定"今天中午 12 点"，日切分不受真实时钟影响。 */
    private val nowMs: Long =
        Instant.parse("2026-10-22T04:00:00Z").toEpochMilli() // = 上海 12:00

    private fun point(
        atMs: Long,
        label: String,
        score: Double = 1.0,
        intensity: Int = 3,
        state: String = "confirmed",
    ) = EmotionTrendPoint(atMs, listOf(EmotionJudge.LabelScore(label, score)), intensity, state)

    private fun calculate(vararg points: EmotionTrendPoint): EmotionTrend =
        EmotionTrendCalculator.calculate(points.toList(), nowMs, zone)

    // ── 情绪分 ─────────────────────────────────────────────────────────

    @Test
    fun moodScore_joyPositive_negativeNegative() {
        assertEquals(3.0, EmotionTrendCalculator.moodScore(point(0, EmotionLabels.JOY, intensity = 3)), 1e-9)
        assertEquals(-3.0, EmotionTrendCalculator.moodScore(point(0, EmotionLabels.SADNESS, intensity = 3)), 1e-9)
    }

    @Test
    fun moodScore_observationLabelsZero_neverFabricatesWarmth() {
        assertEquals(0.0, EmotionTrendCalculator.moodScore(point(0, EmotionLabels.PROBING, intensity = 5)), 1e-9)
        val noPositive = EmotionTrendPoint(0, listOf(EmotionJudge.LabelScore(EmotionLabels.JOY, 0.0)), 3, "watch")
        assertEquals(0.0, EmotionTrendCalculator.moodScore(noPositive), 1e-9)
    }

    @Test
    fun moodScore_dominantLabelWins() {
        val mixed = EmotionTrendPoint(
            0,
            listOf(
                EmotionJudge.LabelScore(EmotionLabels.SADNESS, 0.9),
                EmotionJudge.LabelScore(EmotionLabels.JOY, 0.3),
            ),
            4,
            "confirmed",
        )
        assertEquals(-4.0, EmotionTrendCalculator.moodScore(mixed), 1e-9)
    }

    // ── 空输入不臆测 ────────────────────────────────────────────────────

    @Test
    fun calculate_emptyPoints_yieldsZeroTrendWithNoAdviceNoFollowUp() {
        val trend = EmotionTrendCalculator.calculate(emptyList(), nowMs, zone)
        assertEquals(0, trend.eventCount)
        assertEquals(0L, trend.lastEventAtMs)
        assertEquals(EmotionDirection.FLAT, trend.direction)
        assertTrue(trend.advice.isEmpty())
        assertNull(trend.followUpTopic)
        assertTrue(trend.briefLines().isEmpty())
        assertEquals("", trend.stanceNote())
    }

    // ── 连续冷淡与固定模板建议 ──────────────────────────────────────────

    @Test
    fun calculate_threeColdDays_producesFixedTemplateAdvice() {
        val trend = calculate(
            point(nowMs - 2 * day, EmotionLabels.PERFUNCTORY, intensity = 2),
            point(nowMs - 1 * day, EmotionLabels.LOW, intensity = 2),
            point(nowMs, EmotionLabels.PERFUNCTORY, intensity = 2),
        )
        assertEquals(3, trend.coldStreakDays)
        assertEquals(
            listOf("客户兴趣衰减（连续冷淡 3 天），建议换策略或休眠两周，别硬聊"),
            trend.advice,
        )
    }

    @Test
    fun calculate_twoColdDays_noColdAdvice() {
        val trend = calculate(
            point(nowMs - 1 * day, EmotionLabels.PERFUNCTORY, intensity = 2),
            point(nowMs, EmotionLabels.PERFUNCTORY, intensity = 2),
        )
        assertEquals(2, trend.coldStreakDays)
        assertFalse(trend.advice.any { it.contains("换策略") })
    }

    @Test
    fun calculate_coldStreakBreaksOnWarmDay() {
        val trend = calculate(
            point(nowMs - 3 * day, EmotionLabels.LOW, intensity = 2),
            point(nowMs - 2 * day, EmotionLabels.JOY, intensity = 4),
            point(nowMs - 1 * day, EmotionLabels.PERFUNCTORY, intensity = 2),
            point(nowMs, EmotionLabels.PERFUNCTORY, intensity = 2),
        )
        assertEquals(2, trend.coldStreakDays)
    }

    // ── 温度走向（近 7 日 vs 前 7 日） ─────────────────────────────────

    @Test
    fun calculate_warmingWhenRecentWeekBeatsPrevious() {
        val trend = calculate(
            point(nowMs - 10 * day, EmotionLabels.SADNESS, intensity = 3),
            point(nowMs - 2 * day, EmotionLabels.JOY, intensity = 4),
        )
        assertEquals(EmotionDirection.WARMING, trend.direction)
        assertTrue(trend.delta >= EmotionTrendCalculator.DIRECTION_DELTA)
        assertTrue(trend.advice.any { it.contains("适度推进") })
    }

    @Test
    fun calculate_coolingWhenRecentWeekDrops() {
        val trend = calculate(
            point(nowMs - 10 * day, EmotionLabels.JOY, intensity = 4),
            point(nowMs - 2 * day, EmotionLabels.SADNESS, intensity = 3),
        )
        assertEquals(EmotionDirection.COOLING, trend.direction)
        assertTrue(trend.delta <= -EmotionTrendCalculator.DIRECTION_DELTA)
        assertTrue(trend.advice.any { it.contains("放缓推进") })
    }

    @Test
    fun calculate_singleSidedSamples_stayFlat() {
        val trend = calculate(point(nowMs - 1 * day, EmotionLabels.JOY, intensity = 5))
        assertEquals(EmotionDirection.FLAT, trend.direction)
        assertEquals(0.0, trend.delta, 1e-9)
    }

    // ── 情绪跟进（低落登记 3 天后"最近好点没"） ─────────────────────────

    @Test
    fun followUp_dueAfterThreeDaysWithoutNewEvents() {
        val trend = calculate(point(nowMs - 4 * day, EmotionLabels.LOW, intensity = 3))
        assertEquals(EmotionTrendCalculator.FOLLOW_UP_TOPIC, trend.followUpTopic)
    }

    @Test
    fun followUp_notDueBeforeDelay_andExpiredAfterWindow() {
        assertNull(calculate(point(nowMs - 2 * day, EmotionLabels.LOW, intensity = 3)).followUpTopic)
        assertNull(calculate(point(nowMs - 8 * day, EmotionLabels.LOW, intensity = 3)).followUpTopic)
    }

    @Test
    fun followUp_washedOutByNewerEvents() {
        val trend = calculate(
            point(nowMs - 4 * day, EmotionLabels.LOW, intensity = 3),
            point(nowMs - 1 * day, EmotionLabels.JOY, intensity = 4),
        )
        assertNull(trend.followUpTopic)
    }

    @Test
    fun followUp_requiresLowSpiritWithIntensity() {
        assertNull(calculate(point(nowMs - 4 * day, EmotionLabels.LOW, intensity = 2)).followUpTopic)
        assertNull(calculate(point(nowMs - 4 * day, EmotionLabels.PROBING, intensity = 4)).followUpTopic)
    }

    // ── 消费面文案（固定模板，非 LLM） ─────────────────────────────────

    @Test
    fun briefLines_coverMoodDirectionColdAndAdvice() {
        val trend = calculate(
            point(nowMs - 10 * day, EmotionLabels.JOY, intensity = 4),
            point(nowMs - 2 * day, EmotionLabels.PERFUNCTORY, intensity = 2),
            point(nowMs - 1 * day, EmotionLabels.PERFUNCTORY, intensity = 2),
            point(nowMs, EmotionLabels.PERFUNCTORY, intensity = 2),
        )
        val lines = trend.briefLines()
        assertTrue(lines.first().startsWith("近 7 日情绪均值"))
        assertTrue(lines.any { it.startsWith("关系温度：降温") })
        assertTrue(lines.any { it == "连续冷淡：3 天" })
        assertTrue(lines.any { it.startsWith("建议：") })
    }

    @Test
    fun stanceNote_summarisesTemperatureAndAdvice() {
        val trend = calculate(
            point(nowMs - 1 * day, EmotionLabels.PERFUNCTORY, intensity = 2),
            point(nowMs, EmotionLabels.PERFUNCTORY, intensity = 2),
        )
        val note = trend.stanceNote()
        assertTrue(note.startsWith("关系温度："))
        assertTrue(note.contains("连续冷淡 2 天"))
    }

    @Test
    fun briefLines_andStanceNote_emptyWithoutEvents() {
        val trend = calculate()
        assertTrue(trend.briefLines().isEmpty())
        assertEquals("", trend.stanceNote())
    }
}
