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

package com.verlintas.baic2.floatball.auto

import com.verlintas.baic2.floatball.advisor.strategy.ReplySuggestions
import com.verlintas.baic2.floatball.director.GateRules

/** 一次「可发送纯回复」抽取产物：[text] 为发送正文，[source] 为来源码（入台账）。 */
data class SendableDraft(val text: String, val source: String)

/**
 * 「实际回复」确定性抽取器（托管方案 §四.A.1，纯函数零 LLM 调用，JVM 可单测）。
 *
 * 落库的 ASSISTANT 消息是参谋全文（分析 + 编号建议 + 可选 reply 块）；自动发送
 * 只能取其中的纯回复，三级兜底链：
 * 1. **主路径 `<reply>` 标记协议**（与上游 `<plan>` 块剥离同构的静态 split，
 *    [PlanStreamFilter.split] 语义：缺失/未闭合确定性检测后降级，绝不吞正文）；
 * 2. 标记缺失/未闭合 → [ReplySuggestions.parse] 编号建议首条；
 * 3. 仍无 → [ReplySuggestions.preamble] 引言首句，句界截断到 [answerTargetChars]
 *    （复用 [GateRules.truncateToLength]，不腰斩词语）。
 *
 * [source]（reply_tag/numbered/truncated）随 AutoSendRequest 入台账 reason，
 * 回放可见草稿来源与兜底触发率。分屏流式侧的「reply 块折叠展示」属转录 UI
 * 范畴，派发读的是落库全文的静态抽取，本期不接流。
 */
object SendableDraftExtractor {

    const val SOURCE_REPLY_TAG = "reply_tag"
    const val SOURCE_NUMBERED = "numbered"
    const val SOURCE_TRUNCATED = "truncated"

    const val REPLY_OPEN = "<reply>"
    const val REPLY_CLOSE = "</reply>"

    /** 兜底二截断下限：长度档调得过小仍保证一句正常回复的容量。 */
    const val MIN_FALLBACK_CHARS = 60

    /** 句界字符（与 GateRules 同口径：自然句收口，不腰斩词语）。 */
    private val SENTENCE_BREAKS = charArrayOf('。', '！', '？', '!', '?', '…', '\n', '；', ';')

    /**
     * 从参谋全文抽取可发送纯回复；全文为空或兜底后仍无可用文本返回 null
     * （调用方按无稿处理，绝不发空消息）。
     */
    fun extract(full: String?, answerTargetChars: Int): SendableDraft? {
        val raw = full?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        // 主路径：第一个完整的 <reply>…</reply> 块；空块按缺失降级。
        val open = raw.indexOf(REPLY_OPEN)
        if (open >= 0) {
            val bodyStart = open + REPLY_OPEN.length
            val close = raw.indexOf(REPLY_CLOSE, bodyStart)
            if (close >= 0) {
                val inner = raw.substring(bodyStart, close).trim()
                if (inner.isNotEmpty()) return SendableDraft(inner, SOURCE_REPLY_TAG)
            }
        }
        val body = stripTags(raw)
        // 兜底一：编号建议首条（parse 要求 ≥2 条连续编号才命中）。
        ReplySuggestions.parse(body)?.firstOrNull()?.let { item ->
            val text = item.text.trim()
            if (text.isNotEmpty()) return SendableDraft(text, SOURCE_NUMBERED)
        }
        // 兜底二：引言（无编号头时即全文）首句，句界截断兜容量。
        val base = ReplySuggestions.preamble(body).ifBlank { body }
        val candidate = firstSentence(base).ifBlank { base }
        val maxChars = answerTargetChars.coerceAtLeast(MIN_FALLBACK_CHARS)
        val text = GateRules.truncateToLength(candidate, maxChars)
        return text.takeIf { it.isNotEmpty() }?.let { SendableDraft(it, SOURCE_TRUNCATED) }
    }

    /** 剥离残余标记后作兜底正文（孤儿开/闭标记不得混进编号解析与首句判定）。 */
    private fun stripTags(text: String): String =
        text.replace(REPLY_OPEN, "").replace(REPLY_CLOSE, "").trim()

    /** 首句 = 截至第一个句界（含句界符）；无句界返回原文。 */
    private fun firstSentence(text: String): String {
        val cutAt = text.indexOfAny(SENTENCE_BREAKS)
        return if (cutAt < 0) text else text.substring(0, cutAt + 1)
    }
}
