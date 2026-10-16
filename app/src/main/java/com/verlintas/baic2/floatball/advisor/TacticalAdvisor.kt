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

package com.verlintas.baic2.floatball.advisor

import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.floatball.advisor.strategy.StageGate
import com.verlintas.baic2.floatball.advisor.strategy.StrategyContext
import com.verlintas.baic2.floatball.advisor.strategy.TacticalBrief
import com.verlintas.baic2.floatball.advisor.strategy.TacticalBriefRules
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 增强档战术预调用（P3 §七）：主调用之前的一次 AuxiliaryTasks 快速分析
 * （≤300 tok / 0.2 / [PRECALL_TIMEOUT_MS] 硬预算），输出严格 JSON 经
 * [TacticalBriefRules] 确定性校验后作为本轮战术硬约束。
 *
 * 任何失败（无配置/超时/JSON 不合规）返回 null，调用方静默回退一段式——
 * 读 2 最终行为退化为智能档，绝不阻塞主回合。
 */
@Singleton
class TacticalAdvisor @Inject constructor(
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {
    /**
     * @param latestIncoming 对方最新来信（OCR/a11y 文本，已在调用方限长）。
     * @return 校验通过的 [TacticalBrief]；失败/超时/不合规均为 null。
     */
    suspend fun preAnalyze(ctx: StrategyContext, latestIncoming: String): TacticalBrief? {
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull() ?: return null
        val raw = withTimeoutOrNull(PRECALL_TIMEOUT_MS) {
            runCatching {
                auxiliaryTasks.complete(
                    config = config,
                    systemPrompt = SYSTEM_PROMPT,
                    userPrompt = buildUserPrompt(ctx, latestIncoming),
                    maxTokens = MAX_TOKENS,
                    temperature = TEMPERATURE,
                )
            }.getOrNull()
        } ?: return null
        return TacticalBriefRules.parse(raw)
    }

    private fun buildUserPrompt(ctx: StrategyContext, latestIncoming: String): String {
        val stageLabel = ctx.stageDef?.label ?: ctx.stage.stageCode
        val reds = StageGate.redLines(ctx.pack, ctx.myTabooLines, ctx.theirTabooLines)
        return buildString {
            appendLine("场景：${ctx.pack.label}")
            appendLine("当前关系阶段：$stageLabel")
            ctx.card?.roleLine?.takeIf { it.isNotBlank() }?.let { appendLine("角色自我提醒：$it") }
            ctx.activeL1?.let { appendLine("近期目标：${it.title.trim()}") }
            if (reds.isNotEmpty()) appendLine("红线：${reds.take(MAX_RED_LINES).joinToString("；")}")
            append("对方最新来信：")
            append(latestIncoming.trim().take(MAX_INCOMING_CHARS).ifEmpty { "（无文本，按屏幕场景判断）" })
        }
    }

    private companion object {
        const val MAX_TOKENS = 300
        const val TEMPERATURE = 0.2
        const val PRECALL_TIMEOUT_MS = 8_000L
        const val MAX_RED_LINES = 6
        const val MAX_INCOMING_CHARS = 400

        val SYSTEM_PROMPT = """
            你是聊天临场参谋。在主参谋给出回复建议前，先做一次克制的战术预分析。
            只输出一个 JSON 对象，严格格式：
            {"hardActions":[],"donts":[],"mustAnswer":[],"toneGuard":""}
            - hardActions：本轮必须落实的具体动作，至多 3 条，每条不超过 30 字；
            - donts：本轮绝对不做的事（必须与给定红线一致，不得自创关系判断），至多 3 条；
            - mustAnswer：对方来信中必须正面回应的点（问题/请求/情绪），至多 3 条；
            - toneGuard：一句话语气守卫，不超过 40 字，没有把握留空字符串；
            - 只依据给定事实，不编造对方意图；没有内容的组留空数组；
            - 不要解释、不要输出 JSON 以外的任何内容。
        """.trimIndent()
    }
}
