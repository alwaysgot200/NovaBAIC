/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor.strategy

import com.verlintas.baic2.floatball.advisor.data.StageStateEntity

/**
 * reroute 纯规则（P3 子项 4，可 JVM 单测）：
 * 阶段映射迁移、版本指针判定、待确认识别——不碰 DB/LLM，由 GoalPlanner 落库调用。
 */
object RerouteRules {

    /**
     * 当前站迁移到新路线的目标 code：
     * 映射命中且目标站在新图存在 → 映射站；无映射/映射指向已清洗掉的非法站 → 新链首站。
     */
    fun targetCode(currentCode: String?, graph: StageGraph.Resolved, mapping: Map<String, String>): String {
        val mapped = currentCode?.let { mapping[it] }?.takeIf { graph.stage(it) != null }
        return mapped ?: graph.backbone.first().code
    }

    /**
     * 按 [mapping] 把 [state] 迁到新版本路线图：切站、钉新版本、解锁（reroute 是显式用户意图）、
     * 清信号计数、同步支线标记。目标站不存在（理论上被 [targetCode] 兜底排除）返回 null。
     */
    fun migrateState(
        state: StageStateEntity,
        graph: StageGraph.Resolved,
        mapping: Map<String, String>,
        newVersion: Int,
        now: Long,
        reason: String,
    ): StageStateEntity? {
        val newCode = targetCode(state.stageCode, graph, mapping)
        val target = graph.stage(newCode) ?: return null
        return state.copy(
            stageCode = newCode,
            enteredAt = now,
            advanceHits = "{}",
            evidence = reason,
            roadmapVersion = newVersion,
            pathTag = target.pathTag,
            locked = false,
        )
    }

    /**
     * 是否存在待确认的 reroute 草稿：最新 decomp 版本比阶段状态钉住的版本新，且最新版未确认。
     * pinnedVersion=null（v5 旧数据）永远不算待确认。
     */
    fun isPending(pinnedVersion: Int?, latestVersion: Int, latestConfirmed: Boolean): Boolean =
        pinnedVersion != null && !latestConfirmed && latestVersion > pinnedVersion
}
