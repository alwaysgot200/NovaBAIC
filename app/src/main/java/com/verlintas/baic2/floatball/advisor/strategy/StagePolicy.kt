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

import com.verlintas.baic2.floatball.advisor.ScenarioPack
import com.verlintas.baic2.floatball.advisor.data.StageStateEntity
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * P2 线性阶段机：首站初始化、信号计数、阈值跃迁、手动改站（§7.3）。
 *
 * P2 只沿 pack.stages 顺序前进，不做分叉/回退/跳级（归 P3）；retreat 不回站，
 * 只挂降温指令并累计 failStreak。maintain/defend 终点站为停留态，到达后不再跃迁。
 */
object StagePolicy {

    /** 手动改站写入 stage_states.evidence 的固定证据词。 */
    const val MANUAL_EVIDENCE = "手动设置"

    /** 跃迁判定结果。 */
    data class Advance(
        val nextCode: String,
        val hitKeys: Set<String>,
        val evidence: String,
    )

    /** 初始化联系人在某场景包下的首站状态（advanceHits 空计数）。 */
    fun initial(contactId: Long, pack: ScenarioPack, now: Long): StageStateEntity? {
        val first = pack.stages.firstOrNull() ?: return null
        return StageStateEntity(
            contactId = contactId,
            scenarioCode = pack.code,
            stageCode = first.code,
            enteredAt = now,
            advanceHits = "{}",
            evidence = null,
        )
    }

    /** 某信号计数 +1（同 key 重复命中累加，但跃迁按 distinct key 数判定）。 */
    fun bumpHits(hitsJson: String, signalKey: String): String {
        val current = parseHits(hitsJson)
        val next = current + (signalKey to ((current[signalKey] ?: 0) + 1))
        return encodeHits(next)
    }

    /**
     * 是否应顺序跃迁：**不同 signalKey** 命中数 ≥ `max(1, 阶段信号数-1)` 时返回下一站。
     * 未知站点/终点站/计数不足均返回 null（末站停留）。
     */
    fun maybeAdvance(state: StageStateEntity, pack: ScenarioPack): Advance? {
        val codes = pack.stages.map { it.code }
        val idx = codes.indexOf(state.stageCode)
        if (idx < 0 || idx == codes.lastIndex) return null
        val stageDef = pack.stages[idx]
        val hitKeys = parseHits(state.advanceHits).keys
        val threshold = maxOf(1, stageDef.advanceSignals.size - 1)
        if (hitKeys.size < threshold) return null
        return Advance(
            nextCode = codes[idx + 1],
            hitKeys = hitKeys,
            evidence = "阶段信号：" + hitKeys.joinToString("、"),
        )
    }

    /**
     * 手动改站（always allowed）：切到 [toCode]、清空旧站计数（旧站 key 不得污染新站阈值）、
     * evidence 固定「手动设置」。toCode 不在包内时原样返回（调用方应先校验菜单）。
     */
    fun manualMove(
        state: StageStateEntity,
        pack: ScenarioPack,
        toCode: String,
        now: Long,
    ): StageStateEntity {
        if (pack.stages.none { it.code == toCode }) return state
        return state.copy(
            stageCode = toCode,
            enteredAt = now,
            advanceHits = "{}",
            evidence = MANUAL_EVIDENCE,
        )
    }

    /** 生成跃迁至下一站的新状态（计数清空、进入时间与证据更新）。 */
    fun applyAdvance(state: StageStateEntity, advance: Advance, now: Long): StageStateEntity =
        state.copy(
            stageCode = advance.nextCode,
            enteredAt = now,
            advanceHits = "{}",
            evidence = advance.evidence,
        )

    internal fun parseHits(hitsJson: String): Map<String, Int> =
        runCatching {
            strategyJson.decodeFromString(
                MapSerializer(String.serializer(), Int.serializer()),
                hitsJson,
            )
        }.getOrDefault(emptyMap())

    private fun encodeHits(hits: Map<String, Int>): String =
        strategyJson.encodeToString(
            MapSerializer(String.serializer(), Int.serializer()),
            hits,
        )
}
