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

package com.verlintas.baic2.floatball.auto

import com.verlintas.baic2.floatball.advisor.strategy.ReplySuggestions
import com.verlintas.baic2.floatball.director.GateRules

/** 一次「可发送纯回复」抽取产物：[text] 为发送正文，[source] 为来源码（入台账）。 */
data class SendableDraft(val text: String, val source: String)

/**
 * 「实际回复」确定性抽取器（托管方案 §四.A.1 / v8 §二十四，纯函数零 LLM 调用，
 * JVM 可单测）。
 *
 * v8 口径收紧：**只接受明确的可发送形态**，不再把参谋分析的首句当回复。
 * 抽取顺序：
 * 1. `<reply>…</reply>` 标记块（大小写/全角/空白/代码围栏/XML 转义均兼容；
 *    多块取最后一个通过形态判定的块；缺闭标记时取开标记之后的剩余文本）；
 * 2. `ReplySuggestions.parse()` 编号建议首条（≥2 条连续编号才命中）；
 * 3. 其它一律返回 null——交给 [ReplyResolver] 的「代言者修复调用」或降级，
 *    **绝不把「对方语气客气」这类分析句发出去**。
 *
 * [SendableDraftExtractor.extractPlain] 只用于修复调用的输出兜底：要求更严格的
 * 单段短回复形态，主路径不走它。
 */
object SendableDraftExtractor {

    const val SOURCE_REPLY_TAG = "reply_tag"
    const val SOURCE_REPLY_UNCLOSED = "reply_unclosed"
    const val SOURCE_NUMBERED = "numbered"
    const val SOURCE_PLAIN = "plain"

    /** 主回合抽不出合法回复、由 [ReplyComposer] 代言者修复调用产出的纯回复。 */
    const val SOURCE_COMPOSED = "composed"

    const val REPLY_OPEN = "<reply>"
    const val REPLY_CLOSE = "</reply>"

    /** 兜底长度下限：长度档调得过小仍保证一句正常回复的容量。 */
    const val MIN_FALLBACK_CHARS = 60

    /** 候选回复的绝对长度上限：超过它更像分析稿，直接判不可发送。 */
    private const val MAX_REPLY_CHARS = 800

    /** 修复调用输出的纯文本上限（更严格：必须是短回复）。 */
    private const val MAX_PLAIN_CHARS = 400

    /** 一条自然回复的句数上限（超出更像分析/列表）。 */
    private const val MAX_REPLY_SENTENCES = 4

    /** 标记兼容：大小写、标签内空白、全角尖括号/斜杠、XML 转义。 */
    private val TAG_PATTERN = Regex("""<\s*/?\s*reply\s*>""", RegexOption.IGNORE_CASE)
    private val ESCAPED_TAG = Regex("""&lt;\s*/?\s*reply\s*&gt;""", RegexOption.IGNORE_CASE)
    private val CODE_FENCE_LINE = Regex("""^\s*```[A-Za-z0-9_+-]*\s*$""")
    private val LABEL_PREFIX = Regex(
        """^\s*(?:回复|答复|发送内容|发送正文|reply)\s*[:：]\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val PLACEHOLDER = Regex(
        """^(?:回复正文|回复内容|回复文本|回复|内容|待填写|xxx+|…+|\.{3,})$""",
        RegexOption.IGNORE_CASE,
    )
    private val NUMBERED_LINE = Regex("""(?m)^\s*(?:\d+|[一二三四五六七八九十]+)\s*[.、)）．]\s*""")

    /**
     * 参谋分析稿的确定性特征：不追求穷尽，只拦「几乎不可能出现在一条
     * 可直接发送的短回复里」的元话语。误判的代价是多一次修复调用，安全。
     */
    private val ANALYSIS_MARKERS = listOf(
        Regex("""(建议|推荐|可以|不妨|最好|应该)(你|您)?(这样|这么)?(回复|回答|回她|回他|回对方)"""),
        Regex("""(回复|应答)(要点|建议|思路|方向|策略|模板|示例|版本)"""),
        Regex("""(这样|这么)(回复|说|回答|发)[^。！？!?\n]{0,8}(会|能|可以|显得|更|比较|让)"""),
        Regex("""(对方|他|她)的?(意思|意图|潜台词|语气|态度|情绪|诉求)[^。！？!?\n]{0,8}(可能|大概|似乎|应该|看来|是|偏)"""),
        Regex("""(分析|判断|权衡|风险|利弊|策略)[：:]"""),
        Regex("""首先[\s\S]{0,60}其次"""),
        Regex("""(?m)^\s*#{1,6}\s+"""),
    )

    private val SENTENCE_BREAKS = charArrayOf('。', '！', '？', '!', '?', '…', '\n', '；', ';')

    private val QUOTE_PAIRS = listOf(
        '"' to '"',
        '\'' to '\'',
        '“' to '”',
        '‘' to '’',
        '「' to '」',
        '『' to '』',
    )

    /**
     * 从参谋全文抽取可发送纯回复；没有明确可发送形态返回 null（调用方走修复
     * 调用或降级，绝不发空消息、绝不发分析稿）。
     */
    fun extract(full: String?, answerTargetChars: Int): SendableDraft? {
        val raw = normalize(full) ?: return null
        val cap = maxOf(MAX_REPLY_CHARS, answerTargetChars.coerceAtLeast(MIN_FALLBACK_CHARS) * 2)
        return taggedReply(raw, cap) ?: numberedReply(raw, cap)
    }

    /**
     * 修复调用（[ReplyComposer]）输出的纯文本兜底：模型忘记写标记、但整条输出
     * 本身就是一段短回复时接受。主分析稿不经过本入口。
     */
    fun extractPlain(text: String?, answerTargetChars: Int): SendableDraft? {
        val clean = cleanBlock(normalize(text)) ?: return null
        if (!looksLikeReply(clean, MAX_PLAIN_CHARS)) return null
        if (NUMBERED_LINE.containsMatchIn(clean)) return null
        if (clean.lines().count { it.isNotBlank() } > 3) return null
        val maxChars = answerTargetChars.coerceAtLeast(MIN_FALLBACK_CHARS)
        val bounded = GateRules.truncateToLength(clean, maxChars)
        return bounded.takeIf { it.isNotEmpty() }?.let { SendableDraft(it, SOURCE_PLAIN) }
    }

    // ------------------------------------------------------------ 标记块

    /** 标记块：优先最后一个完整块；完整块都不合格时再看未闭合块的剩余文本。 */
    private fun taggedReply(raw: String, cap: Int): SendableDraft? {
        val blocks = mutableListOf<Pair<Int, Int>>()
        var openBodyStart = -1
        for (match in TAG_PATTERN.findAll(raw)) {
            val closing = match.value.contains('/')
            if (closing) {
                if (openBodyStart >= 0) {
                    blocks += openBodyStart to match.range.first
                    openBodyStart = -1
                }
            } else {
                openBodyStart = match.range.last + 1
            }
        }
        for ((start, end) in blocks.asReversed()) {
            if (end <= start) continue
            val inner = cleanBlock(raw.substring(start, end)) ?: continue
            if (looksLikeReply(inner, cap)) {
                return SendableDraft(inner, SOURCE_REPLY_TAG)
            }
        }
        if (openBodyStart in 0 until raw.length) {
            val tail = cleanBlock(raw.substring(openBodyStart)) ?: return null
            if (looksLikeReply(tail, cap)) {
                return SendableDraft(tail, SOURCE_REPLY_UNCLOSED)
            }
        }
        return null
    }

    // ------------------------------------------------------------ 编号兜底

    /** 编号建议首条：仍需通过形态判定（防止把「建议先安抚」这类分析当回复）。 */
    private fun numberedReply(raw: String, cap: Int): SendableDraft? {
        val body = TAG_PATTERN.replace(raw, "")
        for (item in ReplySuggestions.parse(body).orEmpty()) {
            val text = cleanBlock(item.text) ?: continue
            if (looksLikeReply(text, cap)) return SendableDraft(text, SOURCE_NUMBERED)
        }
        return null
    }

    // ------------------------------------------------------------ 形态判定

    /** 像不像一条可直接发送的回复（保守判定：元话语/长文/列表一律拒绝）。 */
    private fun looksLikeReply(text: String, cap: Int): Boolean {
        if (text.isEmpty() || text.length > cap) return false
        if (text.contains("<reply", ignoreCase = true) ||
            text.contains("</reply", ignoreCase = true) ||
            text.contains("<plan", ignoreCase = true)
        ) {
            return false
        }
        if (ANALYSIS_MARKERS.any { it.containsMatchIn(text) }) return false
        if (sentenceCount(text) > MAX_REPLY_SENTENCES) return false
        return true
    }

    private fun sentenceCount(text: String): Int {
        val breaks = text.count { it in SENTENCE_BREAKS }
        val tail = if (text.last() in SENTENCE_BREAKS) 0 else 1
        return breaks + tail
    }

    // ------------------------------------------------------------ 清洗

    /** 全角标记/零宽字符归一化；没有原始标记时才把 XML 转义还原成标记。 */
    private fun normalize(full: String?): String? {
        val text = full
            ?.replace('\uFF1C', '<') // ＜
            ?.replace('\uFF1E', '>') // ＞
            ?.replace('\uFF0F', '/') // ／
            ?.replace('\u200B', ' ')
            ?.replace('\u200C', ' ')
            ?.replace('\u200D', ' ')
            ?.replace('\uFEFF', ' ')
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val hasRawTag = text.contains("<reply", ignoreCase = true) ||
            text.contains("</reply", ignoreCase = true)
        return if (hasRawTag) text else ESCAPED_TAG.replace(text) { escaped ->
            escaped.value.replace("&lt;", "<").replace("&gt;", ">")
        }
    }

    /** 块内清洗：代码围栏、标签、成对引号、Markdown 加粗、占位符全部剥掉。 */
    private fun cleanBlock(raw: String?): String? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text = text.lines()
            .filterNot { CODE_FENCE_LINE.matches(it) }
            .joinToString("\n")
            .trim()
        text = LABEL_PREFIX.replace(text, "").trim()
        text = stripPairedQuotes(text)
        text = text.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        text = text.replace(Regex("""\n{3,}"""), "\n\n").trim()
        if (text.isEmpty() || PLACEHOLDER.matches(text)) return null
        return text
    }

    /** 整段被引号包住时去壳（模型常见 `"…"`、`「…」` 包裹）。 */
    private fun stripPairedQuotes(text: String): String {
        if (text.length < 2) return text
        val first = text.first()
        val last = text.last()
        return if (QUOTE_PAIRS.any { it.first == first && it.second == last }) {
            text.substring(1, text.length - 1).trim()
        } else {
            text
        }
    }
}
