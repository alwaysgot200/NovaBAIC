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
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole

/**
 * 老参谋发给 LLM 的「瘦身历史」组装器（纯函数、无 Android 依赖）。
 *
 * 持久化会话里每条 OCR 都是完整的，但模型每回合只需要：当前屏幕全文 +
 * 最近少量屏幕的缩写 + 更早屏幕的一句场景占位 + 滚动摘要。本对象只对
 * 「即将发送的副本」做变换（[ChatMessage.copy] 改 content），落库原文与
 * 转录视图都不受影响。
 *
 * 输出恒为 USER 开头、USER/ASSISTANT 交替，可直接喂给任意兼容供应商。
 */
internal object AdvisorHistory {

    /** 最近保留缩写原文的自动屏幕回合数（未绑定/共享会话档）。 */
    private const val RECENT_SCREEN_TURNS = 2

    /** 绑定联系人会话档：同一会话全部属于同一对话线程，近屏多留两屏。 */
    private const val BOUND_RECENT_SCREEN_TURNS = 4

    /** 旧屏幕缩写保留的正文字符数（未绑定/共享会话档）。 */
    private const val SCREEN_ABBREV_CHARS = 160

    /** 绑定联系人会话档：保住报价/异议/承诺原话。 */
    private const val BOUND_SCREEN_ABBREV_CHARS = 400

    /** 当前屏之外历史部分的总字符预算（未绑定/共享会话档）。 */
    private const val PRIOR_BUDGET_CHARS = 2_400

    /** 绑定联系人会话档：同一线程无串档风险，预算让渡给旧屏。 */
    private const val BOUND_PRIOR_BUDGET_CHARS = 3_200

    /** 参与分组的最大消息数（防超长会话的无谓计算）。 */
    private const val MAX_MESSAGES = 60

    private const val ELLIPSIS = "…（屏幕原文已截断）"

    private class Turn(val user: ChatMessage, val tail: List<ChatMessage>)

    /**
     * @param messages 持久化会话的完整消息（按时间升序），最后一条应为当前屏幕 USER。
     * @param screenLabel 自动屏幕消息的前缀（【屏幕内容】）。
     * @param digest 更早屏幕的滚动摘要（≤150 字），可为 null。
     * @param bound true = 已绑定联系人的专属会话：同一会话全部属于同一对话
     *   线程，近屏保留更多（4 屏×400 字）、历史预算放宽到 3200 字，折叠
     *   占位也不再命令模型「忽略旧屏幕」——旧屏里的报价/承诺仍然有效。
     */
    fun build(
        messages: List<ChatMessage>,
        screenLabel: String,
        digest: String?,
        bound: Boolean = false,
    ): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        val turns = groupTurns(messages.takeLast(MAX_MESSAGES))
        if (turns.isEmpty()) return messages.takeLast(MAX_MESSAGES)

        val recentTurns = if (bound) BOUND_RECENT_SCREEN_TURNS else RECENT_SCREEN_TURNS
        val abbrevChars = if (bound) BOUND_SCREEN_ABBREV_CHARS else SCREEN_ABBREV_CHARS
        val budgetChars = if (bound) BOUND_PRIOR_BUDGET_CHARS else PRIOR_BUDGET_CHARS

        val current = turns.last()
        val prior = turns.dropLast(1)
        if (prior.isEmpty()) return flatten(current, abbreviateScreen = false, screenLabel, abbrevChars)

        // 从最近向最远装入预算：手动追问整回合保护；屏幕回合至多保留
        // recentTurns 个缩写；其余折叠为场景占位。
        data class Slot(val turn: Turn, val abbreviate: Boolean)
        val kept = ArrayDeque<Slot>()
        var screenKept = 0
        var used = 0
        val collapsed = ArrayList<Turn>()
        for (turn in prior.asReversed()) {
            val isScreen = isScreen(turn.user, screenLabel)
            if (!isScreen) {
                kept.addFirst(Slot(turn, abbreviate = false))
                used += turnLength(turn)
                continue
            }
            if (screenKept < recentTurns) {
                val abbrevLen = abbrevChars + screenLabel.length + ELLIPSIS.length
                val tailLen = turn.tail.sumOf { it.content.length }
                if (used + abbrevLen + tailLen <= budgetChars) {
                    kept.addFirst(Slot(turn, abbreviate = true))
                    screenKept++
                    used += abbrevLen + tailLen
                    continue
                }
            }
            collapsed.add(0, turn)
        }

        val out = ArrayList<ChatMessage>()
        if (collapsed.isNotEmpty()) {
            out.add(ChatMessage(role = ChatRole.USER, content = headerLine(collapsed, screenLabel, bound)))
            out.add(ChatMessage(role = ChatRole.ASSISTANT, content = digestLine(digest, bound)))
        }
        kept.forEach { slot ->
            out.addAll(flatten(slot.turn, slot.abbreviate, screenLabel, abbrevChars))
        }
        out.addAll(flatten(current, abbreviateScreen = false, screenLabel, abbrevChars))
        return out
    }

    /** 按 USER 边界分回合；开头游离的非 USER 消息丢弃（正常链路不会出现）。 */
    private fun groupTurns(messages: List<ChatMessage>): List<Turn> {
        val turns = ArrayList<Turn>()
        var user: ChatMessage? = null
        var tail = ArrayList<ChatMessage>()
        for (message in messages) {
            if (message.role == ChatRole.USER) {
                user?.let { turns.add(Turn(it, tail)) }
                user = message
                tail = ArrayList()
            } else if (user != null) {
                tail.add(message)
            }
        }
        user?.let { turns.add(Turn(it, tail)) }
        return turns
    }

    private fun isScreen(message: ChatMessage, screenLabel: String): Boolean =
        message.role == ChatRole.USER && message.content.startsWith(screenLabel)

    private fun turnLength(turn: Turn): Int =
        turn.user.content.length + turn.tail.sumOf { it.content.length }

    private fun flatten(
        turn: Turn,
        abbreviateScreen: Boolean,
        screenLabel: String,
        abbrevChars: Int,
    ): List<ChatMessage> {
        val user = if (abbreviateScreen && isScreen(turn.user, screenLabel)) {
            turn.user.copy(content = abbreviate(turn.user.content, screenLabel, abbrevChars))
        } else {
            turn.user
        }
        return buildList {
            add(user)
            addAll(turn.tail)
        }
    }

    /** 保留屏幕前缀行，正文截断到 [abbrevChars]。 */
    private fun abbreviate(content: String, screenLabel: String, abbrevChars: Int): String {
        val prefixLine = screenLabel + "\n"
        val body = if (content.startsWith(prefixLine)) {
            content.removePrefix(prefixLine)
        } else {
            content
        }
        return if (body.length <= abbrevChars) {
            content
        } else {
            prefixLine + body.take(abbrevChars) + ELLIPSIS
        }
    }

    private fun headerLine(collapsed: List<Turn>, screenLabel: String, bound: Boolean): String {
        val tags = collapsed.asSequence()
            .map { sceneTag(it.user.content, screenLabel) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(5)
            .joinToString("、") { (tag, count) -> if (count > 1) "${tag}×$count" else tag }
        return if (bound) {
            "【更早的同一联系人聊天】本次提问前已识别过 ${collapsed.size} 屏" +
                "（主要场景：$tags），原文已省略。其中谈过的报价、承诺、顾虑仍然有效、" +
                "可在回复中引用；但对方的最新态度一律以当前屏幕为准——若当前屏与旧内容" +
                "矛盾（如由拒绝转为同意），立即顺应当前屏，不要沿用旧话术。"
        } else {
            "【历史屏幕】本次提问前已自动识别过 ${collapsed.size} 个屏幕" +
                "（主要场景：$tags）。原始识别文本已省略，请勿复述旧屏幕，请聚焦当前屏幕作答。"
        }
    }

    private fun digestLine(digest: String?, bound: Boolean): String {
        val clean = digest?.trim()?.take(150)
        return if (clean.isNullOrEmpty()) {
            if (bound) {
                "已知悉与此联系人更早的聊天，下面结合当前屏幕作答；" +
                    "对方最新表态以当前屏幕为准。"
            } else {
                "已知悉此前看过的屏幕，下面只针对当前屏幕作答。"
            }
        } else if (bound) {
            "更早的交往背景（带日期，仅代表当时）：$clean"
        } else {
            "此前连续看屏的背景：$clean"
        }
    }

    /**
     * 屏幕场景的确定性归类（零额外调用）。顺序即优先级：快捷面板的
     * 「静音/手电筒」与锁屏时钟共现时算面板，带日期星期的算锁屏。
     */
    private fun sceneTag(content: String, screenLabel: String): String {
        val body = content.removePrefix(screenLabel).removePrefix("\n")
        return when {
            body.contains("手电筒") || body.contains("静音") ||
                body.contains("飞行模式") || body.contains("快捷设置") -> "通知/快捷面板"
            Regex("""\d{1,2}月\d{1,2}日\s*周[一二三四五六日天]""").containsMatchIn(body) -> "锁屏"
            body.contains("主屏幕") -> "主屏幕"
            body.contains("设置") -> "设置页"
            Regex("""回\s*\d{1,2}:\d{2}""").containsMatchIn(body) -> "聊天/消息"
            body.contains("未连接到互联网") || body.contains("http") ||
                body.contains("www.") || body.contains(".com") -> "网页/文章"
            else -> "其他界面"
        }
    }
}
