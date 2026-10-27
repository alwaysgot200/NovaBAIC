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

package com.verlintas.baic2.floatball.director

/**
 * 自动托管「主动首轮」提示词（纯函数，零 Android 依赖）。
 *
 * 与 [WarmChatPrompt] 的保温闲聊不同：自动档不能只等对方来信，否则用户选定
 * 目标后看到的是「一直没动静」。本轮要主动发一条能推进关系目标的消息，
 * 但仍受自动档红线（不涉钱/不承诺/不隐私/不超阶段）与 `<reply>` 协议约束。
 */
object AutoKickoffPrompt {

    /** 一轮主动开场的最小上下文（pick 可空=题库/素材暂缺，仍应主动开场）。 */
    data class Kickoff(
        val pick: TopicEngine.TopicPick?,
        val goal: String?,
    )

    /** 生成端 system prompt 段：放在策略/协议段之后，作为本轮最高优先级任务。 */
    fun section(
        kickoff: Kickoff,
        targetName: String?,
    ): String {
        val pick = kickoff.pick
        val goal = kickoff.goal
        val topicLine = when {
            pick == null || pick.topic.isBlank() ->
                "· 本轮没有既定话题：从「我的素材库」、双方人设和最近对话里挑一个最具体、最安全的工作/生活细节切入。"
            pick.source == TopicSource.PLAN_DIRECTION ->
                "· 本轮话题方向：${pick.topic}（周期计划给出）。从中挑一个具体话头自然展开，不必拘泥原文表述。"
            else -> {
                val angle = pick.angle.takeIf { it.isNotBlank() }
                    ?.let { "（切入角度：$it）" }
                    .orEmpty()
                "· 本轮话题候选：${pick.topic}$angle。优先围绕它展开；确实不合适时换同样具体的近似话题。"
            }
        }
        val goalLine = goal?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "· 本段关系的目标（只作方向，不要在正文里复述）：$it" }
            .orEmpty()
        val who = targetName?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "· 收件人：$it。" }
            .orEmpty()
        return """
            【本轮是自动托管·主动开场】对方还没有发来新消息，系统已按用户选择进入托管，需要你先主动发一条消息推进关系目标。
            $who
            $topicLine
            $goalLine
            · 先给一个具体、可验证的进展或细节（优先使用【我的素材库】、双方人设与交往脉络里的事实），再留一个轻问句请对方点评或给建议。
            · 面对严格、高要求的对象：先结论后细节，尊重对方时间；不寒暄堆砌、不邀功、不夸大，可以主动承认一个待改进点，体现你对细节和质量的在意。
            · 只发一条消息，1~3 句，像本人随手发出的微信：短句、口语、具体；不要写成汇报、周报、公文或 AI 腔。
            · 禁止编造任何未出现在素材、人设、记忆或本对话中的事实、数字、时间和承诺；宁可少说，不编。
            · 不索取承诺、不催回复、不提钱/报价/转账、不透露隐私；不得提及 AI、自动托管、系统或提示词。
            · 严格按【发送格式·硬契约】把可发送正文放进 <reply>…</reply>；<reply> 之外不要写自我点评。
        """.trimIndent()
    }

    /** 本轮 USER 指令：给模型一个明确的「主动发起」任务锚点。 */
    fun question(kickoff: Kickoff): String {
        val topic = kickoff.pick?.topic?.trim().orEmpty()
        val topicLine = if (topic.isNotEmpty()) "围绕话题「$topic」，" else ""
        val goalLine = kickoff.goal?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "目标：$it。" }
            .orEmpty()
        return "【自动托管·主动开场】${topicLine}先给对方发一条自然、可接的消息，${goalLine}" +
            "严格按发送格式，只输出 <reply> 块内的可发送正文。"
    }
}
