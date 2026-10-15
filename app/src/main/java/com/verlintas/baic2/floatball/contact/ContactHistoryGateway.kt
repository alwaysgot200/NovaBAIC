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

package com.verlintas.baic2.floatball.contact

import com.verlintas.baic2.core.data.db.Baic2Database
import com.verlintas.baic2.core.data.db.MessageDao
import com.verlintas.baic2.core.data.mapper.ChatMapper
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import javax.inject.Inject
import javax.inject.Singleton

/** 从更早聊天里捞回的一条成交要件（报价/合同/账期…），带时间戳。 */
data class DealFact(
    val createdAt: Long,
    val content: String,
    /** true = USER 屏幕原文（真实聊天）；false = 参谋回复中引用的内容。 */
    val fromScreen: Boolean,
)

/**
 * 一个完整的「屏幕提问 + 参谋回复」配对（时间升序序列中的一项）。
 * [screenCreatedAt] 用于画像萃取的「距上次萃取」节流。
 */
data class ScreenTurn(
    val screenContent: String,
    val replyContent: String,
    val screenCreatedAt: Long,
)

/**
 * 成交要件网关（设计文档 §6.6）：当前屏出现成交信号但视口内缺要件时，
 * 跨该联系人**全部**参谋会话（合并带来的多个也覆盖）检索更早聊过的
 * 报价/合同/账期等关键行。
 *
 * 只读 public 的上游 [MessageDao.searchMessagesIn]，零上游改动；
 * 不做语义检索——固定小词表的 LIKE 命中即可，语义判断交给 LLM。
 */
@Singleton
class ContactHistoryGateway @Inject constructor(
    private val messageDao: MessageDao,
    private val db: Baic2Database,
    private val mapper: ChatMapper,
) {
    /**
     * 会话内最近一条自动屏幕消息（USER 且以 [labelPrefix] 起头）正文，
     * 没有返回 null。LIMIT 1 有界查询，替代整表加载后内存过滤。
     */
    suspend fun latestScreenBody(conversationId: Long, labelPrefix: String): String? {
        val rows = runCatching {
            messageDao.searchMessagesIn(
                conversationIds = listOf(conversationId),
                pattern = likePatternPrefix(labelPrefix),
                from = null,
                to = null,
                limit = LATEST_SCREEN_LIMIT,
            )
        }.getOrDefault(emptyList())
        return rows.firstOrNull { it.role == ChatRole.USER.name }?.content
    }

    /**
     * 会话最近 [limit] 条消息（时间升序）。瘦身历史只需要最后 60 条，
     * 有界读取替代随会话线性增长的整表加载。
     */
    suspend fun recentMessages(conversationId: Long, limit: Int): List<ChatMessage> =
        runCatching {
            messageDao.latestMessages(conversationId, limit)
                .map(mapper::messageToModel)
                .asReversed()
        }.getOrDefault(emptyList())

    /**
     * 会话内最近 [limitMessages] 条消息中的完整「屏幕提问 + 参谋回复」
     * 配对，时间正序。摘要窗口 12 回合、萃取阈值至多 10 回合，120 条
     * （约 60 配对）的上界远超需求；手动追问消息不参与配对。
     */
    suspend fun recentScreenTurns(
        conversationId: Long,
        labelPrefix: String,
        limitMessages: Int = RECENT_TURN_MESSAGES,
    ): List<ScreenTurn> {
        val messages = recentMessages(conversationId, limitMessages)
        val turns = ArrayList<ScreenTurn>()
        var pendingScreen: ChatMessage? = null
        for (message in messages) {
            when {
                message.role == ChatRole.USER && message.content.startsWith(labelPrefix) ->
                    pendingScreen = message
                message.role == ChatRole.ASSISTANT && pendingScreen != null -> {
                    turns += ScreenTurn(
                        screenContent = pendingScreen.content,
                        replyContent = message.content,
                        screenCreatedAt = pendingScreen.createdAt,
                    )
                    pendingScreen = null
                }
            }
        }
        return turns
    }

    /**
     * 会话内自动屏幕消息的总数（COUNT，有界整数返回，不装载实体）。
     * 回合刚完成时每条屏幕消息都有回复配对，作为滚动摘要的绝对回合序号。
     */
    suspend fun countScreenTurns(conversationId: Long, labelPrefix: String): Int =
        runCatching {
            val query = androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM messages WHERE conversationId = ? " +
                    "AND role = ? AND content LIKE ? ESCAPE '\\'",
                arrayOf<Any>(
                    conversationId,
                    ChatRole.USER.name,
                    likePatternPrefix(labelPrefix),
                ),
            )
            db.query(query).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }.getOrDefault(0)

    /** 当前屏出现哪些成交信号词（命中才值得翻旧账，避免每轮全库扫）。 */
    fun hasClosingSignal(currentScreen: String): Boolean =
        CLOSING_SIGNALS.any { it in currentScreen }

    /**
     * 在 [conversationIds] 范围内检索成交要件，去重后最多 [limit] 条；
     * 优先 USER 屏幕原文（真实聊天），同档内新消息在前。
     */
    suspend fun dealFacts(
        conversationIds: List<Long>,
        limit: Int = MAX_FACTS,
    ): List<DealFact> {
        if (conversationIds.isEmpty()) return emptyList()
        val seen = LinkedHashMap<Long, DealFact>()
        for (keyword in DEAL_KEYWORDS) {
            val rows = runCatching {
                messageDao.searchMessagesIn(
                    conversationIds = conversationIds,
                    pattern = likePattern(keyword),
                    from = null,
                    to = null,
                    limit = ROWS_PER_KEYWORD,
                )
            }.getOrDefault(emptyList())
            for (row in rows) {
                if (row.id in seen) continue
                val snippet = snippetAround(row.content, keyword) ?: continue
                seen[row.id] = DealFact(
                    createdAt = row.createdAt,
                    content = snippet,
                    fromScreen = row.role == ChatRole.USER.name,
                )
            }
            if (seen.size >= limit * 2) break
        }
        return seen.values.sortedWith(
            compareByDescending<DealFact> { it.fromScreen }
                .thenByDescending { it.createdAt },
        ).take(limit)
    }

    /**
     * 画像页「按人搜索」：在该联系人全部会话内做 LIKE 检索（§7），
     * 命中行按时间倒序返回。零上游改动，仅复用 [MessageDao.searchMessagesIn]。
     */
    suspend fun search(
        conversationIds: List<Long>,
        query: String,
        limit: Int = 50,
    ): List<DealFact> {
        val keyword = query.trim()
        if (conversationIds.isEmpty() || keyword.isEmpty()) return emptyList()
        val rows = runCatching {
            messageDao.searchMessagesIn(
                conversationIds = conversationIds,
                pattern = likePattern(keyword),
                from = null,
                to = null,
                limit = limit,
            )
        }.getOrDefault(emptyList())
        return rows.map {
            DealFact(
                createdAt = it.createdAt,
                content = stripScreenLabel(it.content).take(SEARCH_SNIPPET_MAX),
                fromScreen = it.role == ChatRole.USER.name,
            )
        }
    }

    /** 命中行可能是整屏 OCR，截取关键词前后窗口作为要件摘录。 */
    private fun snippetAround(content: String, keyword: String): String? {
        val body = stripScreenLabel(content)
        val hit = body.indexOf(keyword)
        if (hit < 0) return null
        val start = (hit - SNIPPET_LEAD).coerceAtLeast(0)
        val end = (hit + keyword.length + SNIPPET_TAIL).coerceAtMost(body.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < body.length) "…" else ""
        return (prefix + body.substring(start, end).replace(WHITESPACE_RUN, " ").trim() + suffix)
            .take(SNIPPET_MAX)
            .takeIf { it.isNotBlank() }
    }

    /** 去掉自动屏幕消息的「【屏幕内容】」前缀行（只去这种方括号前缀）。 */
    private fun stripScreenLabel(content: String): String =
        content.replaceFirst(SCREEN_LABEL_PREFIX, "").trim()

    private fun likePattern(term: String): String {
        val escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    /** 前缀匹配的 LIKE 参数（屏幕消息去重/计数用）。 */
    private fun likePatternPrefix(prefix: String): String {
        val escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "$escaped%"
    }

    private companion object {
        const val MAX_FACTS = 3
        const val ROWS_PER_KEYWORD = 10

        /** 最近屏幕消息探测：只需最新 1 条，多取几条防御 ASSISTANT 引用。 */
        const val LATEST_SCREEN_LIMIT = 5

        /** 配对/摘要回看的消息上界（约 60 个屏幕回合，远超 12 回合窗口）。 */
        const val RECENT_TURN_MESSAGES = 120
        const val SNIPPET_LEAD = 24
        const val SNIPPET_TAIL = 96
        const val SNIPPET_MAX = 120
        const val SEARCH_SNIPPET_MAX = 240

        /** 出现这些词才认为当前屏进入成交语境（防止无关回合翻旧账）。 */
        val CLOSING_SIGNALS = listOf(
            "下单", "买", "合同", "成交", "付款", "支付", "报价", "价格", "多少钱",
            "优惠", "便宜", "账期", "发票", "型号", "数量", "交付", "订", "定金",
        )

        /** 成交要件检索词：与钱、货、约定直接相关。 */
        val DEAL_KEYWORDS = listOf(
            "报价", "价格", "多少钱", "优惠", "合同", "账期", "发票",
            "型号", "数量", "交付", "定金", "付款",
        )

        /** 自动屏幕消息的「【标签】」前缀；companion 单例，避免每行消息重新编译。 */
        val SCREEN_LABEL_PREFIX = Regex("^【[^】]*】\\s*")

        /** 摘录里连续空白压一个空格。 */
        val WHITESPACE_RUN = Regex("\\s+")
    }
}
