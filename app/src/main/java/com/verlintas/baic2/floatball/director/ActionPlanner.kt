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

package com.verlintas.baic2.floatball.director

import android.content.Context
import android.util.Log
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.floatball.advisor.GoalRepository
import com.verlintas.baic2.floatball.advisor.ScenarioPack
import com.verlintas.baic2.floatball.advisor.StrategyBrief
import com.verlintas.baic2.floatball.advisor.data.ACTION_KIND_CARE
import com.verlintas.baic2.floatball.advisor.data.ACTION_KIND_CUSTOM
import com.verlintas.baic2.floatball.advisor.data.ACTION_KIND_GIFT
import com.verlintas.baic2.floatball.advisor.data.ACTION_KIND_MEETUP
import com.verlintas.baic2.floatball.advisor.data.ACTION_KIND_VISIT
import com.verlintas.baic2.floatball.advisor.data.ACTION_STATUS_DRAFT
import com.verlintas.baic2.floatball.advisor.data.ActionPlayEntity
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L1
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L2
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 谋略库单条（assets/advisor/action_stratagems.json，方案 §6.1）：古典谋略的
 * 沟通化转译。红线：只收正当经营/社交的线下行动，欺骗钱财类不入库；
 * [risk] 必填（资产评审门禁 + 解析兜底双重把关）。
 */
@Serializable
data class ActionStratagem(
    val code: String = "",
    val name: String = "",
    /** 四大类分组：business/workplace/romance/conflict（展示用，过滤不依赖它）。 */
    val category: String = "",
    /** 落库时映射 action_plays.kind（ACTION_KIND_* 之一）。 */
    val kind: String = ACTION_KIND_CUSTOM,
    /** 适用路径 pursue/maintain/defend；空 = 不限路径。 */
    val paths: List<String> = emptyList(),
    /**
     * 阶段白名单：场景包 stage code 全集口径（与 StageGraph/StrategyModels 一致）。
     * 谋略代码只有落在当前站的白名单内才可被选用（方案 §5.3 验收）。
     */
    val stages: List<String> = emptyList(),
    /** 路径说明：这条谋略如何展开的一句话。 */
    val route: String = "",
    /** 模板步骤，可含 {name} 占位（落库前由调用方填充）。 */
    val steps: List<String> = emptyList(),
    val prereq: String = "",
    /** 风险与代价（必填）。 */
    val risk: String = "",
)

/** 谋略库文件根结构（照 topic_seeds 惯例带外壳，便于后续扩展元字段）。 */
@Serializable
data class ActionStratagemBank(val stratagems: List<ActionStratagem> = emptyList())

/** action_plays.payloadJson 的自包含负载（方案 §6 表结构注释口径）。 */
@Serializable
internal data class ActionPlayPayload(
    val title: String = "",
    val rationale: String = "",
    val steps: List<String> = emptyList(),
    val cost: String = "",
    val risk: String = "",
    val prereq: String = "",
)

/**
 * 模型单条产出协议（输出永远不可信）：字段缺省走默认值，非法组合在
 * [ActionStratagemEngine.parse] 中静默丢弃，绝不抛到 UI。
 */
@Serializable
internal data class RawPlay(
    val stratagemCode: String = "",
    val kind: String = "",
    val title: String = "",
    val rationale: String = "",
    val steps: List<String> = emptyList(),
    val cost: String = "",
    val risk: String = "",
    val prereq: String = "",
)

@Serializable
private data class PlaysWrapper(val plays: List<RawPlay> = emptyList())

/** 解析校验后的行动草案：[ActionPlanner] 据此构造 ActionPlayEntity 落库。 */
data class PlayDraft(
    val kind: String,
    val stratagemCode: String,
    val title: String,
    val rationale: String,
    val steps: List<String>,
    val cost: String,
    val risk: String,
    val prereq: String,
)

/**
 * 谋略库筛选与产物解析纯函数（M1 方案 §6.1，零 Android 依赖，JVM 可单测）。
 *
 * 职责边界：ActionPlanner 的本事是「从库中选条 + 按态势定制」，不是自由发明——
 * 阶段白名单是硬闸（方案 §5.3 验收：谋略代码必须在白名单内）；risk 必填是第二条
 * 硬闸（缺 risk 的建议一律丢弃，库内 risk 兜底优先于模型空话）。解析宽松容错：
 * 裸数组 / {"plays":[…]} / 单对象三种形态都接受，畸形输出返回空列表走降级。
 */
object ActionStratagemEngine {

    /** 单次规划最多落库的草案数（少而准，避免把目标页刷成建议墙）。 */
    const val PLAYS_MAX = 3

    /** 草案标题上限（模型超长截断，库内谋略名兜底）。 */
    const val TITLE_MAX = 16

    /** 单条步骤/行级字段截断上限。 */
    const val LINE_MAX = 60

    /** 步骤条数上限（库内模板 4 条，模型 2~4 条口径）。 */
    const val STEPS_MAX = 4

    /** 提示词数据段总预算与候选块预算（确定性截断，防极端资产撑爆）。 */
    const val PROMPT_BUDGET = 2600
    const val CANDIDATE_BUDGET = 2200

    /** 合法 kind 全集与四大类分组（资产校验与解析共用）。 */
    internal val KINDS = setOf(ACTION_KIND_MEETUP, ACTION_KIND_GIFT, ACTION_KIND_VISIT, ACTION_KIND_CARE, ACTION_KIND_CUSTOM)
    internal val CATEGORIES = setOf("business", "workplace", "romance", "conflict")

    /** 库资产解码：畸形文件返回空库（启动不崩，行动建议退化空态）。 */
    fun decodeBank(text: String): ActionStratagemBank = runCatching {
        strategyJson.decodeFromString(ActionStratagemBank.serializer(), text)
    }.getOrDefault(ActionStratagemBank())

    /**
     * 阶段白名单过滤：站码为空或无匹配直接空（弱保证——没有阶段就没有白名单，
     * 宁可不出建议也不出越阶建议）；[pathKind] 已知时再按谋略声明路径二次收窄，
     * 谋略未声明路径视为不限。
     */
    fun filter(bank: ActionStratagemBank, stageCode: String?, pathKind: String?): List<ActionStratagem> {
        val stage = stageCode?.trim().orEmpty()
        if (stage.isEmpty()) return emptyList()
        val path = pathKind?.trim().orEmpty()
        return bank.stratagems
            .filter { it.stages.any { s -> s.trim() == stage } }
            .filter { it.paths.isEmpty() || path.isEmpty() || it.paths.any { p -> p.trim() == path } }
    }

    /** {name} 等占位填充：缺失参数原样保留，不做猜测替换。 */
    fun fillParams(text: String, params: Map<String, String>): String =
        params.entries.fold(text) { acc, (key, value) -> acc.replace("{$key}", value) }

    /**
     * 宽松解析 + 硬闸校验。逐条规则：stratagemCode 必须在 [allowed] 内（越库丢弃）；
     * title/risk 缺失用库内条目兜底，risk 仍为空即丢弃；steps 缺失用库内模板兜底；
     * kind 非法用库内 kind；同谋略同标题去重；总量截到 [PLAYS_MAX]。
     */
    fun parse(
        raw: String,
        allowed: List<ActionStratagem>,
        params: Map<String, String> = emptyMap(),
    ): List<PlayDraft> {
        val plays = decodePlays(raw)
        if (plays.isEmpty()) return emptyList()
        val drafts = ArrayList<PlayDraft>(plays.size)
        val seen = HashSet<String>()
        for (r in plays) {
            val strat = allowed.firstOrNull { it.code.equals(r.stratagemCode.trim(), ignoreCase = true) } ?: continue
            val title = r.title.trim().ifBlank { strat.name.trim() }
            if (title.isEmpty()) continue
            val risk = r.risk.trim().ifBlank { strat.risk.trim() }
            if (risk.isEmpty()) continue
            val steps = r.steps.map { fillParams(it.trim(), params) }.filter { it.isNotEmpty() }
                .ifEmpty { strat.steps.map { fillParams(it.trim(), params) }.filter { it.isNotEmpty() } }
            if (steps.isEmpty()) continue
            val key = strat.code + "|" + title
            if (!seen.add(key)) continue
            drafts += PlayDraft(
                kind = r.kind.trim().takeIf { it in KINDS } ?: strat.kind,
                stratagemCode = strat.code,
                title = title.take(TITLE_MAX),
                rationale = fillParams(r.rationale.trim(), params).take(LINE_MAX * 2),
                steps = steps.take(STEPS_MAX),
                cost = fillParams(r.cost.trim(), params).take(LINE_MAX),
                risk = fillParams(risk, params).take(LINE_MAX),
                prereq = r.prereq.trim().ifBlank { strat.prereq.trim() }.take(LINE_MAX),
            )
            if (drafts.size >= PLAYS_MAX) break
        }
        return drafts
    }

    /** 三形态宽松解码：裸数组 → {"plays":[…]} 包裹 → 单对象。全失败返回空。 */
    private fun decodePlays(raw: String): List<RawPlay> {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (text.isEmpty()) return emptyList()
        val arrayStart = text.indexOf('[')
        val arrayEnd = text.lastIndexOf(']')
        if (arrayStart in 0 until arrayEnd) {
            runCatching {
                return strategyJson.decodeFromString(
                    ListSerializer(RawPlay.serializer()),
                    text.substring(arrayStart, arrayEnd + 1),
                )
            }
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val body = text.substring(start, end + 1)
        // 包裹形态必须真的带出内容才算命中：{"plays":[]} 空壳会让缺省字段空匹配，
        // 若据此返回会吞掉同结构的单对象形态，故为空时继续回退 RawPlay 解析。
        runCatching { strategyJson.decodeFromString(PlaysWrapper.serializer(), body).plays }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        runCatching { return listOf(strategyJson.decodeFromString(RawPlay.serializer(), body)) }
        return emptyList()
    }
}

/** 行动规划取数快照：联系人/场景/阶段/有效目标（纯数据载体，供提示词纯函数）。 */
internal data class PlanFacts(
    val contactName: String = "",
    val scenarioLabel: String = "",
    val pathKind: String = "",
    val stageCode: String = "",
    val stageLabel: String = "",
    val goalTitles: List<String> = emptyList(),
)

/**
 * 行动建议提示词拼装（纯函数，无 Android 依赖）。遵循 BriefCompiler 同款口径：
 * 严格 JSON 输出协议 + 确定性数据段 + 总字符预算截断。
 */
internal object ActionPlannerPrompts {

    fun systemPrompt(): String = """
        你是聊天参谋的行动建议家。根据给定的「谋略库候选」「当前阶段」「有效目标」，
        挑选最适合当下态势的 1~3 条谋略，定制成可执行的线下行动草案。
        只输出一个 JSON 对象，不要解释、不要 Markdown：
        {"plays":[{"stratagemCode":"从候选中原样抄写","kind":"meetup/gift/visit/care/custom",
         "title":"行动标题，≤12字","rationale":"为什么是现在、为什么是这条，≤50字",
         "steps":["具体步骤，每条≤40字，2~4条，写清做什么、何时、何地"],
         "cost":"时间/金钱/人情的现实代价，≤40字","risk":"风险与代价，≤40字",
         "prereq":"开始前要确认的前提，≤30字"}]}
        口径：只能从候选中选择，stratagemCode 不在候选内或 risk 为空的建议会被丢弃；
        行动必须是正当的线下经营/社交行为，禁止任何欺骗、钱财诱导、算计伤害对方的建议；
        结合给定的阶段与目标定制步骤，不要原样照抄模板；语言务实具体，不写漂亮话。
    """.trimIndent()

    /** 数据段拼装：候选块带预算确定性截断，防止极端资产撑爆提示词。 */
    fun userPrompt(facts: PlanFacts, allowed: List<ActionStratagem>): String {
        val sb = StringBuilder()
        var used = 0
        fun append(text: String) {
            if (used >= ActionStratagemEngine.PROMPT_BUDGET) return
            val clipped =
                if (text.length <= ActionStratagemEngine.PROMPT_BUDGET - used) {
                    text
                } else {
                    text.take(ActionStratagemEngine.PROMPT_BUDGET - used)
                }
            sb.append(clipped)
            used += clipped.length
        }
        fun appendLine(text: String) = append("$text\n")

        appendLine("联系人：${facts.contactName.ifBlank { "（未知）" }}")
        appendLine("场景：${facts.scenarioLabel.ifBlank { "（未设）" }}（路径：${pathLabel(facts.pathKind)}）")
        appendLine("当前阶段：${facts.stageLabel.ifBlank { "（未知）" }}（${facts.stageCode}）")
        if (facts.goalTitles.isNotEmpty()) {
            appendLine("有效目标：")
            facts.goalTitles.take(GOAL_LINES).forEach { title -> appendLine("- ${clip(title)}") }
        }
        appendLine("谋略库候选（stratagemCode 只能从这里选）：")
        allowed.forEach { s ->
            append(
                "[${s.code}] ${s.name}（类型 ${s.kind}）｜展开：${s.route}｜" +
                    "模板步骤：${s.steps.mapIndexed { i, step -> "${i + 1}.${clip(step, STEP_CLIP)}" }.joinToString(" ")}｜" +
                    "前提：${s.prereq}｜风险：${s.risk}\n",
            )
        }
        return sb.toString()
    }

    /** 路径代码 → 提示词用中文标签（非用户可见文案）。 */
    internal fun pathLabel(pathKind: String): String = when (pathKind) {
        "pursue" -> "推进"
        "maintain" -> "经营"
        "defend" -> "防守"
        else -> pathKind.ifBlank { "未知" }
    }

    private fun clip(text: String, max: Int = ActionStratagemEngine.LINE_MAX): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= max) oneLine else oneLine.take(max) + "…"
    }

    private const val GOAL_LINES = 4
    private const val STEP_CLIP = 44
}

/** 规划结果：[Done] 带落库实体；[NoCandidates] 阶段白名单筛空；[Failed] 配置/超时/解析/落库失败。 */
sealed interface ActionPlanResult {
    data class Done(val plays: List<ActionPlayEntity>) : ActionPlanResult
    data object NoCandidates : ActionPlanResult
    data object Failed : ActionPlanResult
}

/**
 * 行动建议家（多代理方案 §5.3 ActionPlanner 行 / §10 M1-B）：从谋略库按阶段
 * 白名单选条，慢车道 AuxiliaryTasks 轻调用（≤500 tok）定制草案，宽松解析后
 * 以 draft 状态落 action_plays。M1 仅手动触发（目标页「给点子」），不做自动调度。
 *
 * 边界：不生成聊天文案；只出草案——采纳/完成/放弃全部由用户驱动（GoalTab 卡片）；
 * 全链路失败静默降级（Log.d + 结果态），绝不抛到 UI。
 */
@Singleton
class ActionPlanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val goals: GoalRepository,
    private val strategyBrief: StrategyBrief,
    private val db: AdvisorDatabase,
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {
    private val actionDao get() = db.actionPlayDao()

    @Volatile
    private var cachedBank: ActionStratagemBank? = null

    /** 谋略库资产（照 TopicEngineStore 范式：assets JSON + 进程内缓存，不入库）。 */
    fun bank(): ActionStratagemBank =
        cachedBank ?: runCatching {
            val text = context.assets.open(BANK_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            ActionStratagemEngine.decodeBank(text)
        }.getOrElse {
            Log.d(TAG, "stratagem bank load failed: $it")
            ActionStratagemBank()
        }.also { cachedBank = it }

    /** 某联系人的最近行动建议（含全部状态）；读取失败静默返回空列表。 */
    suspend fun recent(contactId: Long, limit: Int = RECENT_LIMIT): List<ActionPlayEntity> =
        runCatching { actionDao.recentFor(contactId, limit) }.getOrDefault(emptyList())

    /** 状态流转（draft→adopted→done / →dropped），GoalTab 四态操作统一入口。 */
    suspend fun setStatus(id: Long, status: String, resultNote: String? = null): Boolean =
        runCatching { actionDao.updateStatus(id, status, resultNote) }.isSuccess

    /**
     * 手动触发一次规划：按当前站白名单筛谋略 → LLM 定制 → draft 落库。
     * 无场景包/无阶段/白名单筛空一律 [ActionPlanResult.NoCandidates]（不耗 token）；
     * 无模型配置/超时/解析空/落库失败一律 [ActionPlanResult.Failed]。
     */
    suspend fun plan(contactId: Long): ActionPlanResult = withContext(Dispatchers.Default) {
        val pack = runCatching { strategyBrief.pack(contactId) }.getOrNull()
        if (pack == null) {
            Log.d(TAG, "plan skipped (no scenario pack): contact=$contactId")
            return@withContext ActionPlanResult.NoCandidates
        }
        val stage = runCatching { goals.stage(contactId, pack.code) }.getOrNull()
        if (stage == null) {
            Log.d(TAG, "plan skipped (no stage state): contact=$contactId, scenario=${pack.code}")
            return@withContext ActionPlanResult.NoCandidates
        }
        val allowed = ActionStratagemEngine.filter(bank(), stage.stageCode, pack.pathKind)
        if (allowed.isEmpty()) {
            Log.d(TAG, "plan skipped (no stratagem for stage ${stage.stageCode})")
            return@withContext ActionPlanResult.NoCandidates
        }
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
        if (config == null) {
            Log.d(TAG, "plan skipped (no provider config): contact=$contactId")
            return@withContext ActionPlanResult.Failed
        }

        val profile = runCatching { db.contactProfileDao().findById(contactId) }.getOrNull()
        val l2 = runCatching { goals.activeGoals(contactId, HORIZON_L2) }.getOrDefault(emptyList())
        val l1 = runCatching { goals.activeGoals(contactId, HORIZON_L1) }.getOrDefault(emptyList())
        val facts = PlanFacts(
            contactName = profile?.displayName.orEmpty(),
            scenarioLabel = pack.label,
            pathKind = pack.pathKind,
            stageCode = stage.stageCode,
            stageLabel = pack.stages.firstOrNull { it.code == stage.stageCode }?.label.orEmpty(),
            goalTitles = (l2 + l1).map { it.title },
        )

        val raw = withTimeoutOrNull(PLAN_TIMEOUT_MS) {
            runCatching {
                auxiliaryTasks.complete(
                    config = config,
                    systemPrompt = ActionPlannerPrompts.systemPrompt(),
                    userPrompt = ActionPlannerPrompts.userPrompt(facts, allowed),
                    maxTokens = PLAN_MAX_TOKENS,
                    temperature = PLAN_TEMPERATURE,
                )
            }.getOrNull()
        }
        if (raw.isNullOrBlank()) {
            Log.d(TAG, "plan llm failed/timeout: contact=$contactId")
            return@withContext ActionPlanResult.Failed
        }
        val drafts = ActionStratagemEngine.parse(raw, allowed, mapOf(PARAM_NAME to facts.contactName))
        if (drafts.isEmpty()) {
            Log.d(TAG, "plan parse empty: ${raw.take(120)}")
            return@withContext ActionPlanResult.Failed
        }

        val now = System.currentTimeMillis()
        val entities = drafts.map { draft ->
            ActionPlayEntity(
                contactId = contactId,
                goalId = l2.firstOrNull()?.id,
                stageCode = stage.stageCode,
                kind = draft.kind,
                stratagemCode = draft.stratagemCode,
                payloadJson = strategyJson.encodeToString(
                    ActionPlayPayload.serializer(),
                    ActionPlayPayload(
                        title = draft.title,
                        rationale = draft.rationale,
                        steps = draft.steps,
                        cost = draft.cost,
                        risk = draft.risk,
                        prereq = draft.prereq,
                    ),
                ),
                status = ACTION_STATUS_DRAFT,
                createdAt = now,
            )
        }
        val ids = runCatching { entities.map { actionDao.insert(it) } }.getOrElse {
            Log.d(TAG, "plan persist failed: $it")
            return@withContext ActionPlanResult.Failed
        }
        ActionPlanResult.Done(entities.zip(ids) { entity, id -> entity.copy(id = id) })
    }

    private companion object {
        const val TAG = "ActionPlanner"

        const val BANK_ASSET = "advisor/action_stratagems.json"

        /** 模板步骤里的联系人占位参数名。 */
        const val PARAM_NAME = "name"

        /** 慢车道口径（方案 §9 预算表：ActionPlanner ≤500 tok、用户点「给点子」才跑）。 */
        const val PLAN_MAX_TOKENS = 500
        const val PLAN_TEMPERATURE = 0.3
        const val PLAN_TIMEOUT_MS = 30_000L

        /** 目标页卡片默认展示的最近条数。 */
        const val RECENT_LIMIT = 20
    }
}
