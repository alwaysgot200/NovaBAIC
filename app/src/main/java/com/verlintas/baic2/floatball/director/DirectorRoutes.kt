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

/**
 * 触发源四类（方案 §5.1）：A=INCOMING 对方来信（连续模式差分帧/通知监听唤醒）、
 * B=PROACTIVE 主动时机（M2 起）、C=SCHEDULED 周期检点（M2 起）、D=MANUAL 用户手动。
 */
enum class DirectorTrigger { INCOMING, PROACTIVE, SCHEDULED, MANUAL }

/**
 * 路由决策（方案 §5.1 规则表）。M0 只放行 REPLY / BRIEF / NOOP 三类；
 * GUIDE / SMALL_TALK / REPLAN_HINT / ACTION_HINT 为 M1/M2 落地的占位枚举，
 * 规则表放行前不会作为路由产出。
 */
enum class DirectorRoute { REPLY, GUIDE, SMALL_TALK, REPLAN_HINT, ACTION_HINT, BRIEF, NOOP }

/**
 * Director 的输入态势。M0 规则表只消费 [trigger] 与失败信号（failStreak /
 * consecutiveRetreats）；其余字段为 M1/M2 预留（StrategyBrief.assess 与
 * ProactivePolicy 接入后填充），缺省即可，不参与路由。
 */
data class DirectorInput(
    val trigger: DirectorTrigger,
    val stageCode: String? = null,
    val failStreak: Int = 0,
    val consecutiveRetreats: Int = 0,
    val stageDwellDays: Long = 0L,
    /** 对方最后一次来信时间；从未收到来信时为 null（规则表必须容忍）。 */
    val lastIncomingAtMs: Long? = null,
    val nowMs: Long = 0L,
)

/**
 * 路由决策结果。[reason] 是命中的规则键（原样落 director_logs.reason，
 * 如 "incoming_default"/"proactive_not_enabled_m0"，可回放验证）；[replanHint]
 * 是观察性重规划信号（failStreak≥3 或连续 retreat×2，即 P3 reroute 条件），
 * M0 只随决策落库、不改变任何路由与控制流。
 */
data class DirectorDecision(
    val route: DirectorRoute,
    val reason: String,
    val replanHint: Boolean = false,
)

/**
 * 确定性路由规则表（方案 §5.1，纯函数无 Android 依赖，JVM 可单测）。
 * LLM 只在"发什么"环节介入，不在"做什么"环节兜圈子（方案 §2.3）：
 * 本表对"本轮该干什么"给出确定答案，模糊时的轻 LLM 分类留给后续里程碑。
 */
object DirectorRoutes {

    /** 目标失败连击阈值：≥3 建议复评（与 MissionJudgeRunner 的 failStreak 口径一致）。 */
    const val REPLAN_FAIL_STREAK_THRESHOLD = 3

    /** 同站连续回撤阈值：≥2 建议重规划（与 StageGraph reroute 条件一致）。 */
    const val REPLAN_RETREAT_STREAK_THRESHOLD = 2

    /**
     * 按触发源路由（M0 逐字规则）：
     * - INCOMING → REPLY（incoming_default）：走现有 T2→T6 主链路；
     * - MANUAL → REPLY（manual_default）：与现状一致，零回归；
     * - PROACTIVE → NOOP（proactive_not_enabled_m0）：M2 放开前不主动打扰；
     * - SCHEDULED → BRIEF（scheduled_brief）：周期检点产出汇总简报。
     */
    fun decide(input: DirectorInput): DirectorDecision {
        val (route, reason) = when (input.trigger) {
            DirectorTrigger.INCOMING -> DirectorRoute.REPLY to "incoming_default"
            DirectorTrigger.MANUAL -> DirectorRoute.REPLY to "manual_default"
            DirectorTrigger.PROACTIVE -> DirectorRoute.NOOP to "proactive_not_enabled_m0"
            DirectorTrigger.SCHEDULED -> DirectorRoute.BRIEF to "scheduled_brief"
        }
        val replanHint = input.failStreak >= REPLAN_FAIL_STREAK_THRESHOLD ||
            input.consecutiveRetreats >= REPLAN_RETREAT_STREAK_THRESHOLD
        return DirectorDecision(route, reason, replanHint)
    }
}
