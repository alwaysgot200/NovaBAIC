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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor.strategy

import com.verlintas.baic2.floatball.advisor.data.MISSION_ADVANCED
import com.verlintas.baic2.floatball.advisor.data.MISSION_HELD
import com.verlintas.baic2.floatball.advisor.data.MISSION_REGRESSED
import com.verlintas.baic2.floatball.advisor.data.TalkMissionEntity

/** 复盘档位（2026-10-22 方案 F1）：UI 层据此映射固定文案，不做自由文本防提示词污染。 */
enum class RecapTier { GOOD, MIXED, BAD }

/** 近期执行统计（本地即时计算，不落库、不出端）。 */
data class StrategyStats(
    /** 已判定样本数（窗口内）。 */
    val total: Int,
    /** 采纳过的条数（adoptedIndex ≠ null）。 */
    val adopted: Int,
    /** 判定成功（advanced）条数。 */
    val succeeded: Int,
    /** 判定受挫（regressed + held）条数。 */
    val failed: Int,
    /** 当前连挫（goals 表 failStreak 透传：调用方取该联系人可见目标的最大值）。 */
    val failStreak: Int,
    /** 复盘档位；样本 < [StrategyStatsCalculator.MIN_SAMPLE] 时 null（不展示不反哺）。 */
    val tier: RecapTier?,
)

/**
 * 策略效果统计纯函数（2026-10-22 方案 F1，吸收 P1 §八.5 观察项与 P2 §十四⑤ 统计反哺）。
 *
 * 口径：分母只算**已判定**任务令（advanced/regressed/held；pending/ignored/informational
 * 不入分母——informational 是保温闲聊、ignored 是用户未理，都不构成执行效果信号）；
 * held（挂降温）与 regressed 同归「受挫」。输入列表按新 → 旧排序（与 DAO 倒序一致），
 * 窗口取最近 [WINDOW] 条。调用方负责样本新鲜度与数据源（本地 DB，零网络）。
 */
object StrategyStatsCalculator {

    /** 反哺/展示所需最小样本：不足时不产出档位（防小样本噪声）。 */
    const val MIN_SAMPLE = 10

    /** 统计窗口：最近已判定条数上限。 */
    const val WINDOW = 20

    private const val GOOD_RATE_PCT = 70
    private const val BAD_RATE_PCT = 40
    private const val BAD_FAIL_STREAK = 3

    /** 已判定结果集合；held 与 regressed 均视为「未推进」。 */
    private val JUDGED = setOf(MISSION_ADVANCED, MISSION_REGRESSED, MISSION_HELD)

    fun calculate(missions: List<TalkMissionEntity>, failStreak: Int): StrategyStats {
        val judged = missions.asSequence()
            .filter { it.result in JUDGED }
            .take(WINDOW)
            .toList()
        val total = judged.size
        val adopted = judged.count { it.adoptedIndex != null }
        val succeeded = judged.count { it.result == MISSION_ADVANCED }
        return StrategyStats(
            total = total,
            adopted = adopted,
            succeeded = succeeded,
            failed = total - succeeded,
            failStreak = failStreak,
            tier = tier(total, succeeded, failStreak),
        )
    }

    private fun tier(total: Int, succeeded: Int, failStreak: Int): RecapTier? {
        if (total < MIN_SAMPLE) return null
        val rate = succeeded * 100 / total
        return when {
            failStreak >= BAD_FAIL_STREAK || rate < BAD_RATE_PCT -> RecapTier.BAD
            rate >= GOOD_RATE_PCT -> RecapTier.GOOD
            else -> RecapTier.MIXED
        }
    }
}
