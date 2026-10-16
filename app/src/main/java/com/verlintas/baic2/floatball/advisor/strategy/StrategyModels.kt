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
import com.verlintas.baic2.floatball.advisor.ScenarioStage
import com.verlintas.baic2.floatball.advisor.data.GoalEntity
import com.verlintas.baic2.floatball.advisor.data.StageStateEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * P2 策略体系的 JSON 产物与回合上下文（零 Android 依赖，可 JVM 单测）。
 * 慢车道（GoalPlanner/CardCompiler）产出、快车道（Tactician/阶段机）消费。
 */

/** LLM 在 <plan> 块中给出的本轮战术（两段式输出的前半）。 */
@Serializable
data class MissionPlan(
    val mission: String = "",
    val actions: List<String> = emptyList(),
    val forbidden: List<String> = emptyList(),
    val signalsToWatch: List<String> = emptyList(),
    val successWhen: String = "",
)

/** 策略卡分区单条：[source] ai=编译器生成（重编即换），user=用户改写（按指纹永久保留）。 */
@Serializable
data class CardItem(
    val text: String = "",
    val source: String = SOURCE_AI,
) {
    companion object {
        const val SOURCE_AI = "ai"
        const val SOURCE_USER = "user"
    }
}

/**
 * 策略卡负载（strategy_cards.payloadJson）。
 * [roleLineSource] 记录角色句是否被用户改过——改过则重编不覆盖（§6.4）。
 */
@Serializable
data class StrategyCardPayload(
    val roleLine: String = "",
    val selfConstraints: List<CardItem> = emptyList(),
    val theirImplications: List<CardItem> = emptyList(),
    val topicBank: List<CardItem> = emptyList(),
    val taboos: List<CardItem> = emptyList(),
    val cardVersion: Int = 1,
    val roleLineSource: String = CardItem.SOURCE_AI,
)

/** STRATEGIST 产物中的 L2 候选：[deadlineDays] 14~90（defend 可短至 7）。 */
@Serializable
data class L2Candidate(
    val title: String = "",
    val successCriteria: String = "",
    val deadlineDays: Int? = null,
    val priority: Int = 5,
)

/** PLANNER 产物中的 L1 候选：[withinDays] 1~14。 */
@Serializable
data class L1Candidate(
    val title: String = "",
    val successCriteria: String = "",
    val withinDays: Int = 7,
    val priority: Int = 5,
)

/** L3 编译产物：L2 候选（≤4，自动落 ≤2）+ 自我修炼项。 */
@Serializable
data class OutcomesPayload(
    val l2: List<L2Candidate> = emptyList(),
    val practices: List<String> = emptyList(),
)

/** 路线图单站：[code] 必须在 pack.stages 内，越界整项丢弃。 */
@Serializable
data class RoadmapStage(
    val code: String = "",
    val goal: String = "",
    val exitSignals: List<String> = emptyList(),
    /** P3：该站预计停留天数（reroute 超时判定用，缺省由调用方兜底 14 天）。 */
    val expectedDays: Int? = null,
)

/** 有向图边种类（P3）：顺序前进 / 分叉（需确认）/ 回撤（可回站）/ 跳站。 */
const val EDGE_KIND_ADVANCE = "advance"
const val EDGE_KIND_BRANCH = "branch"
const val EDGE_KIND_RETREAT = "retreat"
const val EDGE_KIND_SKIP = "skip"

val EDGE_KINDS = setOf(EDGE_KIND_ADVANCE, EDGE_KIND_BRANCH, EDGE_KIND_RETREAT, EDGE_KIND_SKIP)

/**
 * 有向图边（P3）：[from]/[to] 为站 code；[condition] 为触发条件信号短语；
 * [needConfirm] 的边（branch）只产轻确认不自动移动；[pathTag] 为进入支线时打的标记。
 */
@Serializable
data class EdgeDef(
    val from: String = "",
    val to: String = "",
    val kind: String = EDGE_KIND_ADVANCE,
    val condition: String = "",
    val needConfirm: Boolean = false,
    val pathTag: String? = null,
)

/**
 * 私有支线站（P3）：随 roadmap 版本存储的临时站点，不进场景包资产；
 * reroute 换版后旧支线随旧版失效。字段与 [com.verlintas.baic2.floatball.advisor.ScenarioStage]
 * 对齐并补 goal/retreatSignals/expectedDays。
 */
@Serializable
data class PrivateStageDef(
    val code: String = "",
    val label: String = "",
    val goal: String = "",
    val exitSignals: List<String> = emptyList(),
    val advanceSignals: List<String> = emptyList(),
    val missionHint: String = "",
    val retreatSignals: List<String> = emptyList(),
    val pathTag: String? = null,
    val expectedDays: Int? = null,
)

/** L2 编译产物：路线图（覆盖资产 missionHint）+ 当前站 L1（1~3）+ P3 有向图。 */
@Serializable
data class RoadmapPayload(
    val stages: List<RoadmapStage> = emptyList(),
    val practices: List<String> = emptyList(),
    val l1: List<L1Candidate> = emptyList(),
    /** 有向图边；缺省 = 主链相邻站隐式顺序 advance 边（逐字等价 P2 线性链）。 */
    val edges: List<EdgeDef> = emptyList(),
    /** 私有支线站（code 不得与 pack.stages 冲突，冲突丢弃）。 */
    val privateStages: List<PrivateStageDef> = emptyList(),
    /** 仅 reroute 产物使用：oldCode→newCode 站映射；缺键的旧站迁移时落新链首站。 */
    val stageMapping: Map<String, String> = emptyMap(),
)

/**
 * 运行时站点视图（P3）：骨架站与私有支线站的统一形态，由 StageGraph.resolve 构建，零 LLM。
 * [pathTag] null = 主链骨架站。
 */
data class RuntimeStage(
    val code: String,
    val label: String,
    val advanceSignals: List<String>,
    val missionHint: String,
    val retreatSignals: List<String>,
    val pathTag: String?,
    val expectedDays: Int?,
    /** roadmap 对本站的覆盖目标句（私有站取自身 goal）。 */
    val goal: String,
    val private: Boolean,
)

/**
 * 回合策略上下文（T1 取数结果）：三者全 null 时调用方直接回退 P1 链路。
 * [roadmapStageGoal] 来自最新 roadmap decomp 对当前站的覆盖文案（缺省走 stageDef.missionHint）。
 */
data class StrategyContext(
    val contactId: Long,
    val conversationId: Long?,
    val pack: ScenarioPack,
    val stage: StageStateEntity,
    val stageDef: ScenarioStage?,
    /** P3：当前站运行时视图（骨架站/私有支线站统一；未知站为 null）。 */
    val runtimeStage: RuntimeStage? = null,
    val activeL2: GoalEntity?,
    val activeL1: GoalEntity?,
    val card: StrategyCardPayload?,
    val retreatHits: List<String> = emptyList(),
    /** P3 §12.2：中性时序信号（如 silence_24h）；只描述事实，不驱动边裁决。 */
    val neutralSignals: List<String> = emptyList(),
    /** P3 §12.2：距对方上次来信的完整小时数；无记录为 null（不臆测）。 */
    val hoursSinceIncoming: Int? = null,
    val roadmapStageGoal: String? = null,
    /** P3：当前激活版本的路线图（含 edges/privateStages）；null = 无编译产物、纯资产线性链。 */
    val roadmap: RoadmapPayload? = null,
    /** 画像中 their taboos（雷区禁忌）按行拆分，红线门聚合用。 */
    val theirTabooLines: List<String> = emptyList(),
    /** 画像中 my my_taboo（我不能说的话）按行拆分。 */
    val myTabooLines: List<String> = emptyList(),
)

/** 策略层统一宽松 Json：LLM 输出永远不可信，解析失败一律走降级而非抛异常。 */
val strategyJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
