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
    )

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        Settings(
            pollIntervalMs = prefs.getLong(KEY_POLL, 800L).coerceIn(300L, 5_000L),
            maxSessionMs = prefs.getLong(KEY_MAX_SESSION, DEFAULT_MAX_SESSION),
        ),
    )
    val state: StateFlow<Settings> = _state.asStateFlow()

    fun setPollInterval(ms: Long) {
        val value = ms.coerceIn(300L, 5_000L)
        prefs.edit().putLong(KEY_POLL, value).apply()
        _state.update { it.copy(pollIntervalMs = value) }
    }

    fun setMaxSession(ms: Long) {
        prefs.edit().putLong(KEY_MAX_SESSION, ms).apply()
        _state.update { it.copy(maxSessionMs = ms) }
    }

    fun conversationId(): Long = prefs.getLong(KEY_CONVERSATION_ID, 0L)

    fun setConversationId(id: Long) {
        prefs.edit().putLong(KEY_CONVERSATION_ID, id).apply()
    }

    private companion object {
        const val PREFS_FILE = "floatball_advisor"
        const val KEY_POLL = "poll_interval_ms"
        const val KEY_MAX_SESSION = "max_session_ms"
        const val KEY_CONVERSATION_ID = "advisor_conversation_id"
        const val DEFAULT_MAX_SESSION = 30 * 60_000L
    }
}
