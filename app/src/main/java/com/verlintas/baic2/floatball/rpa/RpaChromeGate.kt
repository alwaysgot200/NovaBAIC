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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 悬浮球隐藏闸门：任何「全屏 RPA 工具 / 后台运行」都能按原因占位，
 * [FloatBallService] 只在集合为空时才允许重建球。
 *
 * 用原因集合而不是布尔值，避免两个工具互相把对方的隐藏状态清掉：
 * - `quickfind`：以图找图透明页（P3）；
 * - `run`：RPA 后台运行（P4）。
 *
 * 闸门只表达「球应不应该在」，不持有窗口；真正 hide/restore 由服务完成。
 */
@Singleton
class RpaChromeGate @Inject constructor() {

    private val _reasons = MutableStateFlow<Set<String>>(emptySet())
    val reasons: StateFlow<Set<String>> = _reasons.asStateFlow()

    val isHidden: Boolean get() = _reasons.value.isNotEmpty()

    fun hide(reason: String) {
        _reasons.update { it + reason }
    }

    fun show(reason: String) {
        _reasons.update { it - reason }
    }

    /** 进程内兜底：服务销毁 / 测试收尾时清空所有占位。 */
    fun reset() {
        _reasons.value = emptySet()
    }

    companion object {
        const val REASON_QUICK_FIND = "quickfind"
        const val REASON_RUN = "run"
    }
}
