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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor.strategy

import kotlinx.serialization.Serializable

/**
 * 增强档战术预分析结果（P3 §七）：主调用之前的一次快速 Tactician 预调用产物，
 * 作为「本轮战术硬约束」插在策略卡内容之后、红线块之前。
 *
 * 模型输出永远不可信，落库/入 prompt 前必须经 [TacticalBriefRules] 确定性裁剪。
 */
@Serializable
data class TacticalBrief(
    /** 本轮必须落实的硬动作。 */
    val hardActions: List<String> = emptyList(),
    /** 本轮绝对不做的事。 */
    val donts: List<String> = emptyList(),
    /** 对方来信中必须正面回应的点。 */
    val mustAnswer: List<String> = emptyList(),
    /** 一句话语气守卫（如「先共情，再谈条件」）。 */
    val toneGuard: String = "",
)

/**
 * [TacticalBrief] 的确定性校验（纯函数，与 PlanStreamFilter 同包，零 Android 依赖）。
 *
 * 规则：宽容去围栏截取 `{…}`；三个数组每组 ≤[MAX_ITEMS] 条、每条 ≤[MAX_ITEM_CHARS] 字、
 * 组内去重；toneGuard ≤[MAX_GUARD_CHARS] 字；任何字段含「越权指令」特征词则整段判废；
 * 四组内容全空同样判废（调用方静默回退一段式）。
 */
object TacticalBriefRules {

    const val MAX_ITEMS = 3
    const val MAX_ITEM_CHARS = 30
    const val MAX_GUARD_CHARS = 40

    /** 越权指令特征（针对助手自身/提示词的元指令，正常聊天策略不会出现）。 */
    private val ILLEGAL_PATTERNS = listOf(
        Regex("ignore\\s+(all\\s+)?previous", RegexOption.IGNORE_CASE),
        Regex("disregard\\s+(your\\s+)?(instructions|prompt)", RegexOption.IGNORE_CASE),
        Regex("new\\s+instructions?", RegexOption.IGNORE_CASE),
        Regex("system\\s+prompt", RegexOption.IGNORE_CASE),
        Regex("忽略(以上|前面|之前|上述|所有).{0,6}(指令|要求|提示|规则)"),
        Regex("(无视|忘记)(之前|前面|以上).{0,4}(指令|要求|规则)"),
    )

    fun parse(raw: String?): TacticalBrief? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val noFence = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = noFence.indexOf('{')
        val end = noFence.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val parsed = runCatching {
            strategyJson.decodeFromString(
                TacticalBrief.serializer(),
                noFence.substring(start, end + 1),
            )
        }.getOrNull() ?: return null

        val actions = sanitizeItems(parsed.hardActions)
        val donts = sanitizeItems(parsed.donts)
        val mustAnswer = sanitizeItems(parsed.mustAnswer)
        val guard = parsed.toneGuard.trim().take(MAX_GUARD_CHARS)

        val allText = actions + donts + mustAnswer + listOf(guard)
        if (allText.any { it.isIllegal() }) return null
        if (actions.isEmpty() && donts.isEmpty() && mustAnswer.isEmpty() && guard.isEmpty()) return null
        return TacticalBrief(
            hardActions = actions,
            donts = donts,
            mustAnswer = mustAnswer,
            toneGuard = guard,
        )
    }

    /** 渲染为「本轮战术硬约束」提示词段（纯函数，供测试与主回合同一口径）。 */
    fun promptBlock(brief: TacticalBrief): String = buildString {
        appendLine("【本轮战术硬约束】（预分析结论，必须遵守；与红线冲突时以红线为准）")
        if (brief.hardActions.isNotEmpty()) {
            appendLine("本轮必须做到：")
            brief.hardActions.forEach { appendLine("- $it") }
        }
        if (brief.donts.isNotEmpty()) {
            appendLine("本轮不要：")
            brief.donts.forEach { appendLine("- $it") }
        }
        if (brief.mustAnswer.isNotEmpty()) {
            appendLine("必须正面回应：${brief.mustAnswer.joinToString("；")}")
        }
        if (brief.toneGuard.isNotEmpty()) append("语气：${brief.toneGuard}")
    }.trimEnd()

    private fun sanitizeItems(items: List<String>): List<String> =
        items.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.take(MAX_ITEM_CHARS) }
            .distinct()
            .take(MAX_ITEMS)
            .toList()

    private fun String.isIllegal(): Boolean = ILLEGAL_PATTERNS.any { it.containsMatchIn(this) }
}
