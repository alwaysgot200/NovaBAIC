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

import android.util.Log
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.floatball.advisor.data.GOAL_ACTIVE
import com.verlintas.baic2.floatball.advisor.data.GoalEntity
import com.verlintas.baic2.floatball.advisor.data.MISSION_ADVANCED
import com.verlintas.baic2.floatball.advisor.data.MISSION_HELD
import com.verlintas.baic2.floatball.advisor.data.MISSION_PENDING
import com.verlintas.baic2.floatball.advisor.data.MISSION_REGRESSED
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_ADVANCE
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_RETREAT
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_SKIP
import com.verlintas.baic2.floatball.advisor.data.StageStateEntity
import com.verlintas.baic2.floatball.advisor.strategy.EDGE_KIND_RETREAT
import com.verlintas.baic2.floatball.advisor.strategy.EDGE_KIND_SKIP
import com.verlintas.baic2.floatball.advisor.strategy.RoadmapPayload
import com.verlintas.baic2.floatball.advisor.strategy.SignalRules
import com.verlintas.baic2.floatball.advisor.strategy.StageGraph
import com.verlintas.baic2.floatball.advisor.strategy.StagePolicy
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/**
 * 任务令 Judge（P2 §9.6 / P3 有向图）：两层裁决，全部失败静默，绝不影响参谋主回合。
 *
 * 1. 规则层（T6 回合收尾调用 [applyRuleRetreat]）：对方来信命中包级/支线退缩信号
 *    且该联系人有 pending 任务令 → 直接 regressed + 目标 failStreak+1（达 3 置 review）。
 *    P3：若当前站存在匹配的 retreat 出边则回站（写 history）；无边则保持 P2 降温语义，
 *    仅原地留痕。
 * 2. LLM 层（下一回合 T1 调用 [judgePending]）：仅当 pending 仍存在且满足
 *    「距上次裁决 ≥ [JUDGE_EVERY_TURNS] 回合 / 规则命中推进信号」之一时，
 *    发一次 200 token、temp=0、[JUDGE_TIMEOUT_MS] 硬超时的轻裁决；结果回写
 *    talk_missions、阶段计数/有向图移动、目标进展或倒退。LLM 失败只保留 pending。
 *
 * 保温豁免（M1，方案 D4）：informational 台账行（保温闲聊回合）永不 pending，
 * 天然不进入上述两层裁决；保温回合本身也不触发对旧 pending 的裁决（调用方在
 * smalltalk 回合跳过 [judgePending]）。
 */
@Singleton
class MissionJudgeRunner @Inject constructor(
    private val goals: GoalRepository,
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {
    /** LLM 裁决结论（输出永远不可信，缺字段走默认）。 */
    @Serializable
    data class JudgeVerdict(
        /** advanced / held / regressed，其余归一为 held。 */
        val missionResult: String = MISSION_HELD,
        /** 必须取自当前站 advanceSignals 原文，非原文不参与计数。 */
        val advanceSignals: List<String> = emptyList(),
        val evidence: String = "",
        val progressNote: String? = null,
        /** P3 §十：候选已达成的 active L1；只有高置信且规则同向才被采纳为建议。 */
        val completedL1: List<CompletedL1> = emptyList(),
    )

    /** completedL1 数组元素：[id] 必须是调用方传入的 active L1 id，否则丢弃。 */
    @Serializable
    data class CompletedL1(
        val id: Long? = null,
        val confidence: Double = 0.0,
        val evidence: String = "",
    )

    /** branch 边命中后的待确认跃迁提议（由 UI 轻确认，不自动移动）。 */
    data class BranchOffer(
        val targetCode: String,
        val targetLabel: String,
        val reason: String,
        val pathTag: String?,
    )

    /** L1 完成建议（Judge 高置信产物；仅建议，是否勾选由用户决定）。 */
    data class L1Completion(
        val goalId: Long,
        val title: String,
        val evidence: String,
    )

    /**
     * 裁决结果：[judged] 本轮是否实际做了 LLM 裁决；[branch] 非空时待用户确认分叉；
     * [l1Completions] P3 为高置信 L1 完成建议（confidence≥0.8 且规则通道同向）。
     */
    data class JudgeOutcome(
        val judged: Boolean,
        val branch: BranchOffer? = null,
        val l1Completions: List<L1Completion> = emptyList(),
    )

    /**
     * 规则倒退裁决（T6）。
     * @return true=已裁决一条 pending（调用方无需再做其他处理）。
     */
    suspend fun applyRuleRetreat(
        contactId: Long,
        pack: ScenarioPack,
        stage: StageStateEntity,
        roadmap: RoadmapPayload?,
        incomingText: String,
    ): Boolean {
        val graph = StageGraph.resolve(pack, roadmap)
        val runtime = graph.stage(stage.stageCode)
        val retreatSignals = pack.retreatSignals + (runtime?.retreatSignals.orEmpty())
        val hits = SignalRules.retreatHits(retreatSignals, incomingText)
        if (hits.isEmpty()) return false
        val mission = goals.latestPendingMission(contactId)
        // P3：有 retreat 出边且命中 → 回站；否则保持 P2「只降温不回站」，两种都写 history。
        val move = StageGraph.autoMove(
            state = stage,
            graph = graph,
            hitKeys = emptySet(),
            signalKeys = emptySet(),
            retreatHits = hits,
        )
        if (move != null) {
            val now = System.currentTimeMillis()
            val moved = StageGraph.applyEdge(stage, move.edge, graph, now, move.reason)
            if (moved != null) {
                goals.commitStage(moved, STAGE_EDGE_RETREAT, move.reason, from = stage, now = now)
                Log.d(TAG, "stage retreated by rule: ${stage.stageCode}->${moved.stageCode}, hits=$hits")
            }
        } else {
            goals.appendStageHistory(
                contactId = contactId,
                scenarioCode = pack.code,
                stageCode = stage.stageCode,
                edge = STAGE_EDGE_RETREAT,
                reason = "退缩信号（降温不回站）：" + hits.joinToString("、"),
            )
        }
        if (mission == null) return false
        goals.settleMission(mission.id, MISSION_REGRESSED)
        mission.goalId?.let { id ->
            val streak = goals.registerRegression(id)
            Log.d(TAG, "rule regression: mission=${mission.id}, goal=$id, streak=$streak, hits=$hits")
        }
        return true
    }

    /**
     * 下一回合 T1 的条件性 LLM 裁决。
     *
     * @param replyText 上一回合参谋给出的建议（判断用户是否照做/对方如何回应的依据，可空）。
     * @param activeL1 当前 active L1（≤[GoalRepository.CAP_L1]）：供 Judge 判 completedL1，
     *   不在此列表中的 id 一律丢弃。
     * @return JudgeOutcome；judged=false=未触发或失败保留 pending。
     */
    suspend fun judgePending(
        contactId: Long,
        pack: ScenarioPack,
        stage: StageStateEntity,
        roadmap: RoadmapPayload?,
        incomingText: String,
        replyText: String?,
        activeL1: List<GoalEntity> = emptyList(),
    ): JudgeOutcome {
        val mission = goals.latestPendingMission(contactId) ?: return JudgeOutcome(false)
        val graph = StageGraph.resolve(pack, roadmap)
        val runtime = graph.stage(stage.stageCode) ?: return JudgeOutcome(false)

        val ruleAdvance = SignalRules.candidates(
            signals = runtime.advanceSignals,
            incomingText = incomingText,
            replyText = replyText.orEmpty(),
        )
        val dueByTurns = countPendingSinceLastSettle(contactId) >= JUDGE_EVERY_TURNS
        if (!dueByTurns && ruleAdvance.isEmpty()) return JudgeOutcome(false)

        val verdict = requestVerdict(
            pack = pack,
            stageLabel = runtime.label,
            advanceSignalOptions = runtime.advanceSignals,
            missionText = mission.mission,
            incomingText = incomingText,
            replyText = replyText,
            activeL1 = activeL1,
        ) ?: return JudgeOutcome(false)

        // 推进信号：规则通道 + LLM 通道取并集；LLM 只认与本站信号原文一致的 key。
        val signalKeys = (ruleAdvance + verdict.advanceSignals)
            .filter { it in runtime.advanceSignals }
            .distinct()
        var updated = stage
        var branchOffer: BranchOffer? = null
        if (signalKeys.isNotEmpty()) {
            signalKeys.forEach { key ->
                updated = updated.copy(advanceHits = StagePolicy.bumpHits(updated.advanceHits, key))
            }
            val now = System.currentTimeMillis()
            val move = StageGraph.autoMove(
                state = updated,
                graph = graph,
                hitKeys = StagePolicy.parseHits(updated.advanceHits).keys,
                signalKeys = signalKeys.toSet(),
                retreatHits = emptyList(),
            )
            if (move != null) {
                val applied = StageGraph.applyEdge(updated, move.edge, graph, now, move.reason)
                if (applied != null) {
                    val edgeConst = when (move.edge.kind) {
                        EDGE_KIND_SKIP -> STAGE_EDGE_SKIP
                        EDGE_KIND_RETREAT -> STAGE_EDGE_RETREAT
                        else -> STAGE_EDGE_ADVANCE
                    }
                    goals.commitStage(applied, edgeConst, move.reason, from = stage, now = now)
                    updated = applied
                    Log.d(TAG, "stage ${move.edge.kind} by judge: ${stage.stageCode}->${applied.stageCode}")
                }
            } else {
                // 未达自动移动阈值：计数持久化；branch 条件命中则产待确认提议。
                goals.upsertStage(updated)
                StageGraph.offers(stage, graph, signalKeys.toSet()).firstOrNull()?.let { offer ->
                    val target = graph.stage(offer.edge.to)
                    branchOffer = target?.let {
                        BranchOffer(
                            targetCode = it.code,
                            targetLabel = it.label,
                            reason = offer.reason,
                            pathTag = offer.edge.pathTag ?: it.pathTag,
                        )
                    }
                }
            }
        }

        val result = when (verdict.missionResult.trim()) {
            MISSION_ADVANCED -> MISSION_ADVANCED
            MISSION_REGRESSED -> MISSION_REGRESSED
            else -> MISSION_HELD
        }
        goals.settleMission(mission.id, result)

        mission.goalId?.let { goalId ->
            when (result) {
                MISSION_ADVANCED -> goals.writeProgress(
                    id = goalId,
                    note = verdict.progressNote?.takeIf { it.isNotBlank() }
                        ?: verdict.evidence.takeIf { it.isNotBlank() },
                    failStreak = 0,
                )
                MISSION_REGRESSED -> goals.registerRegression(goalId)
                else -> Unit
            }
        }
        Log.d(TAG, "mission ${mission.id} judged: $result, signals=$signalKeys, branch=${branchOffer != null}")

        // P3 §十：L1 完成建议——必须高置信（≥0.8）且规则通道同向（本回合规则命中推进
        // 信号且裁决 advanced）；只建议、不勾选。id 不在传入 active L1 列表内一律丢弃。
        val l1Completions = if (result == MISSION_ADVANCED && ruleAdvance.isNotEmpty()) {
            val byId = activeL1.associateBy { it.id }
            verdict.completedL1.mapNotNull { item ->
                val goal = item.id?.let(byId::get) ?: return@mapNotNull null
                if (goal.status != GOAL_ACTIVE || item.confidence < L1_COMPLETE_CONFIDENCE) {
                    return@mapNotNull null
                }
                L1Completion(
                    goalId = goal.id,
                    title = goal.title,
                    evidence = item.evidence.trim(),
                )
            }
        } else {
            emptyList()
        }
        return JudgeOutcome(
            judged = true,
            branch = branchOffer,
            l1Completions = l1Completions,
        )
    }

    /** 倒序连续 pending（尚未裁决）的任务令条数，即距上次裁决经过的回合数。 */
    private suspend fun countPendingSinceLastSettle(contactId: Long): Int =
        goals.recentMissions(contactId, JUDGE_EVERY_TURNS + 1)
            .takeWhile { it.result == MISSION_PENDING }
            .size

    private suspend fun requestVerdict(
        pack: ScenarioPack,
        stageLabel: String,
        advanceSignalOptions: List<String>,
        missionText: String,
        incomingText: String,
        replyText: String?,
        activeL1: List<GoalEntity>,
    ): JudgeVerdict? {
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull() ?: return null
        val systemPrompt = """
            你是聊天任务复盘裁判。根据「对方最新来信」与「上一轮给用户的建议回复」，
            判断该轮任务令的结果。只输出一个 JSON 对象，不要解释、不要 Markdown：
            {"missionResult":"advanced|held|regressed",
             "advanceSignals":[必须从给定阶段信号原文中挑选，没有就给空数组],
             "evidence":"≤40字的客观依据",
             "progressNote":"≤60字的进展小结，没有可给空字符串",
             "completedL1":[{"id":数字,"confidence":0到1的数字,"evidence":"≤40字客观依据"}]}
            口径：advanced=对方反应符合任务预期或出现阶段推进信号；
            regressed=对方拒绝、冷处理、冲突升级等明显倒退；其余=held。
            completedL1：只有当某条近期目标的成功判据在本轮来信中被客观事实直接满足时
            才列出，confidence 表示把握（证据充分才给 ≥0.8），不满足任何一条就给空数组；
            id 必须取自下方给定的目标 id，禁止编造。
        """.trimIndent()
        val userPrompt = buildString {
            appendLine("场景包：${pack.label}；当前阶段：$stageLabel")
            appendLine("本轮任务令：$missionText")
            appendLine("阶段推进信号候选：${advanceSignalOptions.joinToString(" / ")}")
            if (activeL1.isNotEmpty()) {
                appendLine("近期目标（completedL1 只能引用这些 id）：")
                activeL1.forEach { g ->
                    val criteria = g.successCriteria?.trim()?.takeIf { it.isNotEmpty() } ?: "（未写判据）"
                    appendLine("- id=${g.id}：${g.title}；成功判据：$criteria")
                }
            }
            appendLine("对方最新来信：${incomingText.take(JUDGE_INCOMING_CHARS)}")
            replyText?.takeIf { it.isNotBlank() }?.let {
                appendLine("上一轮建议回复：${it.take(JUDGE_REPLY_CHARS)}")
            }
        }
        val raw = withTimeoutOrNull(JUDGE_TIMEOUT_MS) {
            runCatching {
                auxiliaryTasks.complete(
                    config = config,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxTokens = JUDGE_MAX_TOKENS,
                    temperature = JUDGE_TEMPERATURE,
                )
            }.getOrNull()
        } ?: return null
        return runCatching {
            strategyJson.decodeFromString(JudgeVerdict.serializer(), cleanJsonObject(raw))
        }.getOrElse {
            Log.d(TAG, "judge verdict parse failed: ${raw.take(120)}")
            null
        }
    }

    /** 去 ```json 围栏并截取首个 `{…}`（与 Tactician 同口径的宽容错）。 */
    private fun cleanJsonObject(raw: String): String {
        val noFence = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = noFence.indexOf('{')
        val end = noFence.lastIndexOf('}')
        return if (start >= 0 && end > start) noFence.substring(start, end + 1) else noFence
    }

    private companion object {
        const val TAG = "MissionJudge"
        const val JUDGE_EVERY_TURNS = 3
        const val JUDGE_MAX_TOKENS = 200
        const val JUDGE_TEMPERATURE = 0.0
        const val JUDGE_TIMEOUT_MS = 12_000L
        const val JUDGE_INCOMING_CHARS = 600
        const val JUDGE_REPLY_CHARS = 600

        /** completedL1 置信门槛（P3 §十）。 */
        const val L1_COMPLETE_CONFIDENCE = 0.8
    }
}
