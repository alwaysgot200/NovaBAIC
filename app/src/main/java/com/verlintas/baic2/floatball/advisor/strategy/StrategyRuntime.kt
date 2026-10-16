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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor.strategy

import android.util.Log
import com.verlintas.baic2.floatball.advisor.CardCompiler
import com.verlintas.baic2.floatball.advisor.GoalPlanner
import com.verlintas.baic2.floatball.advisor.StyleNoteCompiler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 慢车道调度器（§5.1）：独立 IO 作用域 + 全局编译 Mutex（串行，避免同联系人并发重编）
 * + inFlight 键去重（同 key 已排队/在跑则忽略新请求）。
 *
 * 所有作业 runCatching 隔离：失败只 Log.d，绝不影响参谋主回合（降级矩阵）。
 * L3 编译成功产生 roadmap 后自动排策略卡编译（由 [GoalPlanner.compileGoal] 返回值驱动）。
 */
@Singleton
class StrategyRuntime @Inject constructor(
    private val planner: GoalPlanner,
    private val cardCompiler: CardCompiler,
    private val styleNoteCompiler: StyleNoteCompiler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val compileLock = Mutex()
    private val inFlight = mutableSetOf<String>()

    /** 请求编译目标（L3→outcomes；L2→roadmap+L1+首站+策略卡）。force=手动重编时忽略草稿幂等。 */
    fun requestGoalCompile(goalId: Long, force: Boolean = false) {
        enqueue(keyGoal(goalId)) {
            if (planner.compileGoal(goalId, force)) {
                cardCompiler.compile(contactIdOfGoal(goalId))
            }
        }
    }

    /** 请求编译策略卡（roadmap 入库后 / 手动重编 / 全局卡）。 */
    fun requestCardCompile(contactId: Long) {
        enqueue(keyCard(contactId)) {
            cardCompiler.compile(contactId)
        }
    }

    /**
     * P3 §八：请求编译风格卡的 customNote（保存含手写补充的卡后入队）。
     * 与其他编译共用全局 Mutex 串行；失败由编译器写内存态，不影响主链路。
     */
    fun requestStyleCompile(cardId: Long) {
        enqueue(keyStyle(cardId)) {
            styleNoteCompiler.compile(cardId)
        }
    }

    /**
     * P3 reroute：异步生成新路线草稿，[onReady] 在 IO 线程回调（null=失败/已有待确认草稿）。
     * 入慢车道串行队列，绝不静默迁移阶段——采用/拒绝见 [acceptReroute]/[rejectReroute]。
     */
    fun requestReroute(goalId: Long, onReady: (GoalPlanner.ReroutePlan?) -> Unit) {
        scope.launch {
            val plan = runCatching { planner.runReroute(goalId) }.getOrNull()
            onReady(plan)
        }
    }

    /** P3 reroute 采用：迁移阶段 + 激活新版（调用方自行切回主线程刷 UI）。 */
    suspend fun acceptReroute(goalId: Long, decompId: Long): Boolean =
        runCatching { planner.acceptReroute(goalId, decompId) }.getOrDefault(false)

    /** P3 reroute 拒绝：草稿留存不激活。 */
    suspend fun rejectReroute(decompId: Long) {
        runCatching { planner.rejectReroute(decompId) }
    }

    /**
     * 目标保存后的完整链路：roadmap 脏了就编卡。contactId 由 GoalPlanner 内部递归掌握，
     * 这里在 goal 编译后再查一次目标拿 contactId（L3 递归编 L2 时 cardCompile 需要联系人维度）。
     */
    private suspend fun contactIdOfGoal(goalId: Long): Long =
        planner.contactIdOf(goalId)

    private fun enqueue(key: String, block: suspend () -> Unit) {
        scope.launch {
            compileLock.withLock {
                synchronized(inFlight) {
                    if (!inFlight.add(key)) return@withLock
                }
                try {
                    runCatching { block() }.onFailure { Log.d(TAG, "compile job failed: $key", it) }
                } finally {
                    synchronized(inFlight) { inFlight.remove(key) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "StrategyRuntime"

        fun keyGoal(goalId: Long) = "goal:$goalId"
        fun keyCard(contactId: Long) = "card:$contactId"
        fun keyStyle(cardId: Long) = "style:$cardId"
    }
}
