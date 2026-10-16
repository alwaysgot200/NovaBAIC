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

package com.verlintas.baic2.floatball.advisor.strategy

import com.verlintas.baic2.floatball.advisor.ScenarioPack
import com.verlintas.baic2.floatball.advisor.ScenarioStage

/**
 * 确定性信号规则（零 LLM）：退缩/激化扫描与推进信号粗筛。
 *
 * 推进粗筛只回答「值不值得花一次 Judge 轻 LLM」，宁松勿紧——误报代价是一次 200-token 调用，
 * 漏报代价是阶段停滞（用户仍可手动改站）。
 */
object SignalRules {

    /** P3 §12.2：距上次来信 ≥24h 的中性信号码（语义中性，动作由场景/pathKind 决定）。 */
    const val SILENCE_24H = "silence_24h"

    /** P3 §12.2：24h 阈值（毫秒）。 */
    const val SILENCE_THRESHOLD_MS = 24L * 60L * 60L * 1000L

    /**
     * P3 §12.2：中性时序信号判定。
     * @param lastIncomingAt 对方最后来信时间（epoch ms）；null/非正=旧会话从未记录，
     *   返回 null（无时间不臆测）；时钟倒流（[now] 早于来信）同样视为无信号。
     * @return 距上次来信达到 [thresholdMs] 返回 [SILENCE_24H]，否则 null。
     */
    fun silenceSignal(
        lastIncomingAt: Long?,
        now: Long,
        thresholdMs: Long = SILENCE_THRESHOLD_MS,
    ): String? {
        if (lastIncomingAt == null || lastIncomingAt <= 0L) return null
        val elapsed = now - lastIncomingAt
        if (elapsed < 0L) return null
        return if (elapsed >= thresholdMs) SILENCE_24H else null
    }

    /** P3 §12.2：距上次来信的完整小时数；无时间/时钟倒流返回 null。 */
    fun hoursSinceIncoming(lastIncomingAt: Long?, now: Long): Int? {
        if (lastIncomingAt == null || lastIncomingAt <= 0L) return null
        val elapsed = now - lastIncomingAt
        if (elapsed < 0L) return null
        return (elapsed / (60L * 60L * 1000L)).toInt()
    }

    /** ASCII 数字与全角数字：含数字时长的信号（如「已读不回超过24小时」）P2 一律跳过词表。 */
    private val digitRegex = Regex("[0-9０-９]")

    /** 长度 ≥3 的连续中文片段：把「主动约面试时间/问到岗时间」拆成可独立命中的核心词。 */
    private val cjkRunRegex = Regex("[\\u4e00-\\u9fa5]{3,}")

    /**
     * 退缩（defend 包即激化）信号命中：逐条子串匹配，返回命中的信号原词。
     * 含数字时长的信号本轮跳过（无消息时间戳能力，P3 时序信号补，§7.2）。
     */
    fun retreatHits(pack: ScenarioPack, incomingText: String): List<String> =
        retreatHits(pack.retreatSignals, incomingText)

    /** P3：给定退缩信号词表（包级 + 私有支线站）的命中扫描，含数字时长的信号本轮跳过。 */
    fun retreatHits(signals: List<String>, incomingText: String): List<String> {
        if (incomingText.isEmpty()) return emptyList()
        return signals.filter { signal ->
            signal.isNotBlank() && !signal.contains(digitRegex) && incomingText.contains(signal)
        }
    }

    /**
     * 推进信号粗筛：[stage] 的 advanceSignals 中，任一核心词出现在来信或我方回复里即候选。
     * 核心词 = 信号中长度 ≥3 的连续中文片段（数字与标点天然切段）。
     */
    fun advanceCandidates(
        stage: ScenarioStage,
        incomingText: String,
        replyText: String,
    ): List<String> = candidates(stage.advanceSignals, incomingText, replyText)

    /** P3：同上，但信号列表直接传入（骨架站/私有支线站统一入口）。 */
    fun candidates(
        signals: List<String>,
        incomingText: String,
        replyText: String,
    ): List<String> = signals.filter { signal ->
        signal.isNotBlank() &&
            coreKeys(signal).any { key -> incomingText.contains(key) || replyText.contains(key) }
    }

    /** 抽取信号的可匹配核心词：整条为短中文短语时直接用，否则拆长片段。 */
    internal fun coreKeys(signal: String): List<String> {
        val trimmed = signal.trim()
        if (trimmed.length in 3..6 && cjkRunRegex.matches(trimmed)) return listOf(trimmed)
        return cjkRunRegex.findAll(trimmed).map { it.value }.toList()
    }
}
