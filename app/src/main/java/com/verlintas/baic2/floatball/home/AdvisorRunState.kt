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

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 好参谋运行态唯一事实源（方案 §9.1）：服务写、主页三键操作条与状态签读。
 *
 * 只保存展示所需的快照，不持有任何业务对象——单次/连续/自动的真实生命周期
 * 仍在 [com.verlintas.baic2.floatball.FloatBallService] 的状态机里。
 */
@Singleton
class AdvisorRunState @Inject constructor() {

    enum class Mode { IDLE, ONESHOT, CONTINUOUS, AUTO }

    /** 触发来源：悬浮球手势 / 主页三键操作条（主页入口关闭分屏即停止连续）。 */
    enum class Source { FLOAT_BALL, HOME }

    data class Snapshot(
        val mode: Mode = Mode.IDLE,
        val source: Source = Source.FLOAT_BALL,
        /** 主页入口开启的连续：关闭分屏即停止（方案 §8.12）。 */
        val stopOnSplitClose: Boolean = false,
        /** 自动托管会话数（单会话=1）。 */
        val autoTargetCount: Int = 0,
        /** 当前正在处理的联系人名与 APP 名（状态签显示用）。 */
        val autoActiveContact: String? = null,
        val autoActiveApp: String? = null,
        /** 队列积压与会话暂停数（自动状态签/托管面板用）。 */
        val autoQueued: Int = 0,
        val autoPaused: Int = 0,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun setOneshot(source: Source) {
        _state.update { Snapshot(mode = Mode.ONESHOT, source = source) }
    }

    fun setContinuous(source: Source, stopOnSplitClose: Boolean) {
        _state.update {
            Snapshot(
                mode = Mode.CONTINUOUS,
                source = source,
                stopOnSplitClose = stopOnSplitClose,
            )
        }
    }

    fun setAuto(
        targetCount: Int,
        activeContact: String? = null,
        activeApp: String? = null,
        queued: Int = 0,
        paused: Int = 0,
        source: Source = Source.HOME,
    ) {
        _state.update {
            Snapshot(
                mode = Mode.AUTO,
                source = source,
                autoTargetCount = targetCount,
                autoActiveContact = activeContact,
                autoActiveApp = activeApp,
                autoQueued = queued,
                autoPaused = paused,
            )
        }
    }

    fun setIdle() {
        _state.update { Snapshot() }
    }
}
