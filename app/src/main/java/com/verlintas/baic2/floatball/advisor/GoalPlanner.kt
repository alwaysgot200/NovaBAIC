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
import com.verlintas.baic2.floatball.advisor.data.DECOMP_OUTCOMES
import com.verlintas.baic2.floatball.advisor.data.DECOMP_ROADMAP
import com.verlintas.baic2.floatball.advisor.data.GOAL_ACTIVE
import com.verlintas.baic2.floatball.advisor.data.GoalEntity
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L1
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L2
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L3
import com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_REROUTE
import com.verlintas.baic2.floatball.advisor.strategy.OutcomesPayload
import com.verlintas.baic2.floatball.advisor.strategy.RerouteRules
import com.verlintas.baic2.floatball.advisor.strategy.RoadmapPayload
import com.verlintas.baic2.floatball.advisor.strategy.StageGraph
import com.verlintas.baic2.floatball.advisor.strategy.StagePolicy
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 慢车道目标编译器（§5）：
 * L3 → STRATEGIST（outcomes：L2 候选 + 修炼项，900/0.4）；
 * L2 → PLANNER（roadmap 路线图 + 当前站 L1，700/0.3）。
 *
 * 全部失败只 Log.d 并返回 false，绝不影响参谋回合（降级矩阵 §十）。
 * 幂等：同 kind 已有未确认 draft decomp 且非 [force] 时跳过。
 *
 * @return 本次是否产生了应触发策略卡重编的变化（L2 roadmap 成功）。
 */
@Singleton
class GoalPlanner @Inject constructor(
    private val goals: GoalRepository,
    private val contacts: ContactRepository,
    private val schemas: PersonaSchemas,
    private val styleAssets: StyleAssets,
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {
    suspend fun compileGoal(goalId: Long, force: Boolean = false): Boolean {
        val goal = goals.goal(goalId) ?: return false
        if (goal.status !in ACTIVE_STATUSES) return false
        return when (goal.horizon) {
            HORIZON_L3 -> runStrategist(goal, force)
            HORIZON_L2 -> runPlanner(goal, force)
            else -> false // L1 无编译作业
        }
    }

    /** 供调度器在编译完成后定位联系人维度（编策略卡用）；目标已删返回 0。 */
    suspend fun contactIdOf(goalId: Long): Long = goals.goal(goalId)?.contactId ?: 0L

    // ── PLANNER：reroute 路线重规划（P3 子项 4） ───────────────────────────

    /** 一次 reroute 产出：新路线草稿（未确认、未顶掉旧版）+ 当前站迁移目标。 */
    data class ReroutePlan(
        val goalId: Long,
        val decompId: Long,
        val version: Int,
        val targetCode: String,
        val targetLabel: String,
    )

    /**
     * 生成新路线草稿。绝不静默迁移：本方法只产 decomp（confirmed=0、旧版仍激活），
     * 迁移发生在 [acceptReroute]。已有待确认草稿时返回 null（去重）。
     */
    suspend fun runReroute(goalId: Long): ReroutePlan? {
        val goal = goals.goal(goalId) ?: return null
        if (goal.horizon != HORIZON_L2 || goal.status !in ACTIVE_STATUSES) return null
        val pack = styleAssets.scenarioPacks().byCode(goal.scenarioCode) ?: return null
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull() ?: return null
        val stage = goals.stage(goal.contactId, pack.code) ?: return null
        goals.latestDecomp(goalId, DECOMP_ROADMAP)?.let { latest ->
            if (RerouteRules.isPending(stage.roadmapVersion, latest.version, latest.confirmed)) return null
        }

        val oldRoadmap = goals.activeRoadmap(goal.contactId, pack.code, goalId)
        val recentHistory = goals.stageHistory(goal.contactId, pack.code, REROUTE_HISTORY_TAKE)
        val raw = runCatching {
            auxiliaryTasks.complete(
                config = config,
                systemPrompt = rerouteSystemPrompt(pack.pathKind),
                userPrompt = buildString {
                    appendLine("中期目标：${goal.title}")
                    goal.successCriteria?.takeIf { it.isNotBlank() }?.let { appendLine("成功判据：$it") }
                    appendLine("连续受挫计数：${goal.failStreak}")
                    goal.progressNote?.takeIf { it.isNotBlank() }?.let { appendLine("最近进展备注：$it") }
                    appendLine("当前所处站：${stage.stageCode}（依据：${stage.evidence.orEmpty()}）")
                    if (recentHistory.isNotEmpty()) {
                        appendLine("最近阶段变迁（新→旧）：")
                        recentHistory.forEach { h ->
                            appendLine("- ${h.fromCode.orEmpty()}→${h.toCode}（${h.edge}）${h.reason.orEmpty()}")
                        }
                    }
                    appendLine("场景阶段骨架（stages[].code 只能取自下表，或 privateStages 新造）：")
                    pack.stages.forEach { s ->
                        appendLine("- ${s.code} ${s.label}：${s.missionHint}")
                    }
                    oldRoadmap?.let {
                        appendLine("旧路线（需要重排的对象，不要照搬失效部分）：")
                        it.stages.forEach { s -> appendLine("- ${s.code}：${s.goal}") }
                    }
                },
                maxTokens = TOKENS_PLANNER,
                temperature = TEMP_REROUTE,
            )
        }.getOrNull() ?: return null

        val parsed = parseObject(raw, RoadmapPayload.serializer()) ?: return null
        val payload = StageGraph.sanitize(pack, parsed)
        val newId = goals.putDecomp(
            goalId = goalId,
            kind = DECOMP_ROADMAP,
            payloadJson = strategyJson.encodeToString(RoadmapPayload.serializer(), payload),
            confirmed = false,
            supersede = false,
        )
        val version = goals.latestDecomp(goalId, DECOMP_ROADMAP)?.version ?: return null
        val graph = StageGraph.resolve(pack, payload)
        val targetCode = RerouteRules.targetCode(stage.stageCode, graph, payload.stageMapping)
        val targetLabel = graph.stage(targetCode)?.label ?: targetCode
        return ReroutePlan(
            goalId = goalId,
            decompId = newId,
            version = version,
            targetCode = targetCode,
            targetLabel = targetLabel,
        )
    }

    /** 用户采用新路线：按 stageMapping 迁移阶段状态、钉新版本、解锁、写 history(reroute)。 */
    suspend fun acceptReroute(goalId: Long, decompId: Long): Boolean {
        val goal = goals.goal(goalId) ?: return false
        val pack = styleAssets.scenarioPacks().byCode(goal.scenarioCode) ?: return false
        val decomp = goals.latestDecomp(goalId, DECOMP_ROADMAP)?.takeIf { it.id == decompId } ?: return false
        val payload = runCatching {
            strategyJson.decodeFromString(RoadmapPayload.serializer(), decomp.payloadJson)
        }.getOrNull() ?: return false
        val clean = StageGraph.sanitize(pack, payload)
        val stage = goals.stage(goal.contactId, pack.code) ?: return false
        val graph = StageGraph.resolve(pack, clean)
        val now = System.currentTimeMillis()
        val moved = RerouteRules.migrateState(
            state = stage,
            graph = graph,
            mapping = clean.stageMapping,
            newVersion = decomp.version,
            now = now,
            reason = REROUTE_REASON,
        ) ?: return false
        goals.commitStage(moved, STAGE_EDGE_REROUTE, REROUTE_REASON, from = stage, now = now)
        goals.activateDecomp(goalId, DECOMP_ROADMAP, decompId)
        return true
    }

    /** 用户拒绝：草稿留存但不激活，状态机继续跑旧版。 */
    suspend fun rejectReroute(decompId: Long) = goals.discardDecomp(decompId)

    private fun rerouteSystemPrompt(pathKind: String): String = """
        你是用户的私人关系作战参谋。原路线连续受挫，需要为同一个中期目标重排一条更现实的路线。
        规则：
        1. 输出与普通路线图相同的 schema，并额外给 stageMapping：旧 code → 新 code 的对应关系；
           已废弃、没有对应站的旧 code 直接缺键（系统会把无映射的当前站落到新链首站）；
        2. stages 沿骨架先后顺序，code 取自给定骨架；确有必要的新打法放 privateStages 并在
           edges 里以 branch 边（必须 needConfirm:true）接入，edges 默认给空数组；
        3. 正视受挫原因：目标站可重排、expectedDays 重估，defend 类允许给 retreat 回撤边；
           不得换目标、不得输出与成功判据无关的内容；
        4. 只输出 JSON，不要解释、不要 Markdown：
           {"stages":[{"code":"","goal":"","exitSignals":[""],"expectedDays":14}],"practices":[],
            "l1":[],
            "edges":[{"from":"","to":"","kind":"retreat|branch|skip|advance","condition":"","needConfirm":false}],
            "privateStages":[],
            "stageMapping":{"旧code":"新code"}}
        路径「$pathKind」。
    """.trimIndent()

    // ── STRATEGIST：L3 → outcomes ──────────────────────────────────────────

    private suspend fun runStrategist(goal: GoalEntity, force: Boolean): Boolean {
        if (!force && goals.latestDecomp(goal.id, DECOMP_OUTCOMES)
                ?.let { !it.confirmed } == true
        ) {
            return false
        }
        val pack = styleAssets.scenarioPacks().byCode(goal.scenarioCode)
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull() ?: return false

        val confirmedPersona = confirmedPersonaLines(goal.contactId)
        val aspiration = aspirationLabels(goal.contactId)
        val history = relationshipDigest(goal.contactId)
        val raw = runCatching {
            auxiliaryTasks.complete(
                config = config,
                systemPrompt = strategistSystemPrompt(pack?.pathKind, pack?.group),
                userPrompt = buildString {
                    append("远期愿景：").appendLine(goal.title)
                    if (confirmedPersona.isNotEmpty()) {
                        appendLine("双方已确认画像：")
                        confirmedPersona.forEach { appendLine("- $it") }
                    }
                    if (history.isNotBlank()) appendLine("关系史摘要：").appendLine(history.take(HISTORY_CHARS))
                    if (aspiration.isNotBlank()) appendLine("用户理想自我：").appendLine(aspiration)
                    pack?.let {
                        append("关系路径：").appendLine(it.pathKind)
                        appendLine("阶段词表（仅供理解，不要照搬）：")
                        it.stages.forEach { s -> appendLine("- ${s.code} ${s.label}：${s.missionHint}") }
                    }
                },
                maxTokens = TOKENS_STRATEGIST,
                temperature = TEMP_STRATEGIST,
            )
        }.getOrNull() ?: return false

        val payload = parseObject(raw, OutcomesPayload.serializer()) ?: return false
        goals.putDecomp(goal.id, DECOMP_OUTCOMES, strategyJson.encodeToString(OutcomesPayload.serializer(), payload))

        // 自动落 ≤2 个 provisional L2（守互斥；其余候选留在 payload，目标页可「采纳」补建）。
        val now = System.currentTimeMillis()
        var cardDirty = false
        payload.l2.take(AUTO_L2_LIMIT).forEach { cand ->
            val days = cand.deadlineDays?.coerceIn(minDeadlineDays(pack?.pathKind), MAX_L2_DAYS)
            val result = goals.createProvisionalGoal(
                contactId = goal.contactId,
                horizon = HORIZON_L2,
                title = cand.title,
                parentId = goal.id,
                scenarioCode = goal.scenarioCode,
                successCriteria = cand.successCriteria,
                deadlineAt = days?.let { now + it * DAY_MS },
                priority = cand.priority,
            )
            if (result is GoalOpResult.Ok) {
                cardDirty = compileGoal(result.id) || cardDirty
            }
        }
        return cardDirty
    }

    private fun strategistSystemPrompt(pathKind: String?, group: String?): String = """
        你是用户的私人关系战略顾问。根据用户的远期愿景、双方画像与关系史，
        分解出 $AUTO_L2_L2_HINT 个中期目标（未来 2 周~3 个月可验证的成果）与用户的自我修炼项。

        规则：
        1. 中期目标必须具体、可验证（有明确的成功判据），按优先级排序；deadlineDays 取 $MIN_L2_DAYS~$MAX_L2_DAYS，
           ${PATH_DEFEND_HINT}场景可取 $DEFEND_MIN_DAYS~$MAX_L2_DAYS；
        2. 只输出 JSON，不要解释、不要 Markdown：
           {"l2":[{"title":"","successCriteria":"","deadlineDays":30,"priority":5}],"practices":["",""]}
        3. practices 是用户为实现愿景应坚持的日常练习（第一人称可执行，每条 ≤30 字）；
        4. 路径类型「$pathKind」、场景组「$group」：推进类重争取，经营类重稳定与边界，防守类重止损与体面；
        5. title 简洁，每条 ≤40 字；successCriteria ≤60 字。
    """.trimIndent()

    // ── PLANNER：L2 → roadmap + L1 ─────────────────────────────────────────

    private suspend fun runPlanner(goal: GoalEntity, force: Boolean): Boolean {
        if (!force && goals.latestDecomp(goal.id, DECOMP_ROADMAP)
                ?.let { !it.confirmed } == true
        ) {
            return false
        }
        val pack = styleAssets.scenarioPacks().byCode(goal.scenarioCode) ?: return false
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull() ?: return false
        val l3 = goal.parentId?.let { goals.goal(it) }

        val raw = runCatching {
            auxiliaryTasks.complete(
                config = config,
                systemPrompt = plannerSystemPrompt(pack.pathKind),
                userPrompt = buildString {
                    appendLine("中期目标：${goal.title}")
                    goal.successCriteria?.takeIf { it.isNotBlank() }?.let { appendLine("成功判据：$it") }
                    goal.deadlineAt?.let { appendLine("截止日期：${LocalDate.ofEpochDay(it / DAY_MS)}") }
                    l3?.let { appendLine("远期愿景一句：${it.title}") }
                    appendLine("场景阶段骨架（stage.code 只能取自下表）：")
                    pack.stages.forEach { s ->
                        appendLine("- ${s.code} ${s.label}：${s.missionHint}；推进信号：${s.advanceSignals.joinToString("/")}")
                    }
                    goals.stage(goal.contactId, pack.code)?.let {
                        appendLine("当前所处站：${it.stageCode}（${it.evidence.orEmpty()}）")
                    }
                },
                maxTokens = TOKENS_PLANNER,
                temperature = TEMP_PLANNER,
            )
        }.getOrNull() ?: return false

        val parsed = parseObject(raw, RoadmapPayload.serializer()) ?: return false
        // P3：越界 code/非法边清洗（stage 越界丢弃、私有站重名丢弃、branch 强制需确认）。
        val payload = StageGraph.sanitize(pack, parsed)
        goals.putDecomp(goal.id, DECOMP_ROADMAP, strategyJson.encodeToString(RoadmapPayload.serializer(), payload))

        // 落 ≤3 个 provisional L1（守上限；withinDays 1~14）。
        val now = System.currentTimeMillis()
        payload.l1.take(AUTO_L1_LIMIT).forEach { cand ->
            if (cand.title.isBlank()) return@forEach
            goals.createProvisionalGoal(
                contactId = goal.contactId,
                horizon = HORIZON_L1,
                title = cand.title,
                parentId = goal.id,
                scenarioCode = goal.scenarioCode,
                successCriteria = cand.successCriteria,
                deadlineAt = now + cand.withinDays.coerceIn(MIN_L1_DAYS, MAX_L1_DAYS) * DAY_MS,
                priority = cand.priority,
            )
        }

        // stage_states 不存在则插首站，并钉住当前 roadmap 版本（P3：reroute 后按版本取图）。
        if (goals.stage(goal.contactId, pack.code) == null) {
            val version = goals.latestDecomp(goal.id, DECOMP_ROADMAP)?.version
            StagePolicy.initial(goal.contactId, pack, now)?.copy(roadmapVersion = version)
                ?.let { goals.commitStage(it, com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_INIT, "首站初始化", from = null, now = now) }
        }
        return true
    }

    private fun plannerSystemPrompt(pathKind: String): String = """
        你是用户的私人关系作战参谋。把一个中期目标拆成沿既定阶段链的路线图，
        并给出当前站可立即执行的近期目标（L1）。

        规则：
        1. stages 必须沿阶段骨架的先后顺序，code 只能使用给定 code（不得新造、不得跳站）；
           goal 是该站要达成的一句话状态，exitSignals 是出站信号短语；
           expectedDays 是该站预计停留天数（推进类 7~21，经营/防守类可更长）；
        2. l1 给 $L1_HINT 个「未来 1~14 天、当前站就能做」的近期目标，withinDays 取 $MIN_L1_DAYS~$MAX_L1_DAYS；
        3. 路径「$pathKind」：经营类重节奏稳定，防守类重事实固定与止损，推进类重下一步动作；
        4. edges 默认留空数组（系统会自动补顺序边）。仅当确有必要时才给：
           - retreat 回撤边（防守类：冲突激化时退回上一站止损），condition 写触发信号短语；
           - branch 分叉边（出现需要换打法的新局面），必须 "needConfirm":true；
           - skip 跳站边（某站条件明显可跳过）；
           from/to 的 code 必须取自给定骨架或 privateStages；privateStages 一般留空。
        5. 只输出 JSON，不要解释、不要 Markdown：
           {"stages":[{"code":"","goal":"","exitSignals":[""],"expectedDays":14}],"practices":[],
            "l1":[{"title":"","successCriteria":"","withinDays":7,"priority":5}],
            "edges":[{"from":"","to":"","kind":"retreat|branch|skip|advance","condition":"","needConfirm":false}],
            "privateStages":[]}
    """.trimIndent()

    // ── 画像/摘要素材 ──────────────────────────────────────────────────────

    /** 双方已确认画像行（与 ChatBriefBuilder 同款 code→label + 行为暗示渲染）。 */
    private suspend fun confirmedPersonaLines(contactId: Long): List<String> {
        val schema = schemas.get()
        val byKey = schema.fields.associateBy { it.key }
        fun render(entities: List<PersonaFieldEntity>): List<String> = entities.mapNotNull { e ->
            val def = byKey[e.field] ?: return@mapNotNull null
            val displayed = PersonaValueCodes.displayValue(def, e.value).trim()
            if (displayed.isEmpty()) return@mapNotNull null
            val annotation = annotationFor(def, e.value, displayed)
            val suffix = annotation?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            "${def.label}: $displayed$suffix"
        }
        val mine = render(contacts.myPersona().filter { it.confirmed })
        val theirs = if (contactId == 0L) emptyList() else render(contacts.fields(contactId).filter { it.confirmed })
        return mine.map { "我·$it" } + theirs.map { "对方·$it" }
    }

    /** my_aspiration 已确认标签的顿号拼接（空串表示无）。 */
    internal suspend fun aspirationLabels(contactId: Long): String {
        val def = schemas.get().def("my_aspiration") ?: return ""
        val entity = contacts.myPersona().firstOrNull { it.confirmed && it.field == "my_aspiration" }
            ?: return ""
        return PersonaValues.decodeTags(entity.value)
            .map { PersonaValueCodes.displaySingle(def, it).trim() }
            .filter { it.isNotEmpty() }
            .joinToString("、")
    }

    private suspend fun relationshipDigest(contactId: Long): String =
        contacts.bindingsForContact(contactId)
            .mapNotNull { it.digest?.takeIf { d -> d.isNotBlank() } }
            .sortedByDescending { it.length }
            .joinToString("\n")
            .take(HISTORY_CHARS)

    private fun annotationFor(def: PersonaFieldDef, stored: String, displayed: String): String? {
        if (def.type == PersonaFieldType.ENUM) {
            val raw = stored.trim()
            def.optionByCode(raw)?.impl?.takeIf { it.isNotBlank() }?.let { return it }
            def.optionByLabel(raw)?.impl?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if ((def.type == PersonaFieldType.TEXT || def.type == PersonaFieldType.TEXTAREA) &&
            !def.implTpl.isNullOrBlank()
        ) {
            return def.implTpl.replace("{v}", displayed)
        }
        return null
    }

    private fun <T> parseObject(raw: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        var text = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        text = text.substring(start, end + 1)
        return runCatching { strategyJson.decodeFromString(serializer, text) }
            .onFailure { Log.d(TAG, "planner json parse failed: ${it.message}") }
            .getOrNull()
    }

    private fun minDeadlineDays(pathKind: String?): Int =
        if (pathKind == "defend") DEFEND_MIN_DAYS else MIN_L2_DAYS

    companion object {
        private const val TAG = "GoalPlanner"

        private const val TOKENS_STRATEGIST = 900
        private const val TEMP_STRATEGIST = 0.4
        private const val TOKENS_PLANNER = 700
        private const val TEMP_PLANNER = 0.3
        private const val TEMP_REROUTE = 0.4

        private const val REROUTE_HISTORY_TAKE = 3
        private const val REROUTE_REASON = "重规划路线"

        private const val AUTO_L2_LIMIT = 2
        private const val AUTO_L1_LIMIT = 3
        private const val AUTO_L2_L2_HINT = "2~4"
        private const val L1_HINT = "1~3"

        private const val MIN_L2_DAYS = 14
        private const val MAX_L2_DAYS = 90
        private const val DEFEND_MIN_DAYS = 7
        private const val MIN_L1_DAYS = 1
        private const val MAX_L1_DAYS = 14

        private const val HISTORY_CHARS = 600
        private const val DAY_MS = 86_400_000L

        private const val PATH_DEFEND_HINT = "防守/维权"

        private val ACTIVE_STATUSES = setOf(GOAL_ACTIVE, com.verlintas.baic2.floatball.advisor.data.GOAL_PROVISIONAL)
    }
}
