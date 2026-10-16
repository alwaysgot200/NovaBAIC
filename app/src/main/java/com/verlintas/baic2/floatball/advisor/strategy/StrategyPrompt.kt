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

import com.verlintas.baic2.floatball.advisor.PATH_PURSUE

/**
 * 策略提示词段渲染（纯函数，§八）。位于 styleWithLength 与 brief.styleSection 之间，
 * 顺序固定：角色处境 → 当前阶段 → 近期目标 →（P3 战术硬约束）→ 红线 → 自我约束
 * → 话题素材 → 本轮态势。
 */
object StrategyPrompt {

    /** 弱互动站定义：pursue 包前两站才投喂话题素材（避免后期站打断推进节奏）。 */
    private const val TOPIC_BANK_WEAK_STATION_LIMIT = 1

    /**
     * @param tactical P3 §七增强档预分析结论；非空时物理插在策略卡内容之后、红线之前。
     */
    fun strategySection(ctx: StrategyContext, tactical: TacticalBrief? = null): String {
        val sections = mutableListOf<String>()

        ctx.card?.roleLine?.takeIf { it.isNotBlank() }?.let {
            sections.add("【你的角色与处境】$it")
        }

        val stageLabel = ctx.stageDef?.label ?: ctx.stage.stageCode
        val stageGoal = ctx.roadmapStageGoal?.takeIf { it.isNotBlank() }
            ?: ctx.stageDef?.missionHint?.takeIf { it.isNotBlank() }
        sections.add(
            if (stageGoal != null) "【当前阶段】$stageLabel：$stageGoal"
            else "【当前阶段】$stageLabel",
        )

        ctx.activeL1?.let { l1 ->
            val line = StringBuilder("【近期目标】").append(l1.title.trim())
            l1.successCriteria?.takeIf { it.isNotBlank() }?.let {
                line.append("（成功判据：").append(it.trim()).append("）")
            }
            sections.add(line.toString())
        }

        // P3 §七：战术硬约束位于策略卡内容之后、红线之前；与红线冲突时以红线为准。
        tactical?.takeIf {
            it.hardActions.isNotEmpty() || it.donts.isNotEmpty() ||
                it.mustAnswer.isNotEmpty() || it.toneGuard.isNotEmpty()
        }?.let { sections.add(TacticalBriefRules.promptBlock(it)) }

        val reds = StageGate.redLines(ctx.pack, ctx.myTabooLines, ctx.theirTabooLines).toMutableList()
        StageGate.actionConstraint(ctx.pack.code, ctx.pack.pathKind, ctx.stage.stageCode)
            ?.let { reds.add(it) }
        reds.add(StageGate.COMMITMENT_RULE)
        sections.add("【红线（本轮绝对禁止）】\n" + reds.joinToString("\n") { "- $it" })

        ctx.card?.selfConstraints?.map { it.text }?.takeIf { it.isNotEmpty() }?.let {
            sections.add("【自我约束】" + it.joinToString("；"))
        }

        if (shouldShowTopicBank(ctx)) {
            ctx.card?.topicBank?.map { it.text }?.takeIf { it.isNotEmpty() }?.let {
                sections.add("【话题素材】可自然借用：" + it.joinToString("；"))
            }
        }

        StageGate.retreatSection(ctx.retreatHits, ctx.pack)?.let { sections.add("【本轮态势】$it") }

        // P3 §12.2：silence_24h 是中性事实信号——只陈述时长，动作由场景/pathKind
        // 决定：pursue 可考虑轻触达但不催促；defend/maintain 默认继续等待。
        if (ctx.neutralSignals.contains(SignalRules.SILENCE_24H) && ctx.hoursSinceIncoming != null) {
            val actionHint = if (ctx.pack.pathKind == PATH_PURSUE) {
                "可考虑一次轻触达，但不要催促、不要逼问"
            } else {
                "默认继续等待，不主动催促"
            }
            sections.add(
                "【时序观察】距对方上次来信已约 ${ctx.hoursSinceIncoming} 小时（中性信号，" +
                    "不是态度判断）：$actionHint。",
            )
        }

        return sections.joinToString("\n")
    }

    private fun shouldShowTopicBank(ctx: StrategyContext): Boolean {
        if (ctx.pack.pathKind != PATH_PURSUE) return false
        val idx = ctx.pack.stages.indexOfFirst { it.code == ctx.stage.stageCode }
        return idx in 0..TOPIC_BANK_WEAK_STATION_LIMIT
    }

    /** 输出协议段：要求先出一个 <plan>JSON</plan>，空行后写正文，正文不复述 JSON。 */
    fun outputProtocol(): String =
        "【输出协议】先输出且只输出一个 <plan>JSON</plan>，JSON 字段：" +
            "mission（本轮一句话策略，必须服务近期目标或当前阶段、遵守红线）、" +
            "actions（1~3 条本轮具体动作）、forbidden（本轮不做的事，必须与红线一致）、" +
            "signalsToWatch（对方值得留意的反应）、successWhen（本轮怎样算成功）。" +
            "JSON 后空一行直接写回复正文，正文不得复述 JSON。"
}
