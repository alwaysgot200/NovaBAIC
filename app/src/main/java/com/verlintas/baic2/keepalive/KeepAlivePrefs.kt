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

package com.verlintas.baic2.keepalive

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 保活设置的唯一存储（独立文件 `floatball_keepalive`，与上游
 * SettingsRepository 无关）。强度档位、细项开关、重试参数改完即落盘；
 * 电池优化/通知/精确闹钟等系统状态一律实时查询，不在这里镜像。
 */
@Singleton
class KeepAlivePrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    /** 保活强度档位。 */
    enum class Mode(val value: Int) {
        LOW(0),
        SMART(1),
        PERMANENT(2),
    }

    data class Config(
        val mode: Mode = Mode.SMART,
        val bootEnabled: Boolean = true,
        val excludeRecents: Boolean = false,
        val silenceMusic: Boolean = false,
        val retryTimes: Int = DEFAULT_RETRY_TIMES,
        val retryDelaySec: Int = DEFAULT_RETRY_DELAY_SEC,
        val retryTimeoutSec: Int = DEFAULT_RETRY_TIMEOUT_SEC,
    )

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Config> = _state.asStateFlow()

    private fun load(): Config = Config(
        mode = Mode.entries.firstOrNull { it.value == prefs.getInt(KEY_MODE, Mode.SMART.value) }
            ?: Mode.SMART,
        bootEnabled = prefs.getBoolean(KEY_ENABLE_BOOT, true),
        excludeRecents = prefs.getBoolean(KEY_ENABLE_RECENTS, false),
        silenceMusic = prefs.getBoolean(KEY_ENABLE_MUSIC, false),
        retryTimes = prefs.getInt(KEY_RETRY_TIMES, DEFAULT_RETRY_TIMES).coerceIn(0, 10),
        retryDelaySec = prefs.getInt(KEY_RETRY_DELAY, DEFAULT_RETRY_DELAY_SEC).coerceIn(1, 60),
        retryTimeoutSec = prefs.getInt(KEY_RETRY_TIMEOUT, DEFAULT_RETRY_TIMEOUT_SEC).coerceIn(5, 120),
    )

    fun setMode(mode: Mode) = persist { putInt(KEY_MODE, mode.value) }
        .also { _state.update { it.copy(mode = mode) } }

    fun setBootEnabled(enabled: Boolean) = persist { putBoolean(KEY_ENABLE_BOOT, enabled) }
        .also { _state.update { it.copy(bootEnabled = enabled) } }

    fun setExcludeRecents(enabled: Boolean) =
        persist { putBoolean(KEY_ENABLE_RECENTS, enabled) }
            .also { _state.update { it.copy(excludeRecents = enabled) } }

    fun setSilenceMusic(enabled: Boolean) = persist { putBoolean(KEY_ENABLE_MUSIC, enabled) }
        .also { _state.update { it.copy(silenceMusic = enabled) } }

    fun setRetryTimes(value: Int) {
        val coerced = value.coerceIn(0, 10)
        persist { putInt(KEY_RETRY_TIMES, coerced) }
        _state.update { it.copy(retryTimes = coerced) }
    }

    fun setRetryDelaySec(value: Int) {
        val coerced = value.coerceIn(1, 60)
        persist { putInt(KEY_RETRY_DELAY, coerced) }
        _state.update { it.copy(retryDelaySec = coerced) }
    }

    fun setRetryTimeoutSec(value: Int) {
        val coerced = value.coerceIn(5, 120)
        persist { putInt(KEY_RETRY_TIMEOUT, coerced) }
        _state.update { it.copy(retryTimeoutSec = coerced) }
    }

    private inline fun persist(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    /** 退出旗标：用户显式退出后置 false，心跳/闹钟/开机均不许复活。 */
    var serviceDesired: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_DESIRED, true)
        set(value) = persist { putBoolean(KEY_SERVICE_DESIRED, value) }

    /** 首启保活引导已弹过（2026-10-22）：初装首次启动弹一次，之后不再打扰。 */
    var firstRunGuideShown: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN_GUIDE_SHOWN, false)
        set(value) = persist { putBoolean(KEY_FIRST_RUN_GUIDE_SHOWN, value) }

    private companion object {
        const val PREFS_FILE = "floatball_keepalive"
        const val KEY_MODE = "mode"
        const val KEY_ENABLE_BOOT = "enable_boot"
        const val KEY_ENABLE_RECENTS = "enable_exclude_recents"
        const val KEY_ENABLE_MUSIC = "enable_silence_music"
        const val KEY_RETRY_TIMES = "retry_times"
        const val KEY_RETRY_DELAY = "retry_delay_sec"
        const val KEY_RETRY_TIMEOUT = "retry_timeout_sec"
        const val KEY_SERVICE_DESIRED = "service_desired"
        const val KEY_FIRST_RUN_GUIDE_SHOWN = "first_run_guide_shown"

        const val DEFAULT_RETRY_TIMES = 3
        const val DEFAULT_RETRY_DELAY_SEC = 5
        const val DEFAULT_RETRY_TIMEOUT_SEC = 15
    }
}
