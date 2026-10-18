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
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                dao.trimFor(contactId)
            }.onFailure { Log.d(TAG, "director log write failed", it) }
        }
        return decision
    }

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
