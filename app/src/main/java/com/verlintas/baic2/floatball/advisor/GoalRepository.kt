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

import androidx.room.withTransaction
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.GOAL_ABANDONED
import com.verlintas.baic2.floatball.advisor.data.GOAL_ACTIVE
import com.verlintas.baic2.floatball.advisor.data.GOAL_DONE
import com.verlintas.baic2.floatball.advisor.data.GOAL_PROVISIONAL
import com.verlintas.baic2.floatball.advisor.data.GOAL_REVIEW
import com.verlintas.baic2.floatball.advisor.data.GoalDecompEntity
import com.verlintas.baic2.floatball.advisor.data.GoalEntity
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L1
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L2
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L3
import com.verlintas.baic2.floatball.advisor.data.DECOMP_ROADMAP
import com.verlintas.baic2.floatball.advisor.data.StageStateEntity
import com.verlintas.baic2.floatball.advisor.data.TalkMissionEntity
import com.verlintas.baic2.floatball.advisor.strategy.RoadmapPayload
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 某条 L1「看起来已达成」的高置信建议（P3 §十，仅进程内内存态，绝不自动勾选）。 */
data class L1CompletionHint(
    val goalId: Long,
    val title: String,
    val evidence: String,
)

/** 某档目标写操作结果：成功带新 id；失败携带 reason（UI 层映射文案，不吞语义）。 */
sealed interface GoalOpResult {
    data class Ok(val id: Long) : GoalOpResult
    data class Limit(val reason: String) : GoalOpResult
    data class Invalid(val reason: String) : GoalOpResult
}

/**
 * 三级目标 / 编译产物 / 阶段状态 / 任务令台账的唯一写入口（方案 §5.5 状态机口径）。
 *
 * 互斥上限：L3 active≤1；L2 active+provisional≤2；L1 active+provisional≤3。
 * 手建=active（亲自输入即确认，source=user）；AI 派生=provisional（source=ai，
 * 立即参与回合，等待轻确认/采纳）。超限返回 [GoalOpResult.Limit]。
 */
@Singleton
class GoalRepository @Inject constructor(
    private val db: AdvisorDatabase,
) {
    private val goalDao get() = db.goalDao()
    private val decompDao get() = db.goalDecompDao()
    private val stageDao get() = db.stageStateDao()
    private val historyDao get() = db.stageHistoryDao()
    private val missionDao get() = db.talkMissionDao()

    // ── 查询 ───────────────────────────────────────────────────────────────

    suspend fun goal(id: Long): GoalEntity? = goalDao.find(id)

    fun observeGoals(contactId: Long): Flow<List<GoalEntity>> = goalDao.observeByContact(contactId)

    /** 某档当前有效目标（provisional+active），priority/updatedAt 有序。 */
    suspend fun activeGoals(contactId: Long, horizon: Int): List<GoalEntity> =
        goalDao.listActive(contactId, horizon)

    fun observeActive(contactId: Long, horizon: Int): Flow<List<GoalEntity>> =
        goalDao.observeActive(contactId, horizon)

    // ── L1 完成建议（P3 §十，进程内内存态；不加列、绝不自动勾选）─────────────

    private val _l1CompletionHints = MutableStateFlow<List<L1CompletionHint>>(emptyList())
    val l1CompletionHints = _l1CompletionHints.asStateFlow()

    /** 用户点过「还没」的 L1：本进程生命周期内不再提示。 */
    private val dismissedL1Completions = mutableSetOf<Long>()

    /** Judge 高置信 + 规则同向时调用；已存在/已被「还没」抑制的不重复提示。 */
    fun suggestL1Completion(goalId: Long, title: String, evidence: String) {
        if (goalId in dismissedL1Completions) return
        _l1CompletionHints.update { hints ->
            if (hints.any { it.goalId == goalId }) {
                hints
            } else {
                hints + L1CompletionHint(goalId, title, evidence.take(MAX_HINT_EVIDENCE_CHARS))
            }
        }
    }

    /** 用户在建议条点「还没」：移除建议并本进程不再提示。 */
    fun dismissL1Completion(goalId: Long) {
        dismissedL1Completions += goalId
        _l1CompletionHints.update { hints -> hints.filterNot { it.goalId == goalId } }
    }

    /** 用户确认「标记完成」：走正常 setDone 流程并清掉建议。 */
    suspend fun completeSuggestedL1(goalId: Long) {
        setDone(goalId)
        _l1CompletionHints.update { hints -> hints.filterNot { it.goalId == goalId } }
    }

    // ── F2 目标漂移提醒（2026-10-22 方案）：与 dismissedL1Completions 同款进程内抑制 ──

    private val _driftDismissals = MutableStateFlow<Set<Long>>(emptySet())

    /** 已被用户「忽略」的漂移提醒 L1 集合（进程内抑制，重启后按新证据可再提示）。 */
    val driftDismissals = _driftDismissals.asStateFlow()

    /** 用户在漂移黄条点「忽略」：本进程生命周期内该 L1 不再提示；不自动改目标。 */
    fun dismissDrift(l1Id: Long) {
        _driftDismissals.update { it + l1Id }
    }

    // ── 新建 ───────────────────────────────────────────────────────────────

    /**
     * 用户手建目标：active/confirmed/source=user。
     * L1 必须挂在一个有效 L2 下（[parentId]）；超过互斥上限返回 Limit。
     */
    suspend fun createUserGoal(
        contactId: Long,
        horizon: Int,
        title: String,
        successCriteria: String? = null,
        deadlineAt: Long? = null,
        scenarioCode: String? = null,
        parentId: Long? = null,
        priority: Int = 5,
    ): GoalOpResult {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return GoalOpResult.Invalid(REASON_TITLE_EMPTY)
        if (horizon == HORIZON_L1 && parentId == null) return GoalOpResult.Invalid(REASON_L1_NEEDS_L2)
        if (goalDao.countActive(contactId, horizon) >= capOf(horizon)) return GoalOpResult.Limit(REASON_LIMIT)

        val now = System.currentTimeMillis()
        val id = goalDao.upsert(
            GoalEntity(
                contactId = contactId,
                horizon = horizon,
                parentId = parentId,
                scenarioCode = scenarioCode,
                title = cleanTitle,
                successCriteria = successCriteria?.trim()?.takeIf { it.isNotEmpty() },
                deadlineAt = deadlineAt,
                status = GOAL_ACTIVE,
                priority = priority.coerceIn(1, 9),
                source = SOURCE_USER,
                confirmed = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return GoalOpResult.Ok(id)
    }

    /**
     * 慢车道派生 provisional 目标（source=ai）。守同样的互斥上限；
     * L1 必须挂 L2。deadline/priority 由编译器候选给出。
     */
    suspend fun createProvisionalGoal(
        contactId: Long,
        horizon: Int,
        title: String,
        parentId: Long?,
        scenarioCode: String?,
        successCriteria: String?,
        deadlineAt: Long?,
        priority: Int,
    ): GoalOpResult {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return GoalOpResult.Invalid(REASON_TITLE_EMPTY)
        if (horizon == HORIZON_L1 && parentId == null) return GoalOpResult.Invalid(REASON_L1_NEEDS_L2)
        if (goalDao.countActive(contactId, horizon) >= capOf(horizon)) return GoalOpResult.Limit(REASON_LIMIT)

        val now = System.currentTimeMillis()
        val id = goalDao.upsert(
            GoalEntity(
                contactId = contactId,
                horizon = horizon,
                parentId = parentId,
                scenarioCode = scenarioCode,
                title = cleanTitle,
                successCriteria = successCriteria?.trim()?.takeIf { it.isNotEmpty() },
                deadlineAt = deadlineAt,
                status = GOAL_PROVISIONAL,
                priority = priority.coerceIn(1, 9),
                source = SOURCE_AI,
                confirmed = false,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return GoalOpResult.Ok(id)
    }

    // ── 状态流转（用户操作） ────────────────────────────────────────────────

    suspend fun setDone(id: Long) = goalDao.setStatus(id, GOAL_DONE, System.currentTimeMillis())

    suspend fun setAbandoned(id: Long) = goalDao.setStatus(id, GOAL_ABANDONED, System.currentTimeMillis())

    /** 用户从 review/归档中重新激活（守上限）。 */
    suspend fun reactivate(id: Long): GoalOpResult {
        val g = goalDao.find(id) ?: return GoalOpResult.Invalid(REASON_NOT_FOUND)
        if (goalDao.countActive(g.contactId, g.horizon) >= capOf(g.horizon)) return GoalOpResult.Limit(REASON_LIMIT)
        goalDao.setStatus(id, GOAL_ACTIVE, System.currentTimeMillis())
        return GoalOpResult.Ok(id)
    }

    /** 用户编辑目标后保存（标题/判据/deadline/priority/scenario）。 */
    suspend fun updateUserGoal(
        id: Long,
        title: String,
        successCriteria: String?,
        deadlineAt: Long?,
        priority: Int,
        scenarioCode: String?,
    ): GoalOpResult {
        val existing = goalDao.find(id) ?: return GoalOpResult.Invalid(REASON_NOT_FOUND)
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return GoalOpResult.Invalid(REASON_TITLE_EMPTY)
        val now = System.currentTimeMillis()
        goalDao.upsert(
            existing.copy(
                title = cleanTitle,
                successCriteria = successCriteria?.trim()?.takeIf { it.isNotEmpty() },
                deadlineAt = deadlineAt,
                priority = priority.coerceIn(1, 9),
                scenarioCode = scenarioCode,
                updatedAt = now,
            ),
        )
        return GoalOpResult.Ok(id)
    }

    // ── provisional L2 分屏轻确认（§9.5） ──────────────────────────────────

    /**
     * 取本回合应轻确认的 provisional L2（priority 最高）：
     * 先把 promptCount≥2（已露面两次无操作）者置 abandoned（本次回退基线）；
     * 剩余最高优先级一条计数 +1 并返回（展示即计数，§9.5）。
     */
    suspend fun nextProvisionalL2Prompt(contactId: Long): GoalEntity? = db.withTransaction {
        val now = System.currentTimeMillis()
        val provisional = goalDao.listActive(contactId, HORIZON_L2)
            .filter { it.status == GOAL_PROVISIONAL }
        provisional.filter { it.promptCount >= PROVISIONAL_L2_SILENCE_LIMIT }
            .forEach { goalDao.setStatus(it.id, GOAL_ABANDONED, now) }
        val candidate = provisional
            .filter { it.promptCount < PROVISIONAL_L2_SILENCE_LIMIT }
            .maxByOrNull { it.priority }
            ?: return@withTransaction null
        goalDao.incrementPromptCount(candidate.id, now)
        candidate
    }

    /** ✓确认：active + confirmed + 计数清零。 */
    suspend fun confirmProvisionalL2(id: Long) {
        goalDao.setConfirmed(id, confirmed = true, status = GOAL_ACTIVE, now = System.currentTimeMillis())
    }

    /** 本次跳过：计数 +1；是否归档由下一回合 T1 统一裁决。 */
    suspend fun skipProvisionalL2(id: Long) {
        goalDao.incrementPromptCount(id, System.currentTimeMillis())
    }

    // ── provisional L1（只在目标页，不弹分屏） ──────────────────────────────

    /** ✓采纳 AI 建议 L1：守 ≤3 上限后 active+confirmed。 */
    suspend fun adoptProvisionalL1(id: Long): GoalOpResult {
        val g = goalDao.find(id) ?: return GoalOpResult.Invalid(REASON_NOT_FOUND)
        if (goalDao.countActiveExcept(g.contactId, HORIZON_L1, id) >= CAP_L1) return GoalOpResult.Limit(REASON_LIMIT)
        goalDao.setConfirmed(id, confirmed = true, status = GOAL_ACTIVE, now = System.currentTimeMillis())
        return GoalOpResult.Ok(id)
    }

    /** T1 取数惰性清理：7 天未确认的 L1 建议置 abandoned，返回清理条数。 */
    suspend fun archiveStaleL1(contactId: Long, now: Long = System.currentTimeMillis()): Int =
        goalDao.archiveStaleProvisionalL1(contactId, now - L1_SUGGEST_TTL_MS, now)

    // ── Judge 写回（§9.6） ─────────────────────────────────────────────────

    /** 更新进展备注（≤200 字截断）与连续成功计数（失败连击清零）。 */
    suspend fun writeProgress(id: Long, note: String?, failStreak: Int = 0) {
        val clipped = note?.take(PROGRESS_NOTE_MAX)?.trim()?.takeIf { it.isNotEmpty() }
        goalDao.setProgress(id, clipped, failStreak, System.currentTimeMillis())
    }

    /** 倒退计数 +1；达到阈值置 review（黄条，不自动改目标状态的其他口径由 UI 处理）。 */
    suspend fun registerRegression(id: Long): Int {
        val g = goalDao.find(id) ?: return 0
        val streak = g.failStreak + 1
        val now = System.currentTimeMillis()
        if (streak >= FAIL_STREAK_REVIEW) {
            goalDao.setFailStreak(id, streak, GOAL_REVIEW, now)
        } else {
            goalDao.setProgress(id, g.progressNote, streak, now)
        }
        return streak
    }

    // ── 编译产物（多版本审计） ──────────────────────────────────────────────

    /**
     * 落一版编译产物：version 自动 +1。[supersede]=true（默认）时把同 goal/kind 的旧现行版本
     * 标记 supersededBy；reroute 草稿传 false——新路线未确认前旧版必须保持激活。
     * 返回事务内新行 id（编译器拿它触发后续链路）。
     */
    suspend fun putDecomp(
        goalId: Long,
        kind: String,
        payloadJson: String,
        confirmed: Boolean = false,
        supersede: Boolean = true,
    ): Long =
        db.withTransaction {
            val nextVersion = (decompDao.maxVersion(goalId, kind) ?: 0) + 1
            val newId = decompDao.upsert(
                GoalDecompEntity(
                    goalId = goalId,
                    version = nextVersion,
                    kind = kind,
                    payloadJson = payloadJson,
                    confirmed = confirmed,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            if (supersede) {
                decompDao.latest(goalId, kind)
                    ?.takeIf { it.id != newId }
                    ?.let { decompDao.supersede(it.id, newId) }
            }
            newId
        }

    /** P3 reroute 采用：新 decomp 置 confirmed 并顶掉其余现行版本（旧版留存可回溯）。 */
    suspend fun activateDecomp(goalId: Long, kind: String, newId: Long) = db.withTransaction {
        decompDao.setConfirmed(newId, true)
        decompDao.supersedeOthers(goalId, kind, newId)
    }

    /** P3 reroute 拒绝：新版标记被自己取代（从 latest 消失、数据留存），旧版继续激活。 */
    suspend fun discardDecomp(newId: Long) {
        decompDao.supersede(newId, newId)
    }

    suspend fun latestDecomp(goalId: Long, kind: String) = decompDao.latest(goalId, kind)

    fun observeLatestDecomp(goalId: Long, kind: String) = decompDao.observeLatest(goalId, kind)

    /**
     * 当前阶段应遵循的路线图（P3 激活版本口径）：stage_states.roadmapVersion 非空时
     * 精确取该版本（reroute 未确认时旧版仍激活）；否则取 L2 的最新未取代版本（兼容 v5 数据）。
     * 解析失败返回 null（调用方退化为资产线性链）。
     */
    suspend fun activeRoadmap(contactId: Long, scenarioCode: String, l2GoalId: Long?): RoadmapPayload? {
        val pinnedVersion = stage(contactId, scenarioCode)?.roadmapVersion
        val decomp = when {
            pinnedVersion != null && l2GoalId != null ->
                decompDao.byVersion(l2GoalId, DECOMP_ROADMAP, pinnedVersion)
            l2GoalId != null -> decompDao.latest(l2GoalId, DECOMP_ROADMAP)
            else -> null
        } ?: return null
        return runCatching {
            strategyJson.decodeFromString(RoadmapPayload.serializer(), decomp.payloadJson)
        }.getOrNull()
    }

    // ── 阶段状态 ───────────────────────────────────────────────────────────

    suspend fun stage(contactId: Long, scenarioCode: String): StageStateEntity? =
        stageDao.get(contactId, scenarioCode)

    suspend fun upsertStage(state: StageStateEntity) = stageDao.upsert(state)

    fun observeStage(contactId: Long, scenarioCode: String): Flow<StageStateEntity?> =
        stageDao.observe(contactId, scenarioCode)

    /**
     * 阶段移动唯一入口（v6 有向图）：事务内 upsert 新状态 + 追加 stage_history + 滚动 trim。
     * [from] 为移动前状态（首站 init 时传 null）；[edge] 取 STAGE_EDGE_* 常量。
     */
    suspend fun commitStage(
        state: StageStateEntity,
        edge: String,
        reason: String?,
        from: StageStateEntity?,
        now: Long = System.currentTimeMillis(),
    ) = db.withTransaction {
        stageDao.upsert(state)
        historyDao.insert(
            com.verlintas.baic2.floatball.advisor.data.StageHistoryEntity(
                contactId = state.contactId,
                scenarioCode = state.scenarioCode,
                fromCode = from?.stageCode?.takeIf { it != state.stageCode },
                toCode = state.stageCode,
                edge = edge,
                reason = reason,
                at = now,
            ),
        )
        historyDao.trim(state.contactId, state.scenarioCode, STAGE_HISTORY_KEEP)
    }

    /**
     * 仅追加时间线不移动阶段：retreat 信号命中但 roadmap 无回站边时（P2 降温语义），
     * 以及其他"原地事件"留痕。fromCode/toCode 同为当前站。
     */
    suspend fun appendStageHistory(
        contactId: Long,
        scenarioCode: String,
        stageCode: String,
        edge: String,
        reason: String?,
        now: Long = System.currentTimeMillis(),
    ) = db.withTransaction {
        historyDao.insert(
            com.verlintas.baic2.floatball.advisor.data.StageHistoryEntity(
                contactId = contactId,
                scenarioCode = scenarioCode,
                fromCode = null,
                toCode = stageCode,
                edge = edge,
                reason = reason,
                at = now,
            ),
        )
        historyDao.trim(contactId, scenarioCode, STAGE_HISTORY_KEEP)
    }

    suspend fun stageHistory(contactId: Long, scenarioCode: String, limit: Int = STAGE_HISTORY_KEEP) =
        historyDao.recent(contactId, scenarioCode, limit)

    fun observeStageHistory(contactId: Long, scenarioCode: String, limit: Int = STAGE_HISTORY_KEEP) =
        historyDao.observeRecent(contactId, scenarioCode, limit)

    suspend fun setStageLocked(contactId: Long, scenarioCode: String, locked: Boolean) =
        stageDao.setLocked(contactId, scenarioCode, locked)

    /** 当前站连续同 edge 事件数（reroute 触发：连续 retreat×2）。 */
    suspend fun consecutiveEdgeAtStage(
        contactId: Long,
        scenarioCode: String,
        stageCode: String,
        edge: String,
    ): Int = historyDao.consecutiveAtStage(contactId, scenarioCode, stageCode, edge)

    // ── 任务令台账 ─────────────────────────────────────────────────────────

    /** 落一条本轮 mission，随后按联系人/会话各 trim 到最近 [MISSION_KEEP] 条。 */
    suspend fun insertMission(mission: TalkMissionEntity): Long = db.withTransaction {
        val id = missionDao.insert(mission)
        if (mission.contactId != null) {
            missionDao.trimContact(mission.contactId, MISSION_KEEP)
        } else {
            missionDao.trimConversation(mission.conversationId, MISSION_KEEP)
        }
        id
    }

    suspend fun latestPendingMission(contactId: Long?): TalkMissionEntity? =
        when {
            contactId != null -> missionDao.latestPendingForContact(contactId)
            else -> null
        }

    suspend fun latestPendingMissionForConversation(conversationId: Long) =
        missionDao.latestPendingForConversation(conversationId)

    suspend fun settleMission(id: Long, result: String) = missionDao.settle(id, result)

    /** P3 §九：行内采纳建议编号（1 起）。 */
    suspend fun adoptMission(id: Long, index: Int) = missionDao.adopt(id, index)

    /** P3 §九：分屏气泡观察最近任务令（pending 态/已采纳编号驱动行内按钮）。 */
    fun observeRecentMissionsForConversation(
        conversationId: Long,
        limit: Int = MISSION_KEEP,
    ): Flow<List<TalkMissionEntity>> =
        missionDao.observeRecentForConversation(conversationId, limit)

    /** 倒序最近若干条任务令（Judge 节流「距上次裁决几回合」判定用）。 */
    suspend fun recentMissions(contactId: Long, limit: Int): List<TalkMissionEntity> =
        missionDao.recentForContact(contactId, limit)

    fun observeRecentMissions(contactId: Long, limit: Int = MISSION_LEDGER_LIMIT): Flow<List<TalkMissionEntity>> =
        missionDao.observeRecentForContact(contactId, limit)

    private fun capOf(horizon: Int): Int = when (horizon) {
        HORIZON_L3 -> CAP_L3
        HORIZON_L2 -> CAP_L2
        HORIZON_L1 -> CAP_L1
        else -> CAP_L1
    }

    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_AI = "ai"

        const val CAP_L3 = 1
        const val CAP_L2 = 2
        const val CAP_L1 = 3

        /** stage_history 每联系人×场景滚动保留条数。 */
        const val STAGE_HISTORY_KEEP = 50

        /** provisional L2 沉默露面上限：第 3 次即归档（promptCount 已达 2）。 */
        const val PROVISIONAL_L2_SILENCE_LIMIT = 2

        /** provisional L1 建议存活期：7 天未采纳惰性归档。 */
        const val L1_SUGGEST_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** failStreak 达 3 置 review。 */
        const val FAIL_STREAK_REVIEW = 3

        /** progressNote 落库前截断长度。 */
        const val PROGRESS_NOTE_MAX = 200

        /** L1 完成建议依据在内存态中的截断长度。 */
        const val MAX_HINT_EVIDENCE_CHARS = 60

        /** 每联系人/会话任务令保留条数。 */
        const val MISSION_KEEP = 60

        /** 目标页台账展示条数。 */
        const val MISSION_LEDGER_LIMIT = 20

        // reason 字符串（UI 层映射文案）
        const val REASON_LIMIT = "limit_reached"
        const val REASON_TITLE_EMPTY = "title_empty"
        const val REASON_L1_NEEDS_L2 = "l1_needs_l2"
        const val REASON_NOT_FOUND = "not_found"
    }
}
