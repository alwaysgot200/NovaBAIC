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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor.strategy

import com.verlintas.baic2.floatball.advisor.ScenarioPack
import com.verlintas.baic2.floatball.advisor.data.StageStateEntity

/**
 * P3 有向图阶段机内核（纯函数，零 Android 依赖）：
 * 运行时站点全集 = pack 骨架站 + roadmap 私有支线站；边集 = 主链隐式顺序 advance 边
 * （始终补全，保证线性包行为逐字等价 P2）+ roadmap 显式 edges（同 from/to/kind 时显式覆盖）。
 *
 * 裁决优先级（当前站 locked 时一律不自动移动）：
 * 1. retreat：规则通道命中退缩信号且存在匹配的 retreat 出边 → 自动回站；无边则由调用方走 P2 降温；
 * 2. advance：distinct 推进信号数 ≥ 阈值（沿用 P2 信号数-1）→ 沿显式/隐式 advance 边前进；
 * 3. skip：skip 边条件核心词在本轮信号中命中 → 自动跳站（留痕、可手动退回）；
 * 4. branch：条件命中只产出待确认提议，绝不自动移动。
 */
object StageGraph {

    /** 裁决结果：[Move] 自动执行；[Offer] 需用户轻确认（branch）。 */
    sealed interface Decision {
        val edge: EdgeDef
        val reason: String

        data class Move(override val edge: EdgeDef, override val reason: String) : Decision
        data class Offer(override val edge: EdgeDef, override val reason: String) : Decision
    }

    /** 运行时图：主链 [backbone]（pack 顺序）+ [privates]（随 roadmap 版本走的支线站）。 */
    data class Resolved(
        val backbone: List<RuntimeStage>,
        val privates: List<RuntimeStage>,
        val edges: List<EdgeDef>,
    ) {
        private val byCodeMap = (backbone + privates).associateBy { it.code }

        fun stage(code: String?): RuntimeStage? = code?.let { byCodeMap[it] }

        /** 当前站的全部出边（显式在前、隐式在后，保序）。 */
        fun outgoingFrom(code: String): List<EdgeDef> = edges.filter { it.from == code }
    }

    /**
     * LLM 产物入图前清洗：stage code 越界丢弃（P2 口径）；privateStages 丢弃空 code/label
     * 及与骨架重名者；edges 丢弃端点非法、kind 非法者，branch 边强制 needConfirm=true。
     */
    fun sanitize(pack: ScenarioPack, raw: RoadmapPayload): RoadmapPayload {
        val validStages = raw.stages.filter { s ->
            s.code.isNotBlank() && pack.stages.any { it.code == s.code }
        }
        val backboneCodes = pack.stages.map { it.code }.toSet()
        val validPrivate = raw.privateStages.filter { p ->
            p.code.isNotBlank() && p.label.isNotBlank() && p.code !in backboneCodes
        }.distinctBy { it.code }
        val reachable = backboneCodes + validPrivate.map { it.code }
        val validEdges = raw.edges
            .filter { it.from in reachable && it.to in reachable && it.kind in EDGE_KINDS }
            .map { if (it.kind == EDGE_KIND_BRANCH) it.copy(needConfirm = true) else it }
            .distinctBy { Triple(it.from, it.to, it.kind) }
        return raw.copy(
            stages = validStages,
            privateStages = validPrivate,
            edges = validEdges,
        )
    }

    /** 构建运行时图：roadmap 为 null 时得到纯骨架线性图（=P2）。 */
    fun resolve(pack: ScenarioPack, roadmap: RoadmapPayload?): Resolved {
        val stageGoals = roadmap?.stages?.associate { it.code to it }.orEmpty()
        val backbone = pack.stages.map { s ->
            val over = stageGoals[s.code]
            RuntimeStage(
                code = s.code,
                label = s.label,
                advanceSignals = s.advanceSignals,
                missionHint = s.missionHint,
                retreatSignals = emptyList(),
                pathTag = null,
                expectedDays = over?.expectedDays,
                goal = over?.goal?.trim().orEmpty(),
                private = false,
            )
        }
        val privates = roadmap?.privateStages?.map { p ->
            RuntimeStage(
                code = p.code,
                label = p.label,
                advanceSignals = p.advanceSignals,
                missionHint = p.missionHint,
                retreatSignals = p.retreatSignals,
                pathTag = p.pathTag,
                expectedDays = p.expectedDays,
                goal = p.goal.trim(),
                private = true,
            )
        }.orEmpty()

        val implicit = backbone.zipWithNext { a, b ->
            EdgeDef(from = a.code, to = b.code, kind = EDGE_KIND_ADVANCE)
        }
        // 显式边在前；同 from/to/kind 的隐式边被显式覆盖。
        val explicitKeys = roadmap?.edges
            ?.filter { it.kind == EDGE_KIND_ADVANCE }
            ?.map { it.from to it.to }
            ?.toSet()
            .orEmpty()
        val edges = (roadmap?.edges.orEmpty()) +
            implicit.filter { (it.from to it.to) !in explicitKeys }
        return Resolved(backbone = backbone, privates = privates, edges = edges)
    }

    /** P2 阈值口径：不同信号 key 数 ≥ max(1, 本站推进信号数 - 1)。 */
    fun advanceThreshold(current: RuntimeStage): Int =
        maxOf(1, current.advanceSignals.size - 1)

    /**
     * 自动移动裁决（advance/skip/retreat）；branch 走 [offers]。
     *
     * @param hitKeys 已累计+本轮合并后的 distinct 推进信号 key
     * @param signalKeys 本轮新命中的信号（规则+LLM 并集），供 skip 条件匹配
     * @param retreatHits 规则通道命中的退缩信号原词（空=无）
     */
    fun autoMove(
        state: StageStateEntity,
        graph: Resolved,
        hitKeys: Set<String>,
        signalKeys: Set<String>,
        retreatHits: List<String>,
    ): Decision.Move? {
        if (state.locked) return null
        val current = graph.stage(state.stageCode) ?: return null
        val outgoing = graph.outgoingFrom(state.stageCode)

        outgoing.firstOrNull { it.kind == EDGE_KIND_RETREAT && retreatEdgeMatches(it, retreatHits) }
            ?.let { return Decision.Move(it, "退缩信号：" + retreatHits.joinToString("、")) }

        if (hitKeys.size >= advanceThreshold(current)) {
            outgoing.firstOrNull { e ->
                e.kind == EDGE_KIND_ADVANCE &&
                    (e.condition.isBlank() || conditionMatched(e.condition, hitKeys))
            }?.let {
                val reason = if (it.condition.isBlank()) {
                    "阶段信号：" + hitKeys.joinToString("、")
                } else {
                    "条件达成：${it.condition}"
                }
                return Decision.Move(it, reason)
            }
        }

        outgoing.firstOrNull { e ->
            e.kind == EDGE_KIND_SKIP && conditionMatched(e.condition, signalKeys)
        }?.let { return Decision.Move(it, "跳站信号：${it.condition}") }

        return null
    }

    /** branch 出水中条件被本轮信号命中的提议（需 UI 轻确认；未命中不打扰）。 */
    fun offers(
        state: StageStateEntity,
        graph: Resolved,
        signalKeys: Set<String>,
    ): List<Decision.Offer> {
        if (state.locked) return emptyList()
        return graph.outgoingFrom(state.stageCode)
            .filter { it.kind == EDGE_KIND_BRANCH && conditionMatched(it.condition, signalKeys) }
            .map { Decision.Offer(it, "新分支：${it.condition}") }
    }

    /** 按边生成新状态：移动到目标站、同步 pathTag、清计数、写证据；locked 原样保留由调用方决定。 */
    fun applyEdge(
        state: StageStateEntity,
        edge: EdgeDef,
        graph: Resolved,
        now: Long,
        reason: String,
    ): StageStateEntity? {
        val target = graph.stage(edge.to) ?: return null
        return state.copy(
            stageCode = edge.to,
            enteredAt = now,
            advanceHits = "{}",
            evidence = reason,
            pathTag = edge.pathTag ?: target.pathTag,
        )
    }

    /** 该站预计停留天数（缺省兜底 [DEFAULT_EXPECTED_DAYS]，reroute 超时判定用）。 */
    fun expectedDaysOf(graph: Resolved, stageCode: String): Int =
        graph.stage(stageCode)?.expectedDays?.takeIf { it > 0 } ?: DEFAULT_EXPECTED_DAYS

    /** retreat 边：无条件边逢退缩信号即回站；有条件边需条件核心词同时命中。 */
    private fun retreatEdgeMatches(edge: EdgeDef, retreatHits: List<String>): Boolean {
        if (retreatHits.isEmpty()) return false
        return edge.condition.isBlank() || conditionMatched(edge.condition, retreatHits.toSet())
    }

    /** 条件短语的任一核心词出现在命中信号集合中即算达成。 */
    private fun conditionMatched(condition: String, keys: Set<String>): Boolean {
        if (condition.isBlank()) return false
        val cores = SignalRules.coreKeys(condition)
        return cores.any { core -> keys.any { it.contains(core) } }
    }

    /** reroute 停留超时兜底天数（roadmap 未给 expectedDays 时）。 */
    const val DEFAULT_EXPECTED_DAYS = 14
}
