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

import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaRunEvent
import com.verlintas.rpa.model.RpaScript
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * RPA 运行状态单例：所有权归 [RpaRunService]，脚本库 / 编辑器只读观察。
 *
 * 页面生命周期不再持有运行 Job——离开编辑器、`RpaActivity` 被回收都不影响
 * 后台执行；重新进页面时仍能从 [state] 恢复进度与结果。
 */
@Singleton
class RpaRunController @Inject constructor() {

    sealed interface State {
        data object Idle : State

        data class Running(
            val scriptId: String,
            val scriptName: String,
            /** 当前步骤在 `script.steps` 中的下标（0 基；未开始时为 -1）。 */
            val stepIndex: Int,
            /** `script.steps` 总数（含禁用步骤，与引擎下标一致）。 */
            val stepCount: Int,
            val stepLabel: String,
            val stepAction: RpaAction?,
            val line: RpaLine?,
            val startedAt: Long,
        ) : State {
            /** 供 UI 显示的 1 基进度；尚未进入第一步时为 0。 */
            val displayIndex: Int get() = (stepIndex + 1).coerceAtLeast(1)
        }

        data class Finished(
            val scriptId: String,
            val scriptName: String,
            val ok: Boolean,
            val detail: String,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<RpaRunEvent>>(emptyList())
    val events: StateFlow<List<RpaRunEvent>> = _events.asStateFlow()

    fun start(script: RpaScript, startedAt: Long = System.currentTimeMillis()) {
        _events.value = emptyList()
        _state.value = State.Running(
            scriptId = script.id,
            scriptName = script.name,
            stepIndex = -1,
            stepCount = script.steps.size,
            stepLabel = "",
            stepAction = null,
            line = null,
            startedAt = startedAt,
        )
    }

    /** 记录一条运行事件，并同步 Running 的当前步骤 / 线路。 */
    fun record(event: RpaRunEvent) {
        _events.update { (it + event).takeLast(EVENT_LIMIT) }
        when (event) {
            is RpaRunEvent.StepStarted -> _state.update { current ->
                if (current is State.Running) {
                    current.copy(
                        stepIndex = event.index,
                        stepLabel = event.step.label,
                        stepAction = event.step.action,
                        line = event.line ?: current.line,
                    )
                } else {
                    current
                }
            }

            is RpaRunEvent.LineFallback -> _state.update { current ->
                if (current is State.Running) current.copy(line = event.to) else current
            }

            else -> Unit
        }
    }

    /** 只在 Running 时收口；重复调用保持第一次结果，避免 finally 覆盖真实失败原因。 */
    fun finish(ok: Boolean, detail: String) {
        val running = _state.value as? State.Running ?: return
        _state.value = State.Finished(
            scriptId = running.scriptId,
            scriptName = running.scriptName,
            ok = ok,
            detail = detail,
        )
    }

    /** 用户看过结果后回到 Idle；运行中调用无效。 */
    fun dismissFinished() {
        if (_state.value is State.Finished) {
            _state.value = State.Idle
        }
    }

    /** 服务销毁 / 测试收尾：强制回到 Idle 并清空事件。 */
    fun reset() {
        _state.value = State.Idle
        _events.value = emptyList()
    }

    companion object {
        const val EVENT_LIMIT = 200
    }
}
