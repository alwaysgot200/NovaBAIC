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

import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.advisor.data.GOAL_ACTIVE
import com.verlintas.baic2.floatball.advisor.data.GOAL_PROVISIONAL
import com.verlintas.baic2.floatball.advisor.data.GoalEntity
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L1
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L2
import com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity
import com.verlintas.baic2.floatball.advisor.strategy.SignalRules
import com.verlintas.baic2.floatball.advisor.strategy.StageGraph
import com.verlintas.baic2.floatball.advisor.strategy.StrategyContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 策略快车道的回合取景器（方案 §6.5/§8.1）：T1 并行取数之一，只读组装
 * [StrategyContext]，不做任何编译（慢车道归 [StrategyRuntime]）。
 *
 * 回退纪律：模式关 / 无联系人 / 无场景包 / 无阶段状态（该联系人从未跑过慢车道）
 * 一律返回 null，调用方完整回退 P1 链路。
 */
@Singleton
class StrategyBrief @Inject constructor(
    private val goals: GoalRepository,
    private val cards: StrategyCardRepository,
    private val contacts: ContactRepository,
    private val styleAssets: StyleAssets,
    private val prefs: AdvisorPrefs,
) {
    /**
     * @param context 策略段渲染原料。
     * @param provisionalL2 本回合应在分屏轻确认的观察期 L2（每回合静默 +1，
     *   两次沉默后由 Repository 自动归档；null=本回合无待确认项）。
     * @param stageCodes 本包全部站码（StyleResolver 跨站意图过滤用）。
     * @param suppressGoal true=已有生效 L2，抑制 P1 的【本次聊天的业务目标】行。
     */
    data class Snapshot(
        val context: StrategyContext,
        val provisionalL2: GoalEntity?,
        val stageCodes: Set<String>,
        val suppressGoal: Boolean,
    )

    /** P3：按联系人当前场景包（分叉确认等需在 assess 之外取包时用）。 */
    suspend fun pack(contactId: Long): ScenarioPack? {
        val profile = contacts.profile(contactId) ?: return null
        return styleAssets.scenarioPacks().byCode(profile.scenarioCode)
    }

    /**
     * @param incomingText 已提取的对方最新来信（空则用整屏兜底做红线扫描）。
     * @param countProvisionalPrompt 是否推进观察期 L2 的沉默计数。同一回合因 Judge
     *   跃迁而二次取景时必须传 false（只重新读阶段，不能把轻确认问句计两回合）。
     */
    suspend fun assess(
        contactId: Long,
        conversationId: Long?,
        incomingText: String,
        screenBody: String,
        countProvisionalPrompt: Boolean = true,
    ): Snapshot? {
        if (prefs.state.value.strategyMode == AdvisorPrefs.STRATEGY_OFF) return null
        val profile = contacts.profile(contactId) ?: return null
        val pack = styleAssets.scenarioPacks().byCode(profile.scenarioCode) ?: return null

        // 惰性清理：7 天未动的 active L1 归档（§4）。
        goals.archiveStaleL1(contactId)

        val stage = goals.stage(contactId, pack.code) ?: return null
        val stageDef = pack.stages.firstOrNull { it.code == stage.stageCode }

        val l2List = goals.activeGoals(contactId, HORIZON_L2)
        val activeL2 = l2List.firstOrNull { it.status == GOAL_ACTIVE }
        val activeL1 = activeL2?.let { parent ->
            goals.activeGoals(contactId, HORIZON_L1)
                .firstOrNull { it.status == GOAL_ACTIVE && it.parentId == parent.id }
        }
        // 观察期 L2 轻确认：每回合展示并 +1，两次沉默自动归档（Repository 内事务）。
        val provisionalL2 = if (countProvisionalPrompt) {
            goals.nextProvisionalL2Prompt(contactId)
        } else {
            // 二次取景：计数已在本回合第一次取景推进，这里只读当前观察期条目。
            l2List.firstOrNull { it.status == GOAL_PROVISIONAL }
        }

        val effectiveCard = runCatching { cards.effective(contactId) }.getOrNull()?.second
        val retreatHits = SignalRules.retreatHits(
            pack = pack,
            incomingText = incomingText.ifBlank { screenBody },
        )
        // P3 §12.2：24h 中性时序信号（无来信记录=无信号，不臆测；不参与边裁决）。
        val now = System.currentTimeMillis()
        val lastIncomingAt = conversationId
            ?.let { runCatching { contacts.lastIncomingAt(it) }.getOrNull() }
        val neutralSignal = SignalRules.silenceSignal(lastIncomingAt, now)
        val hoursSinceIncoming = SignalRules.hoursSinceIncoming(lastIncomingAt, now)
        // P3：激活版本路线图（null=v5 旧数据/无编译产物）→ 有向图运行时视图（含私有支线站）。
        val roadmap = goals.activeRoadmap(contactId, pack.code, activeL2?.id)
        val graph = StageGraph.resolve(pack, roadmap)
        val runtimeStage = graph.stage(stage.stageCode)
        val roadmapStageGoal = runtimeStage?.goal?.trim()?.takeIf { it.isNotEmpty() }
        val taboos = tabooLines(contactId)

        val ctx = StrategyContext(
            contactId = contactId,
            conversationId = conversationId,
            pack = pack,
            stage = stage,
            stageDef = stageDef,
            runtimeStage = runtimeStage,
            activeL2 = activeL2,
            activeL1 = activeL1,
            card = effectiveCard,
            retreatHits = retreatHits,
            neutralSignals = listOfNotNull(neutralSignal),
            hoursSinceIncoming = hoursSinceIncoming,
            roadmapStageGoal = roadmapStageGoal,
            roadmap = roadmap,
            theirTabooLines = taboos.first,
            myTabooLines = taboos.second,
        )
        return Snapshot(
            context = ctx,
            provisionalL2 = provisionalL2,
            stageCodes = pack.stages.map { it.code }.toSet(),
            suppressGoal = activeL2 != null,
        )
    }

    /** @return (对方/群雷区禁忌行, 我不能说的话行)，均只取已确认 textarea。 */
    private suspend fun tabooLines(contactId: Long): Pair<List<String>, List<String>> {
        val myEntities = contacts.myPersona().filter { it.confirmed }
        val theirEntities = contacts.fields(contactId).filter { it.confirmed }
        val theirLines = linesOf(theirEntities, FIELD_THEIR_TABOOS) +
            linesOf(theirEntities, FIELD_GROUP_TABOOS)
        return theirLines to linesOf(myEntities, FIELD_MY_TABOO)
    }

    private fun linesOf(entities: List<PersonaFieldEntity>, field: String): List<String> =
        entities.firstOrNull { it.field == field }?.value
            ?.split("\n", "；", ";", "、")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private companion object {
        const val FIELD_THEIR_TABOOS = "taboos"
        const val FIELD_GROUP_TABOOS = "groupTaboos"
        const val FIELD_MY_TABOO = "my_taboo"
    }
}
