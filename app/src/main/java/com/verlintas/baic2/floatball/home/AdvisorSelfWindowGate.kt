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

package com.verlintas.baic2.floatball.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 自身窗口隐藏握手（方案 §5.5）：主页触发参谋后先 `moveTaskToBack`，服务必须
 * 等 [com.verlintas.baic2.MainActivity] 真正 `onStop`（自己的界面不在屏上）才能
 * 截屏——否则会把自家主页文字喂给 LLM（历史教训 §8.1 自噬）。
 *
 * 只做「最近一次 onStop」的一次性信号，不持有 Activity 引用；`awaitHidden`
 * 每次重新武装，避免复用到上一次的过期信号。
 */
object AdvisorSelfWindowGate {

    private val _hidden = MutableStateFlow(false)

    /** Activity `onStop` 调用（MainActivity 白名单纯增量）。 */
    fun markHidden() {
        _hidden.value = true
    }

    /** 显式复位（回到前台等场景，服务侧兜底用）。 */
    fun reset() {
        _hidden.value = false
    }

    /** 等待下一次「自身窗口已不可见」；超时返回 false（调用方必须放弃本次采集）。 */
    suspend fun awaitHidden(timeoutMs: Long): Boolean {
        _hidden.value = false
        return withTimeoutOrNull(timeoutMs) {
            _hidden.first { it }
            true
        } ?: false
    }
}
