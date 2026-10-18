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

package com.verlintas.baic2.floatball.director

/**
 * 保温闲聊回合的提示词段（M1 方案 §7.2/§10，D3 时事禁令）。纯函数、零 Android
 * 依赖，拼进 system prompt 的策略段之后（KPI 覆盖在前，越靠后权重越高）。
 *
 * 保温回合的 KPI 是「自然度 + 可接性」，绝不携带任务推进压力（方案 §13.2
 * 矛盾#3：M1 只经分屏手动入口触发）；时事禁令在此处而非 TopicEngine（D3：
 * 选题层不涉及时事，编造禁令属生成约束）。
 */
object WarmChatPrompt {

    fun section(pick: TopicEngine.TopicPick?): String {
        val topicLine = if (pick == null || pick.topic.isBlank()) {
            "· 本轮没有既定话题（题库暂缺）：从对话语境里挑一个最轻松的中性话题自由开场。"
        } else {
            val angle = pick.angle.takeIf { it.isNotBlank() }?.let { "（切入角度：$it）" }.orEmpty()
            "· 话题候选：${pick.topic}$angle。优先围绕它展开；确实不合适时可以换成" +
                "同样轻松的近似话题，不必生硬硬凑。"
        }
        return """
            【本轮是保温闲聊】用户点了「来点闲聊」，要你给一条发出去就能自然聊起来的开场草稿：
            $topicLine
            · 语气像熟人随手发的一条消息：短句、口语，可以带一个具体细节或一个轻提问，不客套、不油腻。
            · 本轮 KPI 是自然度与可接性：让对方容易接话、不尴尬；绝不推进业务、不索取承诺、不查岗、不催回复，不提任何阶段、任务或目标。
            · 禁止编造时事新闻、热点事件，或任何未发生在本对话与对方画像中的事实；宁可聊日常，不聊时事政治。
            · 只输出草稿正文本身：不加解释、不加前后缀、不用引号把整条包起来。
        """.trimIndent()
    }
}
