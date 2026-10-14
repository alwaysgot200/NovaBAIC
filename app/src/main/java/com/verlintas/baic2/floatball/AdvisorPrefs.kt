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
    )

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
        )

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

    private companion object {
        const val PREFS_FILE = "floatball_advisor"
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
        const val DEFAULT_MAX_SESSION = 30 * 60_000L
    }
}
