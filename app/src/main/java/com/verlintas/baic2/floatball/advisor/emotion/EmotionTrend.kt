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

/**
 * 情绪轨迹输入点（E-g 慢车道，方案 §4.7）：一条已落库判定事件的纯数据投影，
 * 由 emotion_events 行映射而来（见 `EmotionEventEntity.toTrendPoint`）。
 *
 * @property state EMOTION_STATE_* 三档（confirmed/watch/calm）
 */
data class EmotionTrendPoint(
    val createdAtMs: Long,
    val labels: List<EmotionJudge.LabelScore>,
    val intensity: Int,
    val state: String,
)

/** 单日情绪聚合（曲线上的一个点）：仅含有事件的日子（无事件日零信号，不臆测）。 */
data class EmotionDayMood(
    /** 当日 0 点（注入时区口径）。 */
    val dayStartMs: Long,
    /** 当日事件情绪分均值（[EmotionTrendCalculator.moodScore] 口径，-5..+5）。 */
    val avgScore: Double,
    val eventCount: Int,
)

/** 关系温度走向：近 7 日均值 vs 前 7 日均值（样本不足一律 [FLAT]，绝不臆测）。 */
enum class EmotionDirection { WARMING, FLAT, COOLING }

/**
 * 联系人情绪轨迹（E-g 慢车道产物，方案 §4.7）：7/30 日曲线 + 连续冷淡计数 +
 * 温度走向 + 固定模板行动建议 + 情绪跟进话题。消费面三处（周报段 / 行动建议 /
 * 话题跟进）只读本结构，**零 LLM、固定模板防提示词污染**。
 */
data class EmotionTrend(
    /** 近 7 日曲线（仅含事件日，时间升序）。 */
    val curve7: List<EmotionDayMood>,
    /** 近 30 日曲线（同上）。 */
    val curve30: List<EmotionDayMood>,
    /** 连续冷淡日数（从最近事件日回溯，冷淡日口径见 [EmotionTrendCalculator.COLD_DAY_SCORE]）。 */
    val coldStreakDays: Int,
    val direction: EmotionDirection,
    /** 近 7 日均值 − 前 7 日均值（两段都有样本才可判）。 */
    val delta: Double,
    val eventCount: Int,
    /** 最近一条事件时间（0 = 无事件）。 */
    val lastEventAtMs: Long,
    /** 行动建议（固定模板，随计算定格，非 LLM 生成）。 */
    val advice: List<String>,
    /** 到期的情绪跟进话题（如低落 3 天后"最近好点没"）；null = 不到期/无登记。 */
    val followUpTopic: String?,
) {
    /**
     * 周报"情绪趋势"段（BriefCompiler 确定性附加面，照 topicDirections 口径：
     * 不进 LLM 提示词、落库前拼装）。无事件返回空表（GoalTab 空段不渲染）。
     */
    fun briefLines(): List<String> {
        if (eventCount <= 0) return emptyList()
        val lines = ArrayList<String>(4)
        val weekAvg = curve7.map { it.avgScore }.takeIf { it.isNotEmpty() }?.average()
        if (weekAvg != null) {
            val negativeDays = curve7.count { it.avgScore <= EmotionTrendCalculator.COLD_DAY_SCORE }
            lines += "近 7 日情绪均值 ${fmt(weekAvg)}（偏负面 $negativeDays 天 / 共 ${curve7.size} 天有交流）"
        }
        lines += "关系温度：${directionLabel()}${
            if (direction != EmotionDirection.FLAT) "（较前一周 ${fmt(delta)}）" else ""
        }"
        if (coldStreakDays > 0) lines += "连续冷淡：$coldStreakDays 天"
        advice.forEach { lines += "建议：$it" }
        return lines
    }

    /** 行动建议家的一句话态势（PlanFacts.emotionNote，选谋略时须尊重）。 */
    fun stanceNote(): String {
        if (eventCount <= 0) return ""
        val parts = ArrayList<String>(3)
        parts += "关系温度：${directionLabel()}"
        if (coldStreakDays > 0) parts += "连续冷淡 $coldStreakDays 天"
        val note = parts.joinToString("，")
        val first = advice.firstOrNull()
        return if (first == null) note else "$note；$first"
    }

    private fun directionLabel(): String = when (direction) {
        EmotionDirection.WARMING -> "升温"
        EmotionDirection.FLAT -> "持平"
        EmotionDirection.COOLING -> "降温"
    }

    private fun fmt(value: Double): String = String.format("%.1f", value)
}

/**
 * 情绪趋势计算（纯函数零依赖，JVM 直接单测；方案 §4.7）。
 *
 * 纪律：**零 LLM**（固定模板输出）、**无事件不臆测**（空输入 → 空轨迹）、
 * 样本不足不出走向（单边无样本 = FLAT）。评分只看主导标签极性与强度，
 * 与判定引擎同一标签口径（[EmotionLabels]），不引入第二套词表。
 */
object EmotionTrendCalculator {

    /** 冷淡日门槛：当日情绪分均值 ≤ 此值（≈平均强度 1 的负面日）。 */
    const val COLD_DAY_SCORE = -1.0

    /** 温度走向门槛：近 7 日均值较前 7 日变化超过 ±此值才判升/降。 */
    const val DIRECTION_DELTA = 1.0

    /** 连续冷淡达到此天数才给"换策略/休眠"建议（方案例句口径）。 */
    const val COLD_STREAK_ADVICE_DAYS = 3

    /** 情绪跟进延迟：低落登记满 [FOLLOW_UP_DELAY_MS] 后才到期。 */
    const val FOLLOW_UP_DELAY_MS = 3L * 24 * 60 * 60 * 1000

    /** 跟进窗口：超过 [FOLLOW_UP_WINDOW_MS] 视为过期不再追（别翻旧账）。 */
    const val FOLLOW_UP_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** 跟进话题文案（TopicEngine 选题源，指纹去重防重复问）。 */
    const val FOLLOW_UP_TOPIC = "最近好点没"

    /** 低落跟进触发标签（低落/悲伤强度达标才算"登记"）。 */
    private val LOW_SPIRIT_LABELS = listOf(EmotionLabels.LOW, EmotionLabels.SADNESS)

    private const val DAY_MS = 86_400_000L

    /**
     * 单条事件情绪分：主导标签极性（喜 + / 负面 − / 其余 0）× 强度（1..5）。
     * 无正得分标签 → 0（观察类信号不计温）。
     */
    fun moodScore(point: EmotionTrendPoint): Double {
        val top = point.labels.filter { it.score > 0.0 }.maxByOrNull { it.score } ?: return 0.0
        val sign = when {
            top.label == EmotionLabels.JOY -> 1.0
            top.label in EmotionLabels.NEGATIVE -> -1.0
            else -> 0.0
        }
        return sign * point.intensity.coerceIn(1, 5)
    }

    /**
     * 轨迹计算。[nowMs] 决定 7/30 日窗口与跟进到期；[zone] 只影响日切分。
     * 空输入返回零轨迹（eventCount=0，建议/跟进恒空）。
     */
    fun calculate(
        points: List<EmotionTrendPoint>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): EmotionTrend {
        if (points.isEmpty()) {
            return EmotionTrend(
                curve7 = emptyList(), curve30 = emptyList(), coldStreakDays = 0,
                direction = EmotionDirection.FLAT, delta = 0.0, eventCount = 0,
                lastEventAtMs = 0L, advice = emptyList(), followUpTopic = null,
            )
        }
        val sorted = points.sortedBy { it.createdAtMs }
        val curve30 = dayMoods(sorted.filter { it.createdAtMs >= nowMs - 30 * DAY_MS }, zone)
        val curve7 = dayMoods(sorted.filter { it.createdAtMs >= nowMs - 7 * DAY_MS }, zone)
        val prevWeek = sorted.filter {
            it.createdAtMs in (nowMs - 14 * DAY_MS) until (nowMs - 7 * DAY_MS)
        }
        val coldStreak = coldStreakDays(curve30)
        val recentAvg = curve7.map { it.avgScore }.takeIf { it.isNotEmpty() }?.average()
        val prevAvg = prevWeek.map { moodScore(it) }.takeIf { it.isNotEmpty() }?.average()
        val direction = when {
            recentAvg == null || prevAvg == null -> EmotionDirection.FLAT
            recentAvg - prevAvg >= DIRECTION_DELTA -> EmotionDirection.WARMING
            recentAvg - prevAvg <= -DIRECTION_DELTA -> EmotionDirection.COOLING
            else -> EmotionDirection.FLAT
        }
        val delta = if (recentAvg != null && prevAvg != null) recentAvg - prevAvg else 0.0
        val followUp = followUpTopic(sorted, nowMs)
        val advice = buildAdvice(direction, coldStreak)
        return EmotionTrend(
            curve7 = curve7,
            curve30 = curve30,
            coldStreakDays = coldStreak,
            direction = direction,
            delta = delta,
            eventCount = sorted.size,
            lastEventAtMs = sorted.last().createdAtMs,
            advice = advice,
            followUpTopic = followUp,
        )
    }

    /**
     * 低落跟进（方案例：低落 3 天后主动问"好点没"）：最近一条低落/悲伤登记
     * 满 [FOLLOW_UP_DELAY_MS]、仍在 [FOLLOW_UP_WINDOW_MS] 内、且其后无新事件
     * （后有新情绪 = 已变化，不翻旧账）才到期。emotion_events 即登记本身，
     * 不另建跟进表。
     */
    fun followUpTopic(sorted: List<EmotionTrendPoint>, nowMs: Long): String? {
        val lastLow = sorted.lastOrNull { point ->
            point.intensity >= 3 &&
                point.labels.any { it.label in LOW_SPIRIT_LABELS && it.score > 0.0 }
        } ?: return null
        if (sorted.any { it.createdAtMs > lastLow.createdAtMs }) return null
        val gap = nowMs - lastLow.createdAtMs
        if (gap < FOLLOW_UP_DELAY_MS || gap > FOLLOW_UP_WINDOW_MS) return null
        return FOLLOW_UP_TOPIC
    }

    /** 连续冷淡日数：从最近事件日回溯数冷淡日，遇到非冷淡日即停。 */
    private fun coldStreakDays(curve: List<EmotionDayMood>): Int {
        var streak = 0
        for (day in curve.asReversed()) {
            if (day.avgScore > COLD_DAY_SCORE) break
            streak++
        }
        return streak
    }

    /** 行动建议（固定模板，只随证据触发，绝不生成自由文本）。 */
    private fun buildAdvice(direction: EmotionDirection, coldStreak: Int): List<String> {
        val out = ArrayList<String>(2)
        if (coldStreak >= COLD_STREAK_ADVICE_DAYS) {
            out += "客户兴趣衰减（连续冷淡 $coldStreak 天），建议换策略或休眠两周，别硬聊"
        }
        when (direction) {
            EmotionDirection.COOLING ->
                if (out.isEmpty()) out += "关系温度在降：放缓推进，先回暖再谈正事"
            EmotionDirection.WARMING ->
                out += "关系温度在升：可按阶段判据适度推进"
            EmotionDirection.FLAT -> Unit
        }
        return out
    }

    /** 按注入时区聚合到"日"（仅含有事件日，无事件日零信号不臆测）。 */
    private fun dayMoods(points: List<EmotionTrendPoint>, zone: ZoneId): List<EmotionDayMood> =
        points.groupBy { dayStart(it.createdAtMs, zone) }
            .toSortedMap()
            .map { (dayStart, dayPoints) ->
                EmotionDayMood(
                    dayStartMs = dayStart,
                    avgScore = dayPoints.map { moodScore(it) }.average(),
                    eventCount = dayPoints.size,
                )
            }

    private fun dayStart(timestampMs: Long, zone: ZoneId): Long =
        runCatching {
            Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrDefault(timestampMs)
}
