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

package com.verlintas.baic2.floatball

/**
 * 从扁平的聊天屏幕文本（无障碍树文本或 OCR 正文行）中提取「对方最后一条
 * 来信」候选，用作记忆召回与话术检索的收窄 query（v4 方案 §8.2：
 * 不再用整屏 OCR 做 recall terms）。
 *
 * 扁平文本没有发送方坐标，无法严格区分气泡归属；本对象采用确定性启发：
 * 先做**聊天界面门控**（必须存在时间分割行/IM 结构占位/界面词，文章与
 * 设置页等直接返回 null 由调用方回退整屏，保证质量不回退），再自底向上
 * 跳过时间分割、结构占位（图片/语音/表情）与界面词，取末尾连续最多
 * [MAX_LINES] 条消息行。参谋回合的典型触发点就是对方新消息到达，最后
 * 一个文本气泡即高概率的对方来信；拿不准时宁可取末尾文本也比整屏噪声窄。
 *
 * 纯函数、无 Android 依赖，可直接 JVM 单测。
 */
internal object IncomingCue {

    /** 一条来信最多合并的气泡行数（长气泡折行）。 */
    private const val MAX_LINES = 2

    /** 提取结果的字符预算（terms 还会再截断，过长无意义）。 */
    private const val MAX_CHARS = 80

    /** 自底向上最多扫描的行数（越过结构占位继续找上一条文本的上界）。 */
    private const val MAX_SCAN_LINES = 12

    /** 时间/日期分割行：HH:MM、昨天 14:30、10月15日 星期二、星期五 … */
    private val TIME_LINE = Regex(
        """^(?:今天|昨晚|今早|今晚|昨天|前天|明天|上午|下午|中午|凌晨|晚上|早上|晚)?\s*\d{1,2}:\d{2}(?::\d{2})?$""",
    )
    private val DATE_LINE = Regex(
        """^(?:\d{4}年)?\d{1,2}月\d{1,2}日(?:\s+星期[一二三四五六日天])?(?:\s+\d{1,2}:\d{2})?$""" +
            """|^星期[一二三四五六日天]$""",
    )

    /** 无文本线索的结构占位行：[图片]、【语音】、[动画表情] … */
    private val BRACKET_STRUCT = Regex(
        """^[\[【〖［\s]*.*(?:图片|语音|视频|动画表情|大表情|表情|文件|转账|红包|位置|名片|链接|音乐|通话|撤回了一条消息).*[\]】〗］\s]*$""",
    )
    private val STRUCT_PATTERNS = listOf(
        Regex("""^(?:你|对方)?拍了拍.*$"""),
        Regex("""^以下是?.*新消息$"""),
        Regex("""^对方正在输入(?:\.\.\.|…)?$"""),
        Regex("""^.*正在输入…?$"""),
        Regex("""^(?:消息已发出.*拒收|你已添加.*开始聊天|.*开启了朋友验证.*|以上是打招呼的内容.*)$"""),
        Regex("""^[\p{P}\p{S}\s]{1,2}$"""),
    )

    /** 整行相等才算界面词，避免误杀「发送报价」这类真实消息。 */
    private val CHROME_EXACT = setOf(
        "发送", "更多", "返回", "表情", "语音", "按住说话", "我", "+", "取消",
        "复制", "转发", "收藏", "引用", "删除", "提醒", "多选", "翻译", "选择",
        "切换", "视频号", "公众号", "服务号", "通讯录",
    )

    private val WHITESPACE = Regex("""\s+""")

    /**
     * 返回对方最后一条来信的文本候选；非聊天界面或无可用文本时返回 null。
     */
    fun latestIncoming(screenBody: String): String? {
        val lines = screenBody.split('\n')
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        if (lines.none { isChatMarker(it) }) return null

        val picked = ArrayDeque<String>()
        var chars = 0
        for (line in lines.asReversed().asSequence().take(MAX_SCAN_LINES)) {
            if (isSkipLine(line)) continue
            if (picked.any { it == line }) continue
            picked.addFirst(line)
            chars += line.length
            if (picked.size >= MAX_LINES || chars >= MAX_CHARS) break
        }
        return picked.joinToString(" ").take(MAX_CHARS).ifBlank { null }
    }

    /**
     * 召回/检索 query：聊天界面用来信候选，其他界面或提取失败时回退整屏
     * （维持 v3 行为，质量不回退）；[goal] 非空时以空格追加业务目标关键词。
     */
    fun recallQuery(screenBody: String, goal: String?, maxChars: Int): String {
        val cue = latestIncoming(screenBody) ?: screenBody.replace(WHITESPACE, " ").trim()
        val goalPart = goal?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append(cue)
            if (goalPart != null) append(' ').append(goalPart)
        }.take(maxChars)
    }

    /** 聊天界面强标记：时间分割/结构占位/系统提示/界面词，命中任一即算。 */
    private fun isChatMarker(line: String): Boolean =
        TIME_LINE.matches(line) || DATE_LINE.matches(line) ||
            isStructural(line) || line in CHROME_EXACT

    private fun isSkipLine(line: String): Boolean =
        TIME_LINE.matches(line) || DATE_LINE.matches(line) ||
            isStructural(line) || line in CHROME_EXACT

    private fun isStructural(line: String): Boolean {
        if (STRUCT_PATTERNS.any { it.matches(line) }) return true
        val hasBrackets = line.first() in "[【〖［" && line.last() in "]】〗］"
        return hasBrackets && BRACKET_STRUCT.matches(line)
    }
}
