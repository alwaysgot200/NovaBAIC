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

import com.verlintas.baic2.core.model.MemoryText

/**
 * 目标漂移检测（2026-10-22 方案 F2，吸收 P2 §十四到期备忘）：任务令长期跑偏于
 * 当前 active L1 判据时的**观察性提醒**——只建议、人拍板，绝不自动修改/降级目标
 *（与 L1 完成建议同口径）。
 *
 * 纯函数、零 Android 依赖；词交集复用 [MemoryText.terms]（CJK 二元组 + 停用词，
 * 与记忆召回同一套归一口径）。调用方保证只传 active L1（非激活目标不在此判）。
 */
object GoalDriftDetector {

    /** L1 起跑观察期：创建未满 3 天的新目标不判漂移。 */
    const val MIN_AGE_MS: Long = 3L * 24 * 60 * 60 * 1000

    /** 触发门槛：最近连续 ≥6 条任务令与判据零交集，且样本 ≥6 条（不足不判）。 */
    const val MIN_CONSECUTIVE = 6

    /**
     * @param l1Criteria L1 成功判据原文；空白/纯停用词（terms 为空）永不判定（不臆测）。
     * @param missionTexts 任务令文本，按新 → 旧排序（与 DAO 倒序一致）。
     * @param l1CreatedAt L1 创建时间戳。
     * @param now 当前时间戳。
     */
    fun detect(
        l1Criteria: String,
        missionTexts: List<String>,
        l1CreatedAt: Long,
        now: Long,
    ): DriftVerdict {
        val criteria = MemoryText.terms(l1Criteria).toSet()
        if (criteria.isEmpty()) return DriftVerdict(drifted = false, consecutive = 0)
        if (now - l1CreatedAt < MIN_AGE_MS) return DriftVerdict(drifted = false, consecutive = 0)
        if (missionTexts.size < MIN_CONSECUTIVE) return DriftVerdict(drifted = false, consecutive = 0)

        // 从最新往旧数连续零交集条数，命中交集即止（旧记录不再回看）。
        var consecutive = 0
        for (text in missionTexts) {
            if (MemoryText.terms(text).any { it in criteria }) break
            consecutive++
        }
        return DriftVerdict(drifted = consecutive >= MIN_CONSECUTIVE, consecutive = consecutive)
    }

    /** 检测结论：[drifted]=是否建议提醒；[consecutive]=当前连续未指向判据的回合数。 */
    data class DriftVerdict(val drifted: Boolean, val consecutive: Int)
}
