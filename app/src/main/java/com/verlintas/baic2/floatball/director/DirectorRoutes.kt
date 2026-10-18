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
 * 路由决策（方案 §5.1 规则表）。M0 放行 REPLY / BRIEF / NOOP；M1 起放行
 * SMALL_TALK（手动保温入口）；REPLAN_HINT / ACTION_HINT 为 M1/M2 落地的
 * 占位枚举，规则表放行前不会作为路由产出。GUIDE 已按 v2 方案 D6 移出路由
 * 表——推进意图在 T2 态势评估之后才产生，Director 前置拿不到（时序倒挂），
 * 它是既有意图系统行为，不占路由。
 */
enum class DirectorRoute { REPLY, SMALL_TALK, REPLAN_HINT, ACTION_HINT, BRIEF, NOOP }

/**
 * ProactivePolicy 的纯数据投影（M2-b）：路由表只吃快照，不碰 DAO/Room。
 * 由调用方（周期检点）从 proactive_policies 行映射而来；字段口径见
 * 方案 §6 表四与 §7.3 频率纪律。
 */
data class ProactivePolicySnapshot(
    val enabled: Boolean = false,
    /** 每日主动回合上限。 */
    val dailyCap: Int = 1,
    /** 两次主动的最小间隔（小时）。 */
    val minGapHours: Int = 48,
    /** 静默时段 HH:mm（24h 制），允许跨午夜（22:30~08:30）。 */
    val quietStart: String = "22:30",
    val quietEnd: String = "08:30",
)

/**
 * Director 的输入态势。规则表消费 [trigger]、[requestSmalltalk] 与失败信号
 * （failStreak / consecutiveRetreats）；M2-b 起 PROACTIVE 路由额外消费
 * [proactivePolicy]（null = 策略未接入，保持 M0 兼容不动）与频控数据
 * [proactiveCountToday]/[lastProactiveAtMs]（由调用方查 director_logs 得出，
 * 规则表保持纯函数）。
 */
data class DirectorInput(
    val trigger: DirectorTrigger,
    /** M1：分屏「来点闲聊」手动入口标记；仅对 MANUAL 生效（方案 §10/§13.2 矛盾#3）。 */
    val requestSmalltalk: Boolean = false,
    val stageCode: String? = null,
    val failStreak: Int = 0,
    val consecutiveRetreats: Int = 0,
    val stageDwellDays: Long = 0L,
    /** 对方最后一次来信时间；从未收到来信时为 null（规则表必须容忍）。M2-c 起 PROACTIVE 路由消费（方案 §7.3 incoming 优先）。 */
    val lastIncomingAtMs: Long? = null,
    val nowMs: Long = 0L,
    /** M2-b：联系人主动策略快照；null = 未配置（PROACTIVE 按未放开处理）。 */
    val proactivePolicy: ProactivePolicySnapshot? = null,
    /** M2-b：当日已主动次数（director_logs 里 trigger=proactive AND route=smalltalk 的当日计数）。 */
    val proactiveCountToday: Int = 0,
    /** M2-b：上次主动时间；从未主动过为 null（规则表必须容忍）。 */
    val lastProactiveAtMs: Long? = null,
)

/**
 * 路由决策结果。[reason] 是命中的规则键（原样落 director_logs.reason，
 * 如 "incoming_default"/"proactive_not_enabled_m0"，可回放验证）；[replanHint]
 * 是观察性重规划信号（failStreak≥3 或连续 retreat×2，即 P3 reroute 条件），
 * M0 只随决策落库、不改变任何路由与控制流；[actionHint] 是观察性行动建议
 * 信号（F7，方案 §5.6 action_hint 旁路：阶段驻留 ≥ [DirectorRoutes.ACTION_HINT_DWELL_DAYS]），
 * 同样只落台账不占路由，UI 仅在用户打开 GoalTab 时消费。
 */
data class DirectorDecision(
    val route: DirectorRoute,
    val reason: String,
    val replanHint: Boolean = false,
    val actionHint: Boolean = false,
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
     * 行动建议旁路阈值（F7，方案 §5.6 action_hint）：阶段驻留 ≥14 天置
     * actionHint。方案只定义了旁路消费口径（信号由 Director 记录、UI 仅在
     * 用户打开 GoalTab 时消费）未定数值，取 14 天推导：与 StagePolicy 停留
     * 超时的 expectedDays 缺省兜底同量级（单阶段预期驻留以周计），驻留满
     * 两周无跃迁即「行动窗口成熟」的保守信号——再短易对慢节奏关系误报，
     * 再长则错过口头聊天外的推进时机（行动卡建议期）。
     */
    const val ACTION_HINT_DWELL_DAYS = 14L

    /** 一小时的毫秒数（minGapHours 换算用）。 */
    const val HOUR_MS = 3_600_000L

    /**
     * 按触发源路由（M0 逐字规则 + M1 手动保温入口 + M2-b PROACTIVE 放开）：
     * - INCOMING → REPLY（incoming_default）：走现有 T2→T6 主链路；
     * - MANUAL → REPLY（manual_default）：与现状一致，零回归；
     * - MANUAL + requestSmalltalk → SMALL_TALK（manual_smalltalk_entry）：M1 分屏
     *   「来点闲聊」入口（方案 §13.2 矛盾#3）；字段只对 MANUAL 生效，其余触发源带上也不改路；
     * - PROACTIVE → 六分支频控（[routeProactive]，方案 §5.5/§7.3）；
     * - SCHEDULED → BRIEF（scheduled_brief）：周期检点产出汇总简报。
     */
    fun decide(input: DirectorInput): DirectorDecision {
        val (route, reason) = when (input.trigger) {
            DirectorTrigger.INCOMING -> DirectorRoute.REPLY to "incoming_default"
            DirectorTrigger.MANUAL ->
                if (input.requestSmalltalk) {
                    DirectorRoute.SMALL_TALK to "manual_smalltalk_entry"
                } else {
                    DirectorRoute.REPLY to "manual_default"
                }
            DirectorTrigger.PROACTIVE -> routeProactive(input)
            DirectorTrigger.SCHEDULED -> DirectorRoute.BRIEF to "scheduled_brief"
        }
        val replanHint = input.failStreak >= REPLAN_FAIL_STREAK_THRESHOLD ||
            input.consecutiveRetreats >= REPLAN_RETREAT_STREAK_THRESHOLD
        // F7 action_hint 旁路（方案 §5.6）：阶段驻留过长的「行动窗口成熟」信号，
        // 与 replanHint 相互独立——重规划管话术路线，行动建议管口头之外的推进。
        val actionHint = input.stageDwellDays >= ACTION_HINT_DWELL_DAYS
        return DirectorDecision(route, reason, replanHint, actionHint)
    }

    /**
     * PROACTIVE 六分支频控（方案 §5.5 状态机 + §7.3 频率纪律 + proactive_policies
     * 语义，判定顺序固定：disabled → quiet → incoming_recent → cap → gap → pass）：
     * - 策略未接入（null）→ NOOP（proactive_not_enabled_m0）：M0 测试与既有台账口径不变；
     * - enabled=false → NOOP（proactive_disabled）；
     * - 静默时段内（quietStart~quietEnd，允许跨午夜）→ NOOP（quiet_noop）；
     * - 距对方最近来信 < minGapHours → NOOP（incoming_recent_noop）：方案 §7.3
     *   「incoming 永远优先于 proactive（对方先开口绝不抢话）」——来信后对话已
     *   开启、尚在对话节奏中，主动保温多余。窗口取 minGapHours 的推导：方案未定
     *   数值，取「两次主动最小间隔」同量级口径——对方来信与我们的主动同属一次
     *   对话节奏事件，共用同一间隔；窗口过后静默成立，保温才成为正当的主动时机。
     *   位置在 quiet 之后、cap/gap 之前的推导：夜间零打扰是绝对时段纪律，不可
     *   被其他分支掩盖（其命中率是对用户的承诺）；而「对话已开启」是语义性否决，
     *   按 §7.3 原文列于频控之前，先于纯频控记账落 reason，保证回放可见该分支
     *   命中——cap/gap 每检点必查、常态命中，若置于其后本分支将长期被掩盖。
     * - 当日已主动 ≥ dailyCap → NOOP（daily_cap_reached）；
     * - 距上次主动 < minGapHours → NOOP（min_gap_not_met）；
     * - 全部通过 → SMALL_TALK（proactive_smalltalk）。
     */
    private fun routeProactive(input: DirectorInput): Pair<DirectorRoute, String> {
        val policy = input.proactivePolicy ?: return DirectorRoute.NOOP to "proactive_not_enabled_m0"
        if (!policy.enabled) return DirectorRoute.NOOP to "proactive_disabled"
        if (inQuietWindow(input.nowMs, policy.quietStart, policy.quietEnd)) {
            return DirectorRoute.NOOP to "quiet_noop"
        }
        val lastIncoming = input.lastIncomingAtMs
        if (lastIncoming != null && input.nowMs - lastIncoming < policy.minGapHours * HOUR_MS) {
            return DirectorRoute.NOOP to "incoming_recent_noop"
        }
        if (input.proactiveCountToday >= policy.dailyCap) {
            return DirectorRoute.NOOP to "daily_cap_reached"
        }
        val lastAt = input.lastProactiveAtMs
        if (lastAt != null && input.nowMs - lastAt < policy.minGapHours * HOUR_MS) {
            return DirectorRoute.NOOP to "min_gap_not_met"
        }
        return DirectorRoute.SMALL_TALK to "proactive_smalltalk"
    }

    /**
     * 静默时段判定（纯函数，[nowMs] 按本机时区取钟点）。quietStart~quietEnd 允许
     * 跨午夜（22:30~08:30 视为 22:30→次日 08:30）；任一端解析失败视为无静默
     * （fail-open：编辑层只出合法预设，此分支仅为容错不拦截保温）。
     */
    fun inQuietWindow(nowMs: Long, quietStart: String, quietEnd: String): Boolean {
        val start = parseHm(quietStart) ?: return false
        val end = parseHm(quietEnd) ?: return false
        if (start == end) return false
        val now = parseHm(java.util.Calendar.getInstance().apply { timeInMillis = nowMs })
        return if (start < end) {
            now in start..end
        } else {
            now >= start || now <= end
        }
    }

    /** "HH:mm" → 当日分钟数；非法输入返回 null。 */
    private fun parseHm(value: String): Int? {
        val parts = value.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** Calendar → 当日分钟数。 */
    private fun parseHm(calendar: java.util.Calendar): Int =
        calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
}
