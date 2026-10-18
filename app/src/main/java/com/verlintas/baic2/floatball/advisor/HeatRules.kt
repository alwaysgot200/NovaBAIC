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

package com.verlintas.baic2.floatball.advisor

/**
 * 反馈热度 heatScore（0~100，v10 方案 §4.3，V-C 落地）的信号增量纯函数。
 *
 * 只消费**可得信号**，不可得就跳过、绝不臆测（方案 §4.3 红线）：
 * - 字数档：对方段文本长度（长消息 = 高投入）；
 * - 反问：文本含问号（对方在推进对话）；
 * - 时延：距上一次感知到对方来信的间隔（秒回热、久无音讯冷）。
 *
 * 两路调用方：连续模式差分（ScreenAdvisor 来信指纹变化块，gap=两帧感知间隔）
 * 与通知链（submitNotificationTurn，gap=距 lastIncomingAt）；增量与夹逼全在
 * 本对象，JVM 可直接单测。
 */
object HeatRules {

    /** 中性起点：迁移历史行与首记前的缺省分（contact_profiles.heatScore 默认 50 同口径）。 */
    const val NEUTRAL = 50

    /** 单次感知的增量上限/下限（防单帧噪声把热度拉穿）。 */
    const val MAX_DELTA = 5
    const val MIN_DELTA = -4

    /** 一条感知信号：[text]=对方段文本（可空=无文本信号）；[gapMs]=距上次来信感知间隔。 */
    data class Signal(val text: String?, val gapMs: Long? = null)

    /**
     * 计算本次感知增量；文本空白且 gap 不可得（含负 gap 时钟回拨，F6）时返回
     * null（调用方跳过不记分）。时延分档：&lt;5min +2（秒回）；&lt;2h +1；
     * &lt;24h 0（日常节奏）；≥24h -2（久疏）；负 gap 一律按不可得跳过。
     */
    fun delta(signal: Signal): Int? {
        var total = 0
        var any = false
        val text = signal.text?.trim().orEmpty()
        if (text.isNotEmpty()) {
            any = true
            total += when {
                text.length < 15 -> -2
                text.length < 60 -> 1
                else -> 3
            }
            if (text.contains('？') || text.contains('?')) total += 2
        }
        // F6：负 gap 只可能来自时钟回拨——按信号不可得跳过，绝不落进「秒回」档。
        signal.gapMs?.takeIf { it >= 0 }?.let { gap ->
            any = true
            total += when {
                gap < 5L * 60 * 1000 -> 2
                gap < 2L * 60 * 60 * 1000 -> 1
                gap < 24L * 60 * 60 * 1000 -> 0
                else -> -2
            }
        }
        if (!any) return null
        return total.coerceIn(MIN_DELTA, MAX_DELTA)
    }

    /** 增量落分：current null 按 [NEUTRAL] 起步，结果 0~100 夹逼。 */
    fun clamp(current: Int?, delta: Int): Int =
        ((current ?: NEUTRAL) + delta).coerceIn(0, 100)
}
