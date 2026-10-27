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

    /**
     * 自动档启动阶段（2026-10-27 方案 §5.2 S1）：把「已进入自动」与
     * 「正在打开会话/等待首帧」区分开，避免导航十几秒里用户以为没反应。
     */
    enum class AutoPhase {
        /** 空闲或已停止。 */
        IDLE,

        /** 已受理启动请求，正在做前置校验/开总闸。 */
        STARTING,

        /** 正在导航到目标会话（可能数秒到数十秒）。 */
        NAVIGATING,

        /** 会话已就绪，等待首帧/主动首轮/来信。 */
        WAITING,

        /** 首轮已就绪，正常运行（收到回合/在治理/在发送）。 */
        READY,

        /** 启动链失败，本次托管不会继续（[Snapshot.autoNote] 带原因）。 */
        FAILED,
    }

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
        /** 启动阶段：状态签据此显示「启动中/打开微信…/托管中/失败」。 */
        val autoPhase: AutoPhase = AutoPhase.IDLE,
        /** 阶段说明（失败原因/静默跳过原因等，可直接展示的短句）。 */
        val autoNote: String? = null,
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
        phase: AutoPhase = AutoPhase.READY,
        note: String? = null,
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
                autoPhase = phase,
                autoNote = note,
            )
        }
    }

    /**
     * 只推进启动阶段，不改动模式与目标信息（2026-10-27 方案 §5.2 S1/S5）。
     * 非 AUTO 态调用是无效的（可能已停止/被别的档接管），直接忽略。
     */
    fun setAutoPhase(phase: AutoPhase, note: String? = null) {
        _state.update {
            if (it.mode != Mode.AUTO) it else it.copy(autoPhase = phase, autoNote = note)
        }
    }

    fun setIdle() {
        _state.update { Snapshot() }
    }
}
