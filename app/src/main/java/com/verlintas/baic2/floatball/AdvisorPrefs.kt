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

package com.verlintas.baic2.floatball

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 老参谋专属设置（独立于上游 SettingsRepository，避免触碰上游）。
 * 运行中修改下一轮循环即生效；持久化与「老参谋」会话 ID 同文件。
 */
@Singleton
class AdvisorPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    data class Settings(
        /** 连续模式抓帧节奏（毫秒）。 */
        val pollIntervalMs: Long = 800L,
        /** 连续模式单次时长上限（毫秒），0 = 不限。 */
        val maxSessionMs: Long = 30 * 60_000L,
        /** 单次参谋额外上滑几屏（0=单屏，上限 3；方案 §6.6）。 */
        val autoScrollScreens: Int = 2,
        /** 每次滑动后等待渲染的毫秒（600~1500）。 */
        val scrollSettleMs: Long = 800L,
        /** 拼接完成后下滑回底，恢复原阅读位置。 */
        val scrollBack: Boolean = true,
        /** 滑动距离占内容区高度比例（<1 故意留重叠带）。 */
        val swipeHeightRatio: Float = 0.72f,
        /** 禁滑 IM 包名（实测某 IM 上滑触发已读时加入；空=不禁）。 */
        val swipeBlockedPackages: Set<String> = emptySet(),
        /**
         * 连续模式静止画面的抓帧降频上限倍数：1=不降频，2/4=间隔最多倍增
         * 到 2 倍/4 倍（检测到运动立即恢复）。省电与反应延迟的权衡。
         */
        val idleBackoffMultiplier: Int = 2,
        /** 回合后后台滚动摘要（一次额外 LLM 调用）总开关。 */
        val digestEnabled: Boolean = true,
        /** 每完成多少个屏幕回合刷新一次滚动摘要。 */
        val digestEveryTurns: Int = 6,
        /** 回合后后台对方画像萃取（一次额外 LLM 调用，仅绑定联系人）总开关。 */
        val extractEnabled: Boolean = true,
        /** 每完成多少个屏幕回合至多萃取一次画像。 */
        val extractEveryTurns: Int = 5,
        /**
         * 回合内记忆工具模式：
         * 0=智能（手动参谋/追问走全功能 AgentLoop，可自主检索/写入记忆；
         *   连续模式自动回合走无工具直连，省掉每回合 7 个工具 schema 与
         *   记忆协议长提示词）；1=始终增强（全部回合带工具，最聪明最费 token）；
         * 2=始终精简（全部回合无工具直连，最省，记忆仍由 App 侧主动注入）。
         */
        val memoryMode: Int = MEMORY_MODE_AUTO,
        /** 参谋回答长度档：0=简洁(~120字)，1=标准(~260字)，2=详细(~500字)。 */
        val answerLength: Int = ANSWER_STANDARD,
        /** 屏幕去重灵敏度：0=宽松(多问少漏)，1=标准，2=严格(少问省 token)。 */
        val dedupLevel: Int = DEDUP_STANDARD,
        /** 连续模式单次会话 token 预算（输入+输出），0=不限；达到后自动停录。 */
        val sessionTokenBudget: Long = 0L,
    ) {
        /** OCR 层文本相似度阈值（FrameDiffEngine 用）：越严格阈值越高，越少发问。 */
        val ocrDedupSimilarity: Float
            get() = when (dedupLevel) {
                DEDUP_LOOSE -> 0.72f
                DEDUP_STRICT -> 0.86f
                else -> 0.80f
            }

        /** 入库前相邻屏幕去重阈值（ScreenAdvisor 用）。 */
        val screenDedupSimilarity: Float
            get() = when (dedupLevel) {
                DEDUP_LOOSE -> 0.88f
                DEDUP_STRICT -> 0.95f
                else -> 0.92f
            }

        /** 回答长度档对应的硬 maxTokens（输出 token 单价远高于输入）。 */
        val answerMaxTokens: Int
            get() = when (answerLength) {
                ANSWER_CONCISE -> 220
                ANSWER_DETAILED -> 850
                else -> 420
            }

        /** 回答长度档对应的中文字数目标（拼进系统提示词）。 */
        val answerTargetChars: Int
            get() = when (answerLength) {
                ANSWER_CONCISE -> 120
                ANSWER_DETAILED -> 500
                else -> 260
            }
    }

    /** Token 用量累计（主参谋回合；后台摘要/萃取不计入）。 */
    data class TokenStats(
        val totalInput: Long = 0L,
        val totalOutput: Long = 0L,
        val totalTurns: Long = 0L,
        val sessionInput: Long = 0L,
        val sessionOutput: Long = 0L,
    ) {
        val sessionTotal: Long get() = sessionInput + sessionOutput
    }

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadSettings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    private fun loadSettings(): Settings =
        Settings(
            pollIntervalMs = prefs.getLong(KEY_POLL, 800L).coerceIn(300L, 5_000L),
            maxSessionMs = prefs.getLong(KEY_MAX_SESSION, DEFAULT_MAX_SESSION),
            autoScrollScreens = prefs.getInt(KEY_SCROLL_SCREENS, 2).coerceIn(0, 3),
            scrollSettleMs = prefs.getLong(KEY_SCROLL_SETTLE, 800L).coerceIn(600L, 1_500L),
            scrollBack = prefs.getBoolean(KEY_SCROLL_BACK, true),
            swipeHeightRatio = prefs.getFloat(KEY_SWIPE_RATIO, 0.72f).coerceIn(0.5f, 0.9f),
            swipeBlockedPackages = prefs.getStringSet(KEY_SWIPE_BLOCKLIST, emptySet())
                ?.filter { it.isNotBlank() }?.toSet().orEmpty(),
            idleBackoffMultiplier = prefs.getInt(KEY_IDLE_BACKOFF, 2).let {
                if (it in BACKOFF_OPTIONS) it else 2
            },
            digestEnabled = prefs.getBoolean(KEY_DIGEST_ENABLED, true),
            digestEveryTurns = prefs.getInt(KEY_DIGEST_EVERY, 6).let {
                if (it in DIGEST_OPTIONS) it else 6
            },
            extractEnabled = prefs.getBoolean(KEY_EXTRACT_ENABLED, true),
            extractEveryTurns = prefs.getInt(KEY_EXTRACT_EVERY, 5).let {
                if (it in EXTRACT_OPTIONS) it else 5
            },
            memoryMode = prefs.getInt(KEY_MEMORY_MODE, MEMORY_MODE_AUTO).let {
                if (it in MEMORY_MODE_OPTIONS) it else MEMORY_MODE_AUTO
            },
            answerLength = prefs.getInt(KEY_ANSWER_LENGTH, ANSWER_STANDARD).let {
                if (it in ANSWER_OPTIONS) it else ANSWER_STANDARD
            },
            dedupLevel = prefs.getInt(KEY_DEDUP_LEVEL, DEDUP_STANDARD).let {
                if (it in DEDUP_LEVEL_OPTIONS) it else DEDUP_STANDARD
            },
            sessionTokenBudget = prefs.getLong(KEY_SESSION_BUDGET, 0L)
                .takeIf { it in TOKEN_BUDGET_OPTIONS } ?: 0L,
        )

    private val _tokenStats = MutableStateFlow(loadTokenStats())
    val tokenStats: StateFlow<TokenStats> = _tokenStats.asStateFlow()

    fun setPollInterval(ms: Long) {
        val value = ms.coerceIn(300L, 5_000L)
        prefs.edit().putLong(KEY_POLL, value).apply()
        _state.update { it.copy(pollIntervalMs = value) }
    }

    fun setMaxSession(ms: Long) {
        prefs.edit().putLong(KEY_MAX_SESSION, ms).apply()
        _state.update { it.copy(maxSessionMs = ms) }
    }

    fun setAutoScrollScreens(screens: Int) {
        val value = screens.coerceIn(0, 3)
        prefs.edit().putInt(KEY_SCROLL_SCREENS, value).apply()
        _state.update { it.copy(autoScrollScreens = value) }
    }

    fun setScrollSettleMs(ms: Long) {
        val value = ms.coerceIn(600L, 1_500L)
        prefs.edit().putLong(KEY_SCROLL_SETTLE, value).apply()
        _state.update { it.copy(scrollSettleMs = value) }
    }

    fun setScrollBack(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCROLL_BACK, enabled).apply()
        _state.update { it.copy(scrollBack = enabled) }
    }

    fun setSwipeHeightRatio(ratio: Float) {
        val value = ratio.coerceIn(0.5f, 0.9f)
        prefs.edit().putFloat(KEY_SWIPE_RATIO, value).apply()
        _state.update { it.copy(swipeHeightRatio = value) }
    }

    /** 整体替换禁滑名单（运行中改，下一回合即生效）。 */
    fun setSwipeBlockedPackages(packages: Set<String>) {
        val value = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // SharedPreferences 直接持有调用方集合，防御性拷一份再存。
        prefs.edit().putStringSet(KEY_SWIPE_BLOCKLIST, value.toSet()).apply()
        _state.update { it.copy(swipeBlockedPackages = value) }
    }

    /** 连续模式静止降频倍数（仅接受 1/2/4，运行中改下一抓拍循环即生效）。 */
    fun setIdleBackoffMultiplier(multiplier: Int) {
        val value = multiplier.takeIf { it in BACKOFF_OPTIONS } ?: 2
        prefs.edit().putInt(KEY_IDLE_BACKOFF, value).apply()
        _state.update { it.copy(idleBackoffMultiplier = value) }
    }

    fun setDigestEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIGEST_ENABLED, enabled).apply()
        _state.update { it.copy(digestEnabled = enabled) }
    }

    fun setDigestEveryTurns(turns: Int) {
        val value = turns.takeIf { it in DIGEST_OPTIONS } ?: 6
        prefs.edit().putInt(KEY_DIGEST_EVERY, value).apply()
        _state.update { it.copy(digestEveryTurns = value) }
    }

    fun setExtractEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EXTRACT_ENABLED, enabled).apply()
        _state.update { it.copy(extractEnabled = enabled) }
    }

    fun setExtractEveryTurns(turns: Int) {
        val value = turns.takeIf { it in EXTRACT_OPTIONS } ?: 5
        prefs.edit().putInt(KEY_EXTRACT_EVERY, value).apply()
        _state.update { it.copy(extractEveryTurns = value) }
    }

    /** 回合内记忆工具模式（0 智能 / 1 始终增强 / 2 始终精简，下一回合生效）。 */
    fun setMemoryMode(mode: Int) {
        val value = mode.takeIf { it in MEMORY_MODE_OPTIONS } ?: MEMORY_MODE_AUTO
        prefs.edit().putInt(KEY_MEMORY_MODE, value).apply()
        _state.update { it.copy(memoryMode = value) }
    }

    fun setAnswerLength(length: Int) {
        val value = length.takeIf { it in ANSWER_OPTIONS } ?: ANSWER_STANDARD
        prefs.edit().putInt(KEY_ANSWER_LENGTH, value).apply()
        _state.update { it.copy(answerLength = value) }
    }

    fun setDedupLevel(level: Int) {
        val value = level.takeIf { it in DEDUP_LEVEL_OPTIONS } ?: DEDUP_STANDARD
        prefs.edit().putInt(KEY_DEDUP_LEVEL, value).apply()
        _state.update { it.copy(dedupLevel = value) }
    }

    fun setSessionTokenBudget(tokens: Long) {
        val value = tokens.takeIf { it in TOKEN_BUDGET_OPTIONS } ?: 0L
        prefs.edit().putLong(KEY_SESSION_BUDGET, value).apply()
        _state.update { it.copy(sessionTokenBudget = value) }
    }

    /** 记录一个主参谋回合的实际用量（模型 Usage 事件；缺测按 0 计），累计持久化。 */
    fun recordTurnUsage(inputTokens: Long, outputTokens: Long) {
        val current = _tokenStats.value
        val updated = TokenStats(
            totalInput = current.totalInput + inputTokens.coerceAtLeast(0L),
            totalOutput = current.totalOutput + outputTokens.coerceAtLeast(0L),
            totalTurns = current.totalTurns + 1,
            sessionInput = current.sessionInput + inputTokens.coerceAtLeast(0L),
            sessionOutput = current.sessionOutput + outputTokens.coerceAtLeast(0L),
        )
        prefs.edit()
            .putLong(KEY_TOTAL_INPUT, updated.totalInput)
            .putLong(KEY_TOTAL_OUTPUT, updated.totalOutput)
            .putLong(KEY_TOTAL_TURNS, updated.totalTurns)
            .putLong(KEY_SESSION_INPUT, updated.sessionInput)
            .putLong(KEY_SESSION_OUTPUT, updated.sessionOutput)
            .apply()
        _tokenStats.value = updated
    }

    /** 开始新一轮连续参谋时清零会话级计数（总计不清）。 */
    fun resetSessionUsage() {
        val current = _tokenStats.value
        val updated = current.copy(sessionInput = 0L, sessionOutput = 0L)
        prefs.edit().remove(KEY_SESSION_INPUT).remove(KEY_SESSION_OUTPUT).apply()
        _tokenStats.value = updated
    }

    /** 设置页「清零」：总计与会话计数一并清空。 */
    fun clearAllUsage() {
        prefs.edit()
            .remove(KEY_TOTAL_INPUT).remove(KEY_TOTAL_OUTPUT).remove(KEY_TOTAL_TURNS)
            .remove(KEY_SESSION_INPUT).remove(KEY_SESSION_OUTPUT)
            .apply()
        _tokenStats.value = TokenStats()
    }

    private fun loadTokenStats(): TokenStats = TokenStats(
        totalInput = prefs.getLong(KEY_TOTAL_INPUT, 0L).coerceAtLeast(0L),
        totalOutput = prefs.getLong(KEY_TOTAL_OUTPUT, 0L).coerceAtLeast(0L),
        totalTurns = prefs.getLong(KEY_TOTAL_TURNS, 0L).coerceAtLeast(0L),
        sessionInput = prefs.getLong(KEY_SESSION_INPUT, 0L).coerceAtLeast(0L),
        sessionOutput = prefs.getLong(KEY_SESSION_OUTPUT, 0L).coerceAtLeast(0L),
    )

    fun conversationId(): Long = prefs.getLong(KEY_CONVERSATION_ID, 0L)

    fun setConversationId(id: Long) {
        prefs.edit().putLong(KEY_CONVERSATION_ID, id).apply()
    }

    /** 更早屏幕的滚动摘要（ AdvisorHistory 的占位 ASSISTANT 消息用）。 */
    fun screenDigest(): String? = prefs.getString(KEY_DIGEST, null)?.takeIf { it.isNotBlank() }

    /** 摘要已覆盖到第几个自动屏幕回合。 */
    fun digestCoveredTurns(): Int = prefs.getInt(KEY_DIGEST_TURNS, 0)

    fun setScreenDigest(digest: String, coveredTurns: Int) {
        prefs.edit()
            .putString(KEY_DIGEST, digest.take(300))
            .putInt(KEY_DIGEST_TURNS, coveredTurns)
            .apply()
    }

    /** 清空会话时同步重置摘要。 */
    fun resetScreenDigest() {
        prefs.edit().remove(KEY_DIGEST).remove(KEY_DIGEST_TURNS).apply()
    }

    companion object {
        private const val PREFS_FILE = "floatball_advisor"
        const val KEY_POLL = "poll_interval_ms"
        const val KEY_MAX_SESSION = "max_session_ms"
        const val KEY_SCROLL_SCREENS = "auto_scroll_screens"
        const val KEY_SCROLL_SETTLE = "scroll_settle_ms"
        const val KEY_SCROLL_BACK = "scroll_back"
        const val KEY_SWIPE_RATIO = "swipe_height_ratio"
        const val KEY_SWIPE_BLOCKLIST = "swipe_blocked_packages"
        const val KEY_CONVERSATION_ID = "advisor_conversation_id"
        const val KEY_DIGEST = "screen_digest"
        const val KEY_DIGEST_TURNS = "screen_digest_turns"
        const val KEY_IDLE_BACKOFF = "idle_backoff_multiplier"
        const val KEY_DIGEST_ENABLED = "digest_enabled"
        const val KEY_DIGEST_EVERY = "digest_every_turns"
        const val KEY_EXTRACT_ENABLED = "extract_enabled"
        const val KEY_EXTRACT_EVERY = "extract_every_turns"
        const val KEY_MEMORY_MODE = "memory_mode"
        const val KEY_ANSWER_LENGTH = "answer_length"
        const val KEY_DEDUP_LEVEL = "dedup_level"
        const val KEY_SESSION_BUDGET = "session_token_budget"
        const val KEY_TOTAL_INPUT = "usage_total_input"
        const val KEY_TOTAL_OUTPUT = "usage_total_output"
        const val KEY_TOTAL_TURNS = "usage_total_turns"
        const val KEY_SESSION_INPUT = "usage_session_input"
        const val KEY_SESSION_OUTPUT = "usage_session_output"
        const val DEFAULT_MAX_SESSION = 30 * 60_000L

        /** 静止降频允许的倍数档。 */
        val BACKOFF_OPTIONS = setOf(1, 2, 4)

        /** 后台摘要/萃取的允许频率档（回合数）。 */
        val DIGEST_OPTIONS = setOf(3, 6, 12)
        val EXTRACT_OPTIONS = setOf(3, 5, 10)

        /** 记忆工具模式档。 */
        const val MEMORY_MODE_AUTO = 0
        const val MEMORY_MODE_FULL = 1
        const val MEMORY_MODE_LEAN = 2
        val MEMORY_MODE_OPTIONS = setOf(0, 1, 2)

        /** 回答长度档。 */
        const val ANSWER_CONCISE = 0
        const val ANSWER_STANDARD = 1
        const val ANSWER_DETAILED = 2
        val ANSWER_OPTIONS = setOf(0, 1, 2)

        /** 去重灵敏度档。 */
        const val DEDUP_LOOSE = 0
        const val DEDUP_STANDARD = 1
        const val DEDUP_STRICT = 2
        val DEDUP_LEVEL_OPTIONS = setOf(0, 1, 2)

        /** 连续模式 token 预算档（0=不限）。 */
        val TOKEN_BUDGET_OPTIONS = setOf(0L, 20_000L, 50_000L, 100_000L)
    }
}
