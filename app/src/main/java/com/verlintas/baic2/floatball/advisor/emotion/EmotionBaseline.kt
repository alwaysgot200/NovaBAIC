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

/**
 * 联系人滚动基线记录（情感分析师方案 §4.4 原则 2 / §4.7）：30 天窗口内对方来信的
 * 聊天习惯画像，供判定引擎偏离对照、画像页基线卡与慢车道趋势共用。
 *
 * 与 [EmotionJudge.Baseline] 的分工：本类是**可持久化记录**（带 updatedAt，
 * E-c 落 Room）；[EmotionJudge.Baseline] 是判定输入快照，经 [toJudgeBaseline] 转换。
 *
 * @property avgLen 窗口内消息平均字数（trim 后）
 * @property avgIntervalMs 相邻来信平均间隔（毫秒；样本 <2 为 0）
 * @property shortReplyRatio 短回复占比：≤[EmotionJudge.SHORT_REPLY_MAX_LEN] 字且非提问
 * @property activeFollowRatio 主动参与占比：含追问 / 主动追加
 * @property sampleCount 窗口内样本量（< [EmotionJudge.Baseline.MIN_SAMPLES] 不可判偏离）
 * @property updatedAt 最近一次滚动重算时间
 */
data class EmotionBaseline(
    val avgLen: Double,
    val avgIntervalMs: Long,
    val shortReplyRatio: Double,
    val activeFollowRatio: Double,
    val sampleCount: Int,
    val updatedAt: Long,
) {
    /** 转判定引擎输入快照。 */
    fun toJudgeBaseline(): EmotionJudge.Baseline = EmotionJudge.Baseline(
        avgLen = avgLen,
        avgIntervalMs = avgIntervalMs,
        shortReplyRatio = shortReplyRatio,
        activeFollowRatio = activeFollowRatio,
        sampleCount = sampleCount,
    )
}

/**
 * 基线样本：一条对方来信。行为线索不可得就传 false，绝不臆测（HeatRules 同口径）。
 *
 * @property text 来信原文（长度/短回复统计按 trim 后口径，与判定引擎一致）
 * @property timestampMs 来信时间戳（滚动窗口与相邻间隔的依据）
 */
data class EmotionMessageSample(
    val text: String,
    val timestampMs: Long,
    val hasActiveQuestion: Boolean = false,
    val hasFollowUp: Boolean = false,
)

/**
 * 单条消息相对基线的偏离（原则 2 的可解释透出：分屏"基线对照"与证据链共用）。
 * ratio 字段为 null = 不可判（空消息 / 基线无对应均值 / 基线样本不足）。
 */
data class BaselineDeviation(
    /** 本条字数 ÷ 基线均值。 */
    val lenRatio: Double?,
    /** 本条间隔 ÷ 基线均值间隔。 */
    val gapRatio: Double?,
    /** 篇幅骤降（疑似冷淡的行为证据）。 */
    val lengthDrop: Boolean,
    /** 篇幅高投入（正面行为证据）。 */
    val lengthRise: Boolean,
    /** 间隔拉长（疑似冷淡）。 */
    val intervalStretch: Boolean,
    /** 回复加快（热度上升）。 */
    val quickReply: Boolean,
)

/**
 * 基线统计 + 滚动重算 + 偏离计算（纯函数零依赖，JVM 直接单测）。
 *
 * 1. **统计**：窗口内样本 → 平均长度 / 平均间隔 / 短回复占比 / 主动参与占比；
 * 2. **滚动重算**：只取 `nowMs` 前 [DEFAULT_WINDOW_DAYS] 天内的样本（方案 D4 可配），
 *    每次全量重算，无增量状态——样本即事实来源，重算恒定幂等；
 * 3. **偏离计算**：[deviation] 是偏离阈值的**唯一来源**——判定引擎基线证据与
 *    UI 基线对照共用，避免两套阈值漂移。样本不足（未达
 *    [EmotionJudge.Baseline.MIN_SAMPLES]）一律不出偏离，绝不按全局绝对值硬判人格差异。
 */
object EmotionBaselineCalculator {

    /** 滚动窗口天数（方案 D4：默认 30 天，AdvisorPrefs 可调）。 */
    const val DEFAULT_WINDOW_DAYS = 30

    private const val DAY_MS = 86_400_000L

    /** 篇幅骤降门槛：本条 < 基线均值 × 此比（"好的" vs 话痨日常）。 */
    const val LENGTH_DROP_RATIO = 0.3

    /** 篇幅高投入门槛：本条 > 基线均值 × 此比。 */
    const val LONG_REPLY_RATIO = 1.5

    /** 间隔拉长门槛：本条间隔 > 基线均值 × 此比。 */
    const val INTERVAL_STRETCH_RATIO = 1.8

    /** 回复加快门槛：本条间隔 < 基线均值 × 此比（热度上升信号）。 */
    const val QUICK_REPLY_RATIO = 0.4

    /**
     * 滚动重算：取窗口内样本全量统计（无离群截断——方案取均值口径，间隔偏高
     * 由 [EmotionJudge] 的 1.8× 相对门槛吸收）。空窗口返回零基线（不可用）。
     */
    fun calculate(
        samples: List<EmotionMessageSample>,
        nowMs: Long,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): EmotionBaseline {
        val windowMs = windowDays.coerceAtLeast(1).toLong() * DAY_MS
        val inWindow = samples
            .filter { it.timestampMs in (nowMs - windowMs)..nowMs }
            .sortedBy { it.timestampMs }
        if (inWindow.isEmpty()) {
            return EmotionBaseline(
                avgLen = 0.0,
                avgIntervalMs = 0L,
                shortReplyRatio = 0.0,
                activeFollowRatio = 0.0,
                sampleCount = 0,
                updatedAt = nowMs,
            )
        }
        val lens = inWindow.map { it.text.trim().length }
        val gaps = inWindow.zipWithNext { a, b -> b.timestampMs - a.timestampMs }.filter { it >= 0 }
        return EmotionBaseline(
            avgLen = lens.average(),
            avgIntervalMs = if (gaps.isEmpty()) 0L else gaps.average().toLong(),
            shortReplyRatio = inWindow.count { isShortReply(it.text) }.toDouble() / inWindow.size,
            activeFollowRatio = inWindow.count { it.hasActiveQuestion || it.hasFollowUp }
                .toDouble() / inWindow.size,
            sampleCount = inWindow.size,
            updatedAt = nowMs,
        )
    }

    /**
     * 偏离计算：单条消息 vs 基线。基线样本不足（!usable）直接全空——原则 2 的
     * 执法面：话少型人格 / 新联系人在基线内，零信号。
     */
    fun deviation(
        textLen: Int,
        replyIntervalMs: Long?,
        baseline: EmotionJudge.Baseline,
    ): BaselineDeviation {
        if (!baseline.usable) return BaselineDeviation(null, null, false, false, false, false)
        val gap = replyIntervalMs?.takeIf { it >= 0 }
        val gapUsable = gap != null && baseline.avgIntervalMs > 0
        return BaselineDeviation(
            lenRatio = if (textLen > 0 && baseline.avgLen > 0.0) textLen / baseline.avgLen else null,
            gapRatio = if (gapUsable) gap.toDouble() / baseline.avgIntervalMs else null,
            lengthDrop = textLen > 0 && textLen < baseline.avgLen * LENGTH_DROP_RATIO,
            lengthRise = textLen > 0 && textLen > baseline.avgLen * LONG_REPLY_RATIO,
            intervalStretch = gapUsable && gap > baseline.avgIntervalMs * INTERVAL_STRETCH_RATIO,
            quickReply = gapUsable && gap < baseline.avgIntervalMs * QUICK_REPLY_RATIO,
        )
    }

    /** 短回复：trim 后 1..[EmotionJudge.SHORT_REPLY_MAX_LEN] 字且非提问（提问是主动参与）。 */
    private fun isShortReply(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > EmotionJudge.SHORT_REPLY_MAX_LEN) return false
        return !t.contains('？') && !t.contains('?')
    }
}
