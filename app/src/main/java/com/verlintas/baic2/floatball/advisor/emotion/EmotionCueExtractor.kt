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

import com.verlintas.baic2.floatball.director.HandoffCategory
import com.verlintas.baic2.floatball.director.HandoffRules
import java.time.Instant
import java.time.ZoneId

/**
 * 对方来信的行为线索提取（情感分析师方案 §4.3.1 子库 8 的采集面）：从近期
 * 来信序列（旧→新，末条=当前来信）一次性产出判定引擎要的三件输入——
 * 当前条 [EmotionJudge.EmotionCue]、连续累积窗口 [EmotionJudge.WindowItem]、
 * 基线样本 [EmotionMessageSample]。
 *
 * 采集纪律（HeatRules 同口径）：**不可得就传 false/null，绝不臆测**。
 * 启发式口径：
 * - 回复间隔 = 与上一条来信的时间差（时间戳缺失/时钟回拨 → null）；
 * - 主动追问 = 带问号；追加洗白 = 紧邻前条是短确认而本条是实质消息（原则 4）；
 * - 深夜发起 = 来信时间落在 [NIGHT_HOURS]（默认系统时区，可注入测试时区）；
 * - 主动索取资料 = 命中交接官实体索取强模式（复用 HandoffRules，不重写词表）；
 * - 新话题/转发分享暂不可判定 → false（慢车道 E-g 补强）。
 *
 * 同屏重复抓拍会产生连续同文来信行（PromptDedup 同类问题）：连续同文折叠为
 * 最新一条，防止短确认连续累积被重复抓帧虚增（原则 3 的假阳性）。
 */
object EmotionCueExtractor {

    /** 一条来信样本：文本 + 时间戳（0 = 不可得）。 */
    data class IncomingSample(
        val text: String,
        val timestampMs: Long = 0L,
    )

    /** 提取产物：当前条线索 + 连续窗口（不含当前条）+ 基线样本（含当前条）。 */
    data class Extraction(
        val cue: EmotionJudge.EmotionCue,
        val window: List<EmotionJudge.WindowItem>,
        val samples: List<EmotionMessageSample>,
    )

    /** 深夜时段（凌晨 1–5 点，含端点）。 */
    private val NIGHT_HOURS = 1..5

    /** 从旧到新的来信序列（末条=当前来信）提取；空序列给空产物。 */
    fun extract(raw: List<IncomingSample>, zone: ZoneId = ZoneId.systemDefault()): Extraction {
        val items = collapse(raw)
        if (items.isEmpty()) {
            return Extraction(EmotionJudge.EmotionCue(), emptyList(), emptyList())
        }
        val current = items.last()
        val window = items.dropLast(1)
        val currentQuestion = hasQuestion(current.text)
        val currentShort = EmotionJudge.isShortConfirm(current.text)
        val cue = EmotionJudge.EmotionCue(
            replyIntervalMs = intervalMs(current, window.lastOrNull()),
            hasActiveQuestion = currentQuestion,
            // 原则 4 洗白触发器：紧邻前条是短确认而本条是实质消息（追问/补充/新话题）。
            hasFollowUp = EmotionJudge.isShortConfirm(window.lastOrNull()?.text.orEmpty()) && !currentShort,
            newTopic = false,
            nightInitiative = isNight(current.timestampMs, zone),
            sharedContent = false,
            requestedMaterial = requestedMaterial(current.text),
        )
        val windowItems = window.map {
            EmotionJudge.WindowItem(text = it.text, hasActiveQuestion = hasQuestion(it.text))
        }
        val samples = items.mapIndexed { index, item ->
            EmotionMessageSample(
                text = item.text,
                timestampMs = item.timestampMs,
                hasActiveQuestion = hasQuestion(item.text),
                hasFollowUp = index > 0 &&
                    EmotionJudge.isShortConfirm(items[index - 1].text) &&
                    !EmotionJudge.isShortConfirm(item.text),
            )
        }
        return Extraction(cue = cue, window = windowItems, samples = samples)
    }

    /** trim 后同文的连续重复折叠为最新一条（保最新时间戳）。 */
    private fun collapse(raw: List<IncomingSample>): List<IncomingSample> {
        val out = ArrayList<IncomingSample>(raw.size)
        for (sample in raw) {
            val text = sample.text.trim()
            if (text.isEmpty()) continue
            if (out.isNotEmpty() && out.last().text == text) {
                out[out.lastIndex] = IncomingSample(text, sample.timestampMs)
            } else {
                out.add(IncomingSample(text, sample.timestampMs))
            }
        }
        return out
    }

    private fun intervalMs(current: IncomingSample, previous: IncomingSample?): Long? {
        val gap = (current.timestampMs - (previous?.timestampMs ?: return null))
        return gap.takeIf { current.timestampMs > 0 && previous.timestampMs > 0 && it >= 0 }
    }

    private fun isNight(timestampMs: Long, zone: ZoneId): Boolean {
        if (timestampMs <= 0L) return false
        val hour = runCatching { Instant.ofEpochMilli(timestampMs).atZone(zone).hour }.getOrDefault(-1)
        return hour in NIGHT_HOURS
    }

    private fun hasQuestion(text: String): Boolean = text.contains('？') || text.contains('?')

    /** 主动索取资料（兴趣信号）：命中交接官实体索取强模式（复用，不重写词表）。 */
    private fun requestedMaterial(text: String): Boolean =
        runCatching { HandoffRules.strongHit(text)?.first == HandoffCategory.ENTITY }.getOrDefault(false)
}
