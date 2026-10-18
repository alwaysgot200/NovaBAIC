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

package com.verlintas.baic2.floatball.brief

import android.util.Log
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.floatball.advisor.GoalRepository
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.BRIEF_KIND_MONTHLY
import com.verlintas.baic2.floatball.advisor.data.BRIEF_KIND_ON_DEMAND
import com.verlintas.baic2.floatball.advisor.data.BRIEF_KIND_WEEKLY
import com.verlintas.baic2.floatball.advisor.data.BriefOutputEntity
import com.verlintas.baic2.floatball.advisor.data.GoalEntity
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L1
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L2
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L3
import com.verlintas.baic2.floatball.advisor.data.MISSION_ADVANCED
import com.verlintas.baic2.floatball.advisor.data.MISSION_HELD
import com.verlintas.baic2.floatball.advisor.data.MISSION_IGNORED
import com.verlintas.baic2.floatball.advisor.data.MISSION_PENDING
import com.verlintas.baic2.floatball.advisor.data.MISSION_REGRESSED
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_ADVANCE
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_BRANCH
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_INIT
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_MANUAL
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_RETREAT
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_REROUTE
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_SKIP
import com.verlintas.baic2.floatball.advisor.data.StageHistoryEntity
import com.verlintas.baic2.floatball.advisor.data.TalkMissionEntity
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 简报模型输出协议（输出永远不可信，字段缺省走默认值，绝不抛到 UI）。 */
@Serializable
internal data class BriefContent(
    /** 一句话阶段位置与整体态势。 */
    val stagePosition: String = "",
    /** 近期关键事件（必须能对应窗口内 history/mission 行）。 */
    val recentEvents: List<String> = emptyList(),
    /** 目标进展。 */
    val goalProgress: List<String> = emptyList(),
    /** 风险提示。 */
    val risks: List<String> = emptyList(),
    /** 下一步建议。 */
    val nextMoves: List<String> = emptyList(),
)

/**
 * 简报取数快照：窗口内的阶段变迁、任务令台账、有效目标与当前站。
 * 纯数据载体（实体本身无 Android 依赖），供提示词纯函数与 JVM 单测使用。
 */
internal data class BriefFacts(
    val contactName: String,
    val rangeStart: Long,
    val rangeEnd: Long,
    /** 窗口内 stage_history 行，时间倒序。 */
    val history: List<StageHistoryEntity>,
    /** 窗口内 talk_missions 行（含 result 结局），时间倒序。 */
    val missions: List<TalkMissionEntity>,
    /** 有效（provisional+active）目标，L3→L1。 */
    val goals: List<GoalEntity>,
    /** 当前站 code（stage_states 优先，回退最近一条 history 的 toCode）。 */
    val currentStageCode: String? = null,
    /** 当前站进入时间。 */
    val currentStageEnteredAt: Long? = null,
)

/**
 * 简报提示词拼装与产物解析（纯函数，无 Android 依赖，方案 §3/§6）。
 *
 * 遵循 MissionJudgeRunner 同款口径：严格 JSON 输出协议 + 宽松解析（截首个 {…}）；
 * 数据段确定性拼装、每行带日期、总字符预算 [PROMPT_BUDGET]。
 */
internal object BriefPrompts {

    /** user 提示词总字符预算（数据段）。 */
    internal const val PROMPT_BUDGET = 2000

    /** 单行最大长度，超长截断加省略号。 */
    internal const val LINE_MAX = 100

    /** 窗口内没有任何阶段变动/任务记录时无可汇总，不需要调 LLM（目标本身不算事件）。 */
    fun hasWindowData(facts: BriefFacts): Boolean =
        facts.history.isNotEmpty() || facts.missions.isNotEmpty()

    fun systemPrompt(): String = """
        你是聊天参谋的简报专家。根据给定的「阶段变迁」「任务令台账」「目标进展」，
        汇总一份简明的关系进展简报。只输出一个 JSON 对象，不要解释、不要 Markdown：
        {"stagePosition":"一句话概括当前所处阶段与整体态势，≤50字",
         "recentEvents":["近期关键事件，每条≤40字，最多4条，逐条对应给定的阶段变迁或任务令行"],
         "goalProgress":["各目标的最新进展，每条≤40字，最多4条"],
         "risks":["需要注意的风险，每条≤40字，最多3条，没有就给空数组"],
         "nextMoves":["下一步行动建议，每条≤40字，最多3条"]}
        口径：只使用输入中给出的事实，禁止编造窗口外或不存在的事件；
        recentEvents 的每一条都必须能在输入的阶段变迁或任务令行中找到对应；
        语言客观克制，不要夸张修辞。
    """.trimIndent()

    /**
     * 拼装数据段。[zone] 供 JVM 单测注入固定时区；超预算即停（确定性截断）。
     */
    fun userPrompt(facts: BriefFacts, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder()
        var used = 0
        fun append(text: String) {
            if (used >= PROMPT_BUDGET) return
            val clipped = if (text.length <= PROMPT_BUDGET - used) text else text.take(PROMPT_BUDGET - used)
            sb.append(clipped)
            used += clipped.length
        }
        fun appendLine(text: String) = append("$text\n")

        appendLine("联系人：${facts.contactName.ifBlank { "（未知）" }}")
        appendLine("统计区间：${md(facts.rangeStart, zone)} ~ ${md(facts.rangeEnd, zone)}")
        facts.currentStageCode?.let { code ->
            val since = facts.currentStageEnteredAt?.let { "（自 ${md(it, zone)} 起）" }.orEmpty()
            appendLine("当前阶段：$code$since")
        }
        if (facts.goals.isNotEmpty()) {
            appendLine("有效目标：")
            facts.goals.take(GOAL_LINES).forEach { g ->
                appendLine("- [L${g.horizon}] ${clip(g.title)}${progressSuffix(g.progressNote)}")
            }
        }
        if (facts.history.isNotEmpty()) {
            appendLine("阶段变迁（时间倒序）：")
            facts.history.take(HISTORY_LINES).forEach { h ->
                val from = h.fromCode?.let { "$it → " }.orEmpty()
                val reason = h.reason?.takeIf { it.isNotBlank() }?.let { "（${clip(it)}）" }.orEmpty()
                appendLine("- ${md(h.at, zone)} ${edgeLabel(h.edge)}：$from${h.toCode}$reason")
            }
        }
        if (facts.missions.isNotEmpty()) {
            appendLine("任务令台账（时间倒序，含结局）：")
            facts.missions.take(MISSION_LINES).forEach { m ->
                appendLine("- ${md(m.createdAt, zone)} ${clip(m.mission)}（结局：${missionResultLabel(m.result)}）")
            }
        }
        return sb.toString()
    }

    /** 宽松解析：去 ```json 围栏并截取首个 {…}；畸形输出返回 null。 */
    fun parse(raw: String): BriefContent? {
        val noFence = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = noFence.indexOf('{')
        val end = noFence.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            strategyJson.decodeFromString(BriefContent.serializer(), noFence.substring(start, end + 1))
        }.getOrNull()
    }

    private fun progressSuffix(note: String?): String =
        note?.takeIf { it.isNotBlank() }?.let { "（进展：${clip(it)}）" }.orEmpty()

    /** stage_history.edge → 提示词用中文标签（非用户可见文案）。 */
    internal fun edgeLabel(edge: String): String = when (edge) {
        STAGE_EDGE_INIT -> "起步"
        STAGE_EDGE_ADVANCE -> "推进"
        STAGE_EDGE_BRANCH -> "分叉"
        STAGE_EDGE_RETREAT -> "回撤"
        STAGE_EDGE_SKIP -> "跳站"
        STAGE_EDGE_MANUAL -> "手动"
        STAGE_EDGE_REROUTE -> "重规划"
        else -> "变动"
    }

    /** talk_missions.result → 提示词用中文标签。 */
    internal fun missionResultLabel(result: String): String = when (result) {
        MISSION_ADVANCED -> "推进"
        MISSION_HELD -> "维持"
        MISSION_REGRESSED -> "倒退"
        MISSION_IGNORED -> "未采纳"
        MISSION_PENDING -> "待裁"
        else -> "维持"
    }

    private fun clip(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= LINE_MAX) oneLine else oneLine.take(LINE_MAX) + "…"
    }

    private fun md(epochMillis: Long, zone: ZoneId): String =
        DATE.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    private const val GOAL_LINES = 6
    private const val HISTORY_LINES = 10
    private const val MISSION_LINES = 12
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd", Locale.CHINA)
}

/**
 * 简报专家（多代理方案 §3 简报专家行 / §10 M0）：慢车道汇总「最近两周与某人的
 * 关系进展到哪一步」。取数 → 确定性拼装 → AuxiliaryTasks 轻调用（≤800 tok）→
 * 宽松解析 → brief_outputs 落库（每联系人每 kind 滚动 10 份）。
 *
 * 全链路失败静默（Log.d + 返回 null），绝不抛到 UI；窗口内无任何
 * stage_history/talk_missions 数据时直接返回 null，不耗 token。
 */
@Singleton
class BriefCompiler @Inject constructor(
    private val goals: GoalRepository,
    private val db: AdvisorDatabase,
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {
    private val historyDao get() = db.stageHistoryDao()
    private val missionDao get() = db.talkMissionDao()
    private val briefDao get() = db.briefOutputDao()

    /** 某粒度的最新一份简报；读取失败静默返回 null。 */
    suspend fun latest(contactId: Long, kind: String): BriefOutputEntity? =
        runCatching { briefDao.latest(contactId, kind) }.getOrNull()

    /** 窗口内是否存在可汇总的活动（无则 UI 直接给空态，不调 LLM）。 */
    suspend fun hasRecentActivity(contactId: Long): Boolean =
        BriefPrompts.hasWindowData(collectFacts(contactId, BRIEF_KIND_ON_DEMAND))

    /**
     * 惰性周报刷新（GoalTab 首次进入调用）：最新 weekly 超过 [WEEKLY_STALE_MS]
     * 才重新编译；无数据/失败静默返回 null，不打扰用户。
     */
    suspend fun refreshWeeklyIfStale(contactId: Long): BriefOutputEntity? {
        val latest = latest(contactId, BRIEF_KIND_WEEKLY)
        if (latest != null && System.currentTimeMillis() - latest.createdAt < WEEKLY_STALE_MS) {
            return null
        }
        return compile(contactId, BRIEF_KIND_WEEKLY)
    }

    /**
     * 编译一份简报并落库。
     * @return 成功带落库实体；无数据/无配置/超时/解析失败/落库失败一律 null。
     */
    suspend fun compile(contactId: Long, kind: String): BriefOutputEntity? =
        withContext(Dispatchers.Default) {
            val facts = collectFacts(contactId, kind)
            if (!BriefPrompts.hasWindowData(facts)) {
                Log.d(TAG, "brief skipped (no window data): contact=$contactId, kind=$kind")
                return@withContext null
            }
            val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
            if (config == null) {
                Log.d(TAG, "brief skipped (no provider config): contact=$contactId")
                return@withContext null
            }
            val raw = withTimeoutOrNull(COMPILE_TIMEOUT_MS) {
                runCatching {
                    auxiliaryTasks.complete(
                        config = config,
                        systemPrompt = BriefPrompts.systemPrompt(),
                        userPrompt = BriefPrompts.userPrompt(facts),
                        maxTokens = COMPILE_MAX_TOKENS,
                        temperature = COMPILE_TEMPERATURE,
                    )
                }.getOrNull()
            }
            if (raw.isNullOrBlank()) {
                Log.d(TAG, "brief llm failed/timeout: contact=$contactId, kind=$kind")
                return@withContext null
            }
            val content = BriefPrompts.parse(raw) ?: run {
                Log.d(TAG, "brief parse failed: ${raw.take(120)}")
                return@withContext null
            }
            val now = System.currentTimeMillis()
            val entity = BriefOutputEntity(
                contactId = contactId,
                kind = kind,
                rangeStart = facts.rangeStart,
                rangeEnd = facts.rangeEnd,
                contentJson = strategyJson.encodeToString(BriefContent.serializer(), content),
                createdAt = now,
            )
            runCatching {
                val id = briefDao.insert(entity)
                briefDao.trimFor(contactId, BRIEF_KEEP)
                entity.copy(id = id)
            }.getOrElse {
                Log.d(TAG, "brief persist failed: $it")
                null
            }
        }

    /** 取数（全部只读，逐项 runCatching 静默降级），拼装 [BriefFacts] 供纯函数消费。 */
    private suspend fun collectFacts(
        contactId: Long,
        kind: String,
        now: Long = System.currentTimeMillis(),
    ): BriefFacts {
        val rangeStart = now - lookbackMs(kind)
        val activeGoals = listOf(HORIZON_L3, HORIZON_L2, HORIZON_L1).flatMap { horizon ->
            runCatching { goals.activeGoals(contactId, horizon) }.getOrDefault(emptyList())
        }
        val profile = runCatching { db.contactProfileDao().findById(contactId) }.getOrNull()
        // 场景全集：主 L2 场景 + 全部有效目标的场景 + 档案默认场景（stage_history 按场景查询）。
        val scenarioCodes = buildSet {
            profile?.scenarioCode?.let(::add)
            activeGoals.forEach { g -> g.scenarioCode?.let(::add) }
        }
        val history = scenarioCodes
            .flatMap { code ->
                runCatching { historyDao.recent(contactId, code, HISTORY_FETCH) }.getOrDefault(emptyList())
            }
            .filter { it.at >= rangeStart && it.at <= now }
            .sortedByDescending { it.at }
            .take(HISTORY_FETCH)
        val missions = runCatching { missionDao.recentForContact(contactId, MISSION_FETCH) }
            .getOrDefault(emptyList())
            .filter { it.createdAt >= rangeStart && it.createdAt <= now }
            .sortedByDescending { it.createdAt }
            .take(MISSION_LINES_TOTAL)

        // 当前站：优先主场景的 stage_states；缺失回退最近一条 history 的 toCode（含窗口外）。
        val primaryScenario = activeGoals.firstOrNull { it.horizon == HORIZON_L2 }?.scenarioCode
            ?: profile?.scenarioCode
        var currentStageCode: String? = null
        var currentStageEnteredAt: Long? = null
        if (primaryScenario != null) {
            runCatching { goals.stage(contactId, primaryScenario) }.getOrNull()?.let { state ->
                currentStageCode = state.stageCode
                currentStageEnteredAt = state.enteredAt
            }
        }
        if (currentStageCode == null) {
            val fallback = scenarioCodes
                .flatMap { code ->
                    runCatching { historyDao.recent(contactId, code, 1) }.getOrDefault(emptyList())
                }
                .maxByOrNull { it.at }
            if (fallback != null) {
                currentStageCode = fallback.toCode
                currentStageEnteredAt = fallback.at
            }
        }
        return BriefFacts(
            contactName = profile?.displayName.orEmpty(),
            rangeStart = rangeStart,
            rangeEnd = now,
            history = history,
            missions = missions,
            goals = activeGoals,
            currentStageCode = currentStageCode,
            currentStageEnteredAt = currentStageEnteredAt,
        )
    }

    private fun lookbackMs(kind: String): Long = when (kind) {
        BRIEF_KIND_MONTHLY -> MONTHLY_LOOKBACK_MS
        BRIEF_KIND_WEEKLY, BRIEF_KIND_ON_DEMAND -> WEEKLY_LOOKBACK_MS
        else -> WEEKLY_LOOKBACK_MS
    }

    private companion object {
        const val TAG = "BriefCompiler"

        /** weekly/onDemand 回看窗口（方案 §10 M0：两周口径）。 */
        const val WEEKLY_LOOKBACK_MS = 14L * 24 * 60 * 60 * 1000
        const val MONTHLY_LOOKBACK_MS = 30L * 24 * 60 * 60 * 1000

        /** weekly 简报新鲜期：超过才在进入目标页时静默刷新。 */
        const val WEEKLY_STALE_MS = 7L * 24 * 60 * 60 * 1000

        const val COMPILE_MAX_TOKENS = 800
        const val COMPILE_TEMPERATURE = 0.3
        const val COMPILE_TIMEOUT_MS = 30_000L

        /** brief_outputs 每联系人每 kind 滚动保留份数（与 DAO trimFor 默认一致）。 */
        const val BRIEF_KEEP = 10

        /** 窗口内最多取的阶段变迁/任务令行数（提示词侧再各截前 10/12 条）。 */
        const val HISTORY_FETCH = 10
        const val MISSION_FETCH = 60
        const val MISSION_LINES_TOTAL = 12
    }
}
