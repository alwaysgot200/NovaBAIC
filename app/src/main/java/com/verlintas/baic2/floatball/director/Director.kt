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

package com.verlintas.baic2.floatball.director

import android.util.Log
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.DirectorLogEntity
import com.verlintas.baic2.floatball.advisor.data.ROUTE_ACTION_HINT
import com.verlintas.baic2.floatball.advisor.data.ROUTE_BRIEF
import com.verlintas.baic2.floatball.advisor.data.ROUTE_NOOP
import com.verlintas.baic2.floatball.advisor.data.ROUTE_REPLAN_HINT
import com.verlintas.baic2.floatball.advisor.data.ROUTE_REPLY
import com.verlintas.baic2.floatball.advisor.data.ROUTE_SMALL_TALK
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_INCOMING
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_MANUAL
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_PROACTIVE
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_SCHEDULED
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 调度中枢（方案 §5，M0 骨架）：调 [DirectorRoutes] 做确定性决策，并把每次
 * 决策落 director_logs 台账（可复盘、可调参）。M0 只做记录与观察性 replan
 * 信号，不改任何现有回合控制流；PROACTIVE/SCHEDULED 触发路由由 M2 放开。
 */
@Singleton
class Director @Inject constructor(private val db: AdvisorDatabase) {

    /** 与 AdvisorRepository/GoalRepository 同款：DAO 不进 Hilt 图，从库属性委托取。 */
    private val dao get() = db.directorLogDao()

    /**
     * 决策并记台账。决策本身是纯函数永不失败；台账是旁路，任何持久化失败
     * 只 Log.d 不上抛——绝不阻断参谋回合（调用方无需再包 runCatching）。
     * [contactId] 为 null（回合未绑定联系人）时只决策不落库。
     * [turnMessageId] 为触发消息 id（回合的 USER 消息，prepareTurn/入口落库
     * 即产生）——与 talk_missions.turnMessageId 同锚，闭合方案 §6 的
     * 「决策→回合→结局」三层回放链。
     */
    suspend fun route(
        input: DirectorInput,
        contactId: Long?,
        conversationId: Long? = null,
        turnMessageId: Long? = null,
    ): DirectorDecision {
        val decision = DirectorRoutes.decide(input)
        if (contactId != null) {
            runCatching {
                dao.insert(
                    DirectorLogEntity(
                        contactId = contactId,
                        conversationId = conversationId,
                        trigger = input.trigger.code(),
                        route = decision.route.code(),
                        reason = decision.reason,
                        turnMessageId = turnMessageId,
                        replanHint = decision.replanHint,
                        actionHint = decision.actionHint,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                dao.trimFor(contactId)
            }.onFailure { Log.d(TAG, "director log write failed", it) }
        }
        return decision
    }

    /**
     * 回填 PROACTIVE 台账行的回合锚点（§6 三层回放链的检点时序补口）：
     * 周期检点先落决策行（此时回合消息尚未产生，turnMessageId 只能为空），
     * ScreenAdvisor.runProactiveSmalltalk 落库触发消息后由检点把最新一条
     * proactive/smalltalk 行的锚点补上，链路闭合。旁路：失败只 Log.d 不上抛。
     */
    suspend fun attachProactiveTurnMessage(contactId: Long, turnMessageId: Long) {
        runCatching {
            dao.attachTurnMessageToLatest(contactId, TRIGGER_PROACTIVE, ROUTE_SMALL_TALK, turnMessageId)
        }.onFailure { Log.d(TAG, "director turn backfill failed", it) }
    }

    /**
     * F7 action_hint 消费面（方案 §5.6：UI 只在用户打开 GoalTab 时消费）：
     * 新鲜度窗口（[sinceMs] 由调用方取 ACTION_HINT_DWELL_DAYS 对齐）内最近
     * 一条 actionHint 决策行；无/过期返回 null（驻留解除后旧横幅不复活）。
     * 旁路：失败只 Log.d 不上抛（查询失败不打扰 UI）。
     */
    suspend fun latestActionHint(contactId: Long, sinceMs: Long): DirectorLogEntity? =
        runCatching { dao.latestActionHint(contactId, sinceMs) }.getOrNull()

    private fun DirectorTrigger.code(): String = when (this) {
        DirectorTrigger.INCOMING -> TRIGGER_INCOMING
        DirectorTrigger.PROACTIVE -> TRIGGER_PROACTIVE
        DirectorTrigger.SCHEDULED -> TRIGGER_SCHEDULED
        DirectorTrigger.MANUAL -> TRIGGER_MANUAL
    }

    private fun DirectorRoute.code(): String = when (this) {
        DirectorRoute.REPLY -> ROUTE_REPLY
        DirectorRoute.SMALL_TALK -> ROUTE_SMALL_TALK
        DirectorRoute.REPLAN_HINT -> ROUTE_REPLAN_HINT
        DirectorRoute.ACTION_HINT -> ROUTE_ACTION_HINT
        DirectorRoute.BRIEF -> ROUTE_BRIEF
        DirectorRoute.NOOP -> ROUTE_NOOP
    }

    private companion object {
        const val TAG = "Director"
    }
}
