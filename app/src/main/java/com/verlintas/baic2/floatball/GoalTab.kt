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
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.advisor.GoalOpResult
import com.verlintas.baic2.floatball.advisor.GoalRepository
import com.verlintas.baic2.floatball.advisor.GROUP_BUSINESS
import com.verlintas.baic2.floatball.advisor.GROUP_CONFLICT
import com.verlintas.baic2.floatball.advisor.GROUP_ROMANCE
import com.verlintas.baic2.floatball.advisor.GROUP_SOCIAL
import com.verlintas.baic2.floatball.advisor.GROUP_WORKPLACE
import com.verlintas.baic2.floatball.advisor.PATH_DEFEND
import com.verlintas.baic2.floatball.advisor.PATH_MAINTAIN
import com.verlintas.baic2.floatball.advisor.PersonaSchemas
import com.verlintas.baic2.floatball.advisor.PersonaValueCodes
import com.verlintas.baic2.floatball.advisor.PersonaValues
import com.verlintas.baic2.floatball.advisor.ScenarioPack
import com.verlintas.baic2.floatball.advisor.StyleAssets
import com.verlintas.baic2.floatball.advisor.StrategyCardRepository
import com.verlintas.baic2.floatball.advisor.data.ACTION_STATUS_ADOPTED
import com.verlintas.baic2.floatball.advisor.data.ACTION_STATUS_DONE
import com.verlintas.baic2.floatball.advisor.data.ACTION_STATUS_DRAFT
import com.verlintas.baic2.floatball.advisor.data.ACTION_STATUS_DROPPED
import com.verlintas.baic2.floatball.advisor.data.ActionPlayEntity
import com.verlintas.baic2.floatball.advisor.data.BRIEF_KIND_ON_DEMAND
import com.verlintas.baic2.floatball.advisor.data.BRIEF_KIND_WEEKLY
import com.verlintas.baic2.floatball.advisor.data.BriefOutputEntity
import com.verlintas.baic2.floatball.advisor.data.CARD_CONFIRMED
import com.verlintas.baic2.floatball.advisor.data.CARD_DRAFT
import com.verlintas.baic2.floatball.advisor.data.DECOMP_OUTCOMES
import com.verlintas.baic2.floatball.advisor.data.DECOMP_ROADMAP
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
import com.verlintas.baic2.floatball.advisor.data.MISSION_ADVANCED
import com.verlintas.baic2.floatball.advisor.data.MISSION_HELD
import com.verlintas.baic2.floatball.advisor.data.MISSION_IGNORED
import com.verlintas.baic2.floatball.advisor.data.MISSION_PENDING
import com.verlintas.baic2.floatball.advisor.data.MISSION_REGRESSED
import com.verlintas.baic2.floatball.advisor.data.PersonalMaterialEntity
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_ADVANCE
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_BRANCH
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_INIT
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_MANUAL
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_REROUTE
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_RETREAT
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_SKIP
import com.verlintas.baic2.floatball.advisor.data.StrategyCardEntity
import com.verlintas.baic2.floatball.advisor.strategy.OutcomesPayload
import com.verlintas.baic2.floatball.advisor.strategy.RerouteRules
import com.verlintas.baic2.floatball.advisor.strategy.RoadmapPayload
import com.verlintas.baic2.floatball.advisor.strategy.StageGraph
import com.verlintas.baic2.floatball.advisor.strategy.StagePolicy
import com.verlintas.baic2.floatball.advisor.strategy.TemplateFill
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import com.verlintas.baic2.floatball.brief.BriefCompiler
import com.verlintas.baic2.floatball.brief.BriefContent
import com.verlintas.baic2.floatball.director.ActionPlanResult
import com.verlintas.baic2.floatball.director.ActionPlanner
import com.verlintas.baic2.floatball.director.ActionPlayPayload
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * 联系人详情的「目标」Tab（P2 §11.2）：全局快捷卡 → L3 愿景（含修炼项与
 * L2 候选采纳）→ L2 中期目标（阶段进度条/观察期确认/路线图）→ L1 近期目标
 * （AI 建议只在此页出现，不弹分屏）→ 任务令台账 → 策略卡入口。
 *
 * 纯展示与写操作编排；所有重活（编译/编卡）经 [com.verlintas.baic2.floatball.advisor.strategy.StrategyRuntime] 异步入队。
 */
@Composable
internal fun GoalsTab(
    contactId: Long,
    initialGoalId: Long?,
    goals: GoalRepository,
    cards: StrategyCardRepository,
    assets: StyleAssets,
    contacts: ContactRepository,
    schemas: PersonaSchemas,
    runtime: com.verlintas.baic2.floatball.advisor.strategy.StrategyRuntime,
    briefs: BriefCompiler,
    plays: ActionPlanner,
    policies: com.verlintas.baic2.floatball.director.ProactivePolicyRepository,
    materials: com.verlintas.baic2.floatball.director.PersonalMaterialRepository,
    director: com.verlintas.baic2.floatball.director.Director,
    scroll: androidx.compose.foundation.ScrollState?,
    onOpenStrategyCard: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packs = remember { assets.scenarioPacks().packs }

    val profile by contacts.observeProfile(contactId)
        .collectAsStateWithLifecycle(initialValue = null)
    val allGoals by goals.observeGoals(contactId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val missions by goals.observeRecentMissions(contactId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val latestCard by cards.observeLatest(contactId)
        .collectAsStateWithLifecycle(initialValue = null)

    var aspiration by remember { mutableStateOf("") }
    LaunchedEffect(contactId) {
        val def = schemas.get().def("my_aspiration")
        val raw = contacts.myPersona().firstOrNull { it.confirmed && it.field == "my_aspiration" }?.value
        aspiration = raw?.let {
            PersonaValues.decodeTags(it)
                .map { tag -> def?.let { d -> PersonaValueCodes.displaySingle(d, tag).trim() } ?: tag }
                .filter { s -> s.isNotEmpty() }
                .joinToString("、")
        }.orEmpty()
    }

    val visibleStatuses = setOf(GOAL_ACTIVE, GOAL_PROVISIONAL, GOAL_REVIEW)
    val l3Goals = allGoals.filter { it.horizon == HORIZON_L3 && it.status in visibleStatuses }
    val l2Goals = allGoals.filter { it.horizon == HORIZON_L2 && it.status in visibleStatuses }
    val l1Goals = allGoals.filter { it.horizon == HORIZON_L1 && it.status in visibleStatuses }

    // F7 action_hint 消费（方案 §5.6：UI 只在用户打开 GoalTab 时消费）：查新鲜度
    // 窗口内最近一条 actionHint 决策行，命中则出行动建议横幅并滚动定位到下方
    // 行动建议卡；窗口对齐触发阈值（驻留解除后旧横幅不复活）。
    var actionHinted by remember(contactId) { mutableStateOf(false) }
    LaunchedEffect(contactId) {
        val since = System.currentTimeMillis() -
            com.verlintas.baic2.floatball.director.DirectorRoutes.ACTION_HINT_DWELL_DAYS * DAY_MS
        actionHinted = director.latestActionHint(contactId, since) != null
    }
    // 行动建议卡分区在滚动容器内的纵坐标（横幅点击滚动定位用）。
    var playsTopY by remember { mutableStateOf(0f) }

    var createHorizon by remember { mutableStateOf<Int?>(null) }
    var editingGoal by remember { mutableStateOf<GoalEntity?>(null) }
    var initialEditConsumed by remember(contactId) { mutableStateOf(false) }
    LaunchedEffect(initialGoalId, allGoals) {
        if (!initialEditConsumed && initialGoalId != null && initialGoalId > 0) {
            val target = allGoals.firstOrNull { it.id == initialGoalId }
            if (target != null) {
                editingGoal = target
                initialEditConsumed = true
            }
        }
    }

    // ① 全局快捷卡
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clickable(onClick = onOpenSettings),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.float_goal_global_quick),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.float_goal_global_quick_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ①.2 双方人设与维持度（v10，UltraAdvisor v2.2 §2.4/§2.5/§2.6，VA-2）：
    // 对方风格三标签 + 我方关系侧面（含合并差异核阅）+ 维持度三档 + 群标识 + 手动合并。
    // 区块实现独立成 AdvisorPersonaCard.kt，本文件只挂载（追加式，不动既有行）。
    AdvisorPersonaCard(contactId = contactId, profile = profile, contacts = contacts)

    // ①.3 该联系人的会话列表已升为画像页平级「参谋会话」Tab（§1.5，
    // 2026-10-22 重构）：搜索 + 深链 + 新建/关联，见 AdvisorSessionsTab.kt。

    // ①.5 行动建议横幅（F7 action_hint 消费面）：Director 台账旁路信号命中时
    // 提示阶段驻留较久、引导到下方行动建议卡；点击按「同帧根坐标差」滚动定位
    // （目标 = 行动卡滚到横幅当前视口位；无滚动态时仅展示）。
    if (actionHinted) {
        var bannerTopY by remember { mutableStateOf(0f) }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .onGloballyPositioned { coords -> bannerTopY = coords.boundsInRoot().top }
                .clickable {
                    val target = scroll
                    if (target != null) {
                        val delta = (playsTopY - bannerTopY).toInt()
                        scope.launch { target.animateScrollTo(target.value + delta) }
                    }
                },
        ) {
            Text(
                text = stringResource(R.string.float_action_hint_banner),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }

    // ② 远期愿景 L3
    GoalSectionHeader(
        title = stringResource(R.string.float_goal_section_l3),
        actionLabel = stringResource(R.string.float_goal_new_l3),
        actionEnabled = l3Goals.size < GoalRepository.CAP_L3,
        onAction = { createHorizon = HORIZON_L3 },
    )
    if (l3Goals.isEmpty()) {
        GoalEmptyHint(stringResource(R.string.float_goal_empty_l3))
    }
    l3Goals.forEach { goal ->
        L3GoalCard(
            goal = goal,
            goals = goals,
            runtime = runtime,
            existingL2Titles = l2Goals.map { it.title }.toSet(),
            onEdit = { editingGoal = goal },
        )
    }

    // ③ 中期目标 L2
    GoalSectionHeader(
        title = stringResource(R.string.float_goal_section_l2),
        actionLabel = stringResource(R.string.float_goal_new_l2),
        actionEnabled = l2Goals.size < GoalRepository.CAP_L2,
        onAction = { createHorizon = HORIZON_L2 },
    )
    if (l2Goals.isEmpty()) {
        GoalEmptyHint(stringResource(R.string.float_goal_empty_l2))
    }
    l2Goals.forEach { goal ->
        L2GoalCard(
            goal = goal,
            contactId = contactId,
            goals = goals,
            packs = packs,
            runtime = runtime,
            onEdit = { editingGoal = goal },
        )
    }

    // ④ 近期目标 L1
    val canCreateL1 = l2Goals.any { it.status == GOAL_ACTIVE }
    GoalSectionHeader(
        title = stringResource(R.string.float_goal_section_l1),
        actionLabel = stringResource(R.string.float_goal_new_l1),
        actionEnabled = canCreateL1 && l1Goals.size < GoalRepository.CAP_L1,
        onAction = { createHorizon = HORIZON_L1 },
    )
    if (l1Goals.isEmpty()) {
        GoalEmptyHint(
            if (canCreateL1) {
                stringResource(R.string.float_goal_empty_l1)
            } else {
                stringResource(R.string.float_goal_parent_required)
            },
        )
    }
    l1Goals.forEach { goal ->
        L1GoalCard(goal = goal, goals = goals)
    }

    // ⑤ 任务令台账
    Text(
        text = stringResource(R.string.float_mission_ledger),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
    if (missions.isEmpty()) {
        GoalEmptyHint(stringResource(R.string.float_mission_ledger_empty))
    }
    missions.forEach { mission ->
        MissionLedgerRow(mission)
    }

    // ⑤.5 参谋简报（M0 BriefCompiler 慢车道汇总：阶段位置 + 事件/进展/风险/下一步）
    AdvisorBriefCard(contactId = contactId, briefs = briefs)

    // ⑤.6 行动建议（M1 ActionPlanner：谋略库按阶段选条定制，draft 落库，用户采纳驱动）。
    // onGloballyPositioned 记录分区根坐标：①.5 行动建议横幅点击滚动定位用。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> playsTopY = coords.boundsInRoot().top },
    ) {
        AdvisorPlaysCard(contactId = contactId, planner = plays)
    }

    // ⑤.7 主动保温（M2-b：联系人级 ProactivePolicy 开关与频控三参数，周期检点消费）
    AdvisorProactiveCard(contactId = contactId, policies = policies)

    // ⑤.8 我的素材（F6，M1 方案 §7.2 素材三源之②「用户投喂」：保温选题的鲜活源）
    AdvisorMaterialsCard(materials = materials)

    // ⑥ 策略卡入口
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clickable(onClick = onOpenStrategyCard),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.float_goal_card_entry),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = cardSubtitle(latestCard),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.float_goal_edit),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(4.dp),
            )
        }
    }

    createHorizon?.let { horizon ->
        GoalEditDialog(
            contactId = contactId,
            existing = null,
            initialHorizon = horizon,
            packs = packs,
            defaultScenario = profile?.scenarioCode,
            parentL2Options = l2Goals.filter { it.status == GOAL_ACTIVE },
            contactName = profile?.displayName.orEmpty(),
            aspiration = aspiration,
            goals = goals,
            runtime = runtime,
            onDismiss = { createHorizon = null },
        )
    }
    editingGoal?.let { goal ->
        GoalEditDialog(
            contactId = contactId,
            existing = goal,
            initialHorizon = goal.horizon,
            packs = packs,
            defaultScenario = goal.scenarioCode ?: profile?.scenarioCode,
            parentL2Options = l2Goals,
            contactName = profile?.displayName.orEmpty(),
            aspiration = aspiration,
            goals = goals,
            runtime = runtime,
            onDismiss = { editingGoal = null },
        )
    }
}

@Composable
private fun cardSubtitle(card: StrategyCardEntity?): String = when {
    card == null || card.status == com.verlintas.baic2.floatball.advisor.data.CARD_ARCHIVED ->
        stringResource(R.string.float_goal_card_none)
    card.status == CARD_DRAFT -> stringResource(R.string.float_goal_card_draft, card.version)
    card.status == CARD_CONFIRMED -> stringResource(R.string.float_goal_card_confirmed, card.version)
    else -> stringResource(R.string.float_goal_card_none)
}

@Composable
private fun GoalSectionHeader(
    title: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = if (actionEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable(enabled = actionEnabled, onClick = onAction)
                .padding(4.dp),
        )
    }
}

@Composable
private fun GoalEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

// ------------------------------------------------------------ L3

@Composable
private fun L3GoalCard(
    goal: GoalEntity,
    goals: GoalRepository,
    runtime: com.verlintas.baic2.floatball.advisor.strategy.StrategyRuntime,
    existingL2Titles: Set<String>,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decomp by goals.observeLatestDecomp(goal.id, DECOMP_OUTCOMES)
        .collectAsStateWithLifecycle(initialValue = null)
    val outcomes = remember(decomp?.payloadJson) {
        decomp?.payloadJson?.let { json ->
            runCatching { strategyJson.decodeFromString(OutcomesPayload.serializer(), json) }.getOrNull()
        }
    }
    GoalCardSurface {
        GoalCardTitleRow(
            title = goal.title,
            badges = listOfNotNull(provisionalBadge(goal)),
        )
        goal.progressNote?.takeIf { it.isNotBlank() }?.let {
            GoalSmallText(stringResource(R.string.float_goal_progress_note, it))
        }
        outcomes?.practices?.takeIf { it.isNotEmpty() }?.let { practices ->
            Text(
                text = stringResource(R.string.float_goal_practices),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            practices.forEach { line ->
                Text("· $line", style = MaterialTheme.typography.bodySmall)
            }
        }
        val candidates = outcomes?.l2
            ?.filter { it.title.isNotBlank() && it.title.trim() !in existingL2Titles }
            .orEmpty()
        if (candidates.isNotEmpty()) {
            Text(
                text = stringResource(R.string.float_goal_outcomes_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            candidates.forEach { cand ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(cand.title, style = MaterialTheme.typography.bodyMedium)
                        cand.successCriteria.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            val now = System.currentTimeMillis()
                            val days = cand.deadlineDays
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
                                runtime.requestGoalCompile(result.id)
                            } else {
                                toastGoalResult(context, result, HORIZON_L2)
                            }
                        }
                    }) { Text(stringResource(R.string.float_goal_adopt_candidate)) }
                }
            }
        }
        if (outcomes == null) {
            GoalSmallText(stringResource(R.string.float_goal_decomp_working))
        }
        GoalActionRow(
            status = goal.status,
            isProvisional = goal.status == GOAL_PROVISIONAL,
            onEdit = onEdit,
            onConfirm = null,
            onDone = { scope.launch { goals.setDone(goal.id) } },
            onArchive = { scope.launch { goals.setAbandoned(goal.id) } },
            onReactivate = null,
            onRecompile = { runtime.requestGoalCompile(goal.id, force = true) },
        )
    }
}

// ------------------------------------------------------------ L2

@Composable
private fun L2GoalCard(
    goal: GoalEntity,
    contactId: Long,
    goals: GoalRepository,
    packs: List<ScenarioPack>,
    runtime: com.verlintas.baic2.floatball.advisor.strategy.StrategyRuntime,
    onEdit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pack = packs.firstOrNull { it.code == goal.scenarioCode }
    val roadmapDecomp by goals.observeLatestDecomp(goal.id, DECOMP_ROADMAP)
        .collectAsStateWithLifecycle(initialValue = null)
    val roadmap = remember(roadmapDecomp?.payloadJson) {
        roadmapDecomp?.payloadJson?.let { json ->
            runCatching { strategyJson.decodeFromString(RoadmapPayload.serializer(), json) }.getOrNull()
        }
    }

    GoalCardSurface {
        GoalCardTitleRow(
            title = goal.title,
            badges = buildList {
                provisionalBadge(goal)?.let { add(it) }
                if (goal.status == GOAL_REVIEW) add(stringResource(R.string.float_goal_badge_review))
            },
        )
        goal.successCriteria?.takeIf { it.isNotBlank() }?.let {
            GoalSmallText(it)
        }
        goal.deadlineAt?.let {
            GoalSmallText(stringResource(R.string.float_goal_deadline_label, formatDate(it)))
        }
        goal.progressNote?.takeIf { it.isNotBlank() }?.let {
            GoalSmallText(stringResource(R.string.float_goal_progress_note, it))
        }

        if (pack != null) {
            StageProgressBar(
                contactId = contactId,
                pack = pack,
                goals = goals,
                roadmap = roadmap,
                roadmapDecomp = roadmapDecomp,
                goalId = goal.id,
                runtime = runtime,
                failStreak = goal.failStreak,
            )
        }
        roadmap?.practices?.takeIf { it.isNotEmpty() }?.let { practices ->
            Text(
                text = stringResource(R.string.float_goal_roadmap_practices),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            practices.forEach { line ->
                Text("· $line", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (roadmap == null) {
            GoalSmallText(stringResource(R.string.float_goal_decomp_working))
        }

        GoalActionRow(
            status = goal.status,
            isProvisional = goal.status == GOAL_PROVISIONAL,
            onEdit = onEdit,
            onConfirm = {
                scope.launch {
                    goals.confirmProvisionalL2(goal.id)
                    runtime.requestCardCompile(contactId)
                }
            },
            onDone = { scope.launch { goals.setDone(goal.id) } },
            onArchive = { scope.launch { goals.setAbandoned(goal.id) } },
            onReactivate = if (goal.status == GOAL_REVIEW) {
                { scope.launch { goals.reactivate(goal.id) } }
            } else {
                null
            },
            onRecompile = { runtime.requestGoalCompile(goal.id, force = true) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageProgressBar(
    contactId: Long,
    pack: ScenarioPack,
    goals: GoalRepository,
    /** 最新 decomp 解析出的路线（reroute 草稿期间是「新版」，不等于当前激活版）。 */
    roadmap: RoadmapPayload?,
    roadmapDecomp: GoalDecompEntity?,
    goalId: Long,
    runtime: com.verlintas.baic2.floatball.advisor.strategy.StrategyRuntime,
    failStreak: Int,
) {
    val scope = rememberCoroutineScope()
    val state by goals.observeStage(contactId, pack.code)
        .collectAsStateWithLifecycle(initialValue = null)
    // P3：画图永远用「激活版本」（stage_states.roadmapVersion 钉住的旧版；草稿不抢显示）。
    var activeRoadmap by remember(goalId, state?.roadmapVersion) {
        mutableStateOf<RoadmapPayload?>(null)
    }
    LaunchedEffect(goalId, state?.roadmapVersion, roadmapDecomp?.id) {
        activeRoadmap = if (state?.roadmapVersion != null) {
            goals.activeRoadmap(contactId, pack.code, goalId)
        } else {
            roadmap
        }
    }
    val graph = remember(pack, activeRoadmap) { StageGraph.resolve(pack, activeRoadmap) }
    val history by goals.observeStageHistory(contactId, pack.code, HISTORY_SHOW)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var pickerOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }

    // P3 reroute：最新 decomp 是未确认新版 → 黄条待确认（迁站目标按 stageMapping 预算）。
    val newGraph = remember(roadmapDecomp?.id, roadmap) { StageGraph.resolve(pack, roadmap) }
    val reroutePending = state?.let { s ->
        roadmapDecomp
            ?.takeIf { d -> RerouteRules.isPending(s.roadmapVersion, d.version, d.confirmed) }
            ?.let { d ->
                val targetCode = RerouteRules.targetCode(s.stageCode, newGraph, roadmap?.stageMapping.orEmpty())
                Triple(d, targetCode, newGraph.stage(targetCode)?.label ?: targetCode)
            }
    }
    val rerouteBusy = remember { mutableStateOf(false) }
    fun triggerReroute() {
        if (rerouteBusy.value || reroutePending != null) return
        rerouteBusy.value = true
        runtime.requestReroute(goalId) { _ -> rerouteBusy.value = false }
    }

    if (reroutePending != null) {
        val (decomp, _, targetLabel) = reroutePending
        GoalSmallText(stringResource(R.string.float_reroute_pending, targetLabel))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = {
                scope.launch { runtime.acceptReroute(goalId, decomp.id) }
            }) { Text(stringResource(R.string.float_reroute_accept)) }
            OutlinedButton(onClick = {
                scope.launch { runtime.rejectReroute(decomp.id) }
            }) { Text(stringResource(R.string.float_reroute_reject)) }
        }
    }

    Text(
        text = stringResource(R.string.float_stage_progress),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
    val currentIndex = pack.stages.indexOfFirst { it.code == state?.stageCode }.let { if (it < 0) 0 else it }
    val onBackbone = state != null && pack.stages.any { it.code == state?.stageCode }
    val terminalReached = onBackbone && currentIndex == pack.stages.lastIndex &&
        pack.pathKind in setOf(PATH_MAINTAIN, PATH_DEFEND)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        pack.stages.forEachIndexed { index, stage ->
            val isCurrent = onBackbone && index == currentIndex
            val isPast = onBackbone && index < currentIndex
            val label = when {
                isCurrent && terminalReached -> "✓ ${stage.label}"
                isPast -> "✓ ${stage.label}"
                isCurrent && state?.locked == true -> "🔒 ${stage.label}"
                else -> stage.label
            }
            FilterChip(
                selected = isCurrent,
                onClick = { pickerOpen = true },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
    // P3 私有支线站：随当前 roadmap 版本走，虚线感用次级颜色区分。
    if (graph.privates.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            graph.privates.forEach { p ->
                val isCurrent = state?.stageCode == p.code
                FilterChip(
                    selected = isCurrent,
                    onClick = { pickerOpen = true },
                    label = {
                        Text(
                            "〽 ${p.label}" + if (isCurrent && state?.locked == true) " 🔒" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    },
                )
            }
        }
    }
    // 锁定/解锁：锁定中自动边（推进/跳站/回撤/分叉）不得移动本站。
    state?.let { s ->
        Text(
            text = if (s.locked) "🔒 " + stringResource(R.string.float_stage_locked) else "🔓",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable {
                    scope.launch { goals.setStageLocked(contactId, pack.code, !s.locked) }
                },
        )
    }
    if (terminalReached) {
        GoalSmallText(
            stringResource(
                if (pack.pathKind == PATH_DEFEND) {
                    R.string.float_stage_terminal_defend
                } else {
                    R.string.float_stage_terminal
                },
            ),
        )
    }
    state?.evidence?.takeIf { it.isNotBlank() }?.let { evidence ->
        GoalSmallText(stringResource(R.string.float_stage_evidence, evidence))
    }
    state?.let { s ->
        graph.stage(s.stageCode)?.goal?.takeIf { it.isNotBlank() }?.let {
            GoalSmallText(stringResource(R.string.float_stage_roadmap_goal, it))
        }
    }

    // P3 reroute 触发 1/3：连续受挫 ≥3（黄条口径）或本站停留超 expectedDays×1.5。
    // 全部只出按钮，绝不静默重规划；有待确认草稿时不重复触发。
    if (reroutePending == null) {
        val s = state
        if (s != null) {
            val expectedDays = StageGraph.expectedDaysOf(graph, s.stageCode)
            val overdue = System.currentTimeMillis() - s.enteredAt >
                expectedDays * REROUTE_TIMEOUT_FACTOR_NUM / REROUTE_TIMEOUT_FACTOR_DEN * DAY_MS
            if (overdue) {
                Text(
                    text = stringResource(R.string.float_reroute_timeout),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (overdue || failStreak >= GoalRepository.FAIL_STREAK_REVIEW) {
                val busy = rerouteBusy.value
                OutlinedButton(
                    onClick = { triggerReroute() },
                    enabled = !busy,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(stringResource(if (busy) R.string.float_reroute_busy else R.string.float_reroute_btn))
                }
            }
        }
    }

    // 历程（折叠，最近 5 条）。
    Text(
        text = stringResource(R.string.float_stage_history_title) + "（${history.size}）",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 6.dp)
            .clickable { historyOpen = !historyOpen },
    )
    if (historyOpen) {
        if (history.isEmpty()) {
            GoalSmallText(stringResource(R.string.float_stage_history_empty))
        } else {
            history.forEach { e ->
                val from = e.fromCode?.let { graph.stage(it)?.label ?: it }
                val to = graph.stage(e.toCode)?.label ?: e.toCode
                val arrow = from?.let { "$it → $to" } ?: to
                GoalSmallText("${edgeLabel(e.edge)} · $arrow · ${relativeTime(e.at)}${e.reason?.takeIf { r -> r.isNotBlank() }?.let { " · $it" }.orEmpty()}")
            }
        }
    }

    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text(stringResource(R.string.float_stage_pick_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    (graph.backbone + graph.privates).forEach { stage ->
                        val isCurrent = stage.code == state?.stageCode
                        Text(
                            text = (if (isCurrent) "● " else "○ ") + stage.label +
                                if (isCurrent) "（${stringResource(R.string.float_stage_current)}）" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickerOpen = false
                                    val current = state
                                    if (current != null && !isCurrent) {
                                        scope.launch {
                                            val now = System.currentTimeMillis()
                                            // P3：手动改站同步支线标记、加锁（防自动边立刻拉走，用户可点锁解除）。
                                            val moved = current.copy(
                                                stageCode = stage.code,
                                                enteredAt = now,
                                                advanceHits = "{}",
                                                evidence = StagePolicy.MANUAL_EVIDENCE,
                                                pathTag = stage.pathTag,
                                                locked = true,
                                            )
                                            goals.commitStage(
                                                state = moved,
                                                edge = STAGE_EDGE_MANUAL,
                                                reason = StagePolicy.MANUAL_EVIDENCE,
                                                from = current,
                                                now = now,
                                            )
                                        }
                                    }
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) {
                    Text(stringResource(R.string.float_profile_cancel))
                }
            },
        )
    }
}

/** 阶段历程在 GoalTab 默认折叠展示的条数。 */
private const val HISTORY_SHOW = 5

/** 简报卡每分组展示条数上限。 */
private const val BRIEF_ITEM_SHOW = 3

/** 简报卡每条展示字符上限（超长省略）。 */
private const val BRIEF_ITEM_MAX = 80

/** P3 reroute 超时因子：停留 > expectedDays × 3/2 提示重规划。 */
private const val REROUTE_TIMEOUT_FACTOR_NUM = 3L
private const val REROUTE_TIMEOUT_FACTOR_DEN = 2L

/** stage_history.edge → 中文标签。 */
@Composable
private fun edgeLabel(edge: String): String = stringResource(
    when (edge) {
        STAGE_EDGE_INIT -> R.string.float_stage_edge_init
        STAGE_EDGE_ADVANCE -> R.string.float_stage_edge_advance
        STAGE_EDGE_BRANCH -> R.string.float_stage_edge_branch
        STAGE_EDGE_RETREAT -> R.string.float_stage_edge_retreat
        STAGE_EDGE_SKIP -> R.string.float_stage_edge_skip
        STAGE_EDGE_MANUAL -> R.string.float_stage_edge_manual
        STAGE_EDGE_REROUTE -> R.string.float_stage_edge_reroute
        else -> R.string.float_stage_edge_advance
    },
)

/** 极简相对时间（历程用）：今天内显示 x 小时前，更早显示 x 天前（W3：文案走资源，复用 mission 时间键）。 */
@Composable
private fun relativeTime(at: Long): String {
    val diffMs = System.currentTimeMillis() - at
    val hours = diffMs / 3_600_000L
    val days = diffMs / 86_400_000L
    return when {
        days >= 1 -> stringResource(R.string.float_mission_time_day, days.toInt())
        hours >= 1 -> stringResource(R.string.float_mission_time_hour, hours.toInt())
        else -> stringResource(R.string.float_mission_time_justnow)
    }
}

// ------------------------------------------------------------ L1

@Composable
private fun L1GoalCard(goal: GoalEntity, goals: GoalRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isProvisional = goal.status == GOAL_PROVISIONAL
    // P3 §十：Judge 高置信完成建议（内存态，仅 active L1 显示；绝不自动勾选）。
    val completionHints by goals.l1CompletionHints.collectAsStateWithLifecycle(initialValue = emptyList())
    val hint = completionHints.firstOrNull { it.goalId == goal.id }
    GoalCardSurface {
        if (!isProvisional && hint != null) {
            L1CompletionHintBar(
                title = goal.title,
                evidence = hint.evidence,
                onComplete = { scope.launch { goals.completeSuggestedL1(goal.id) } },
                onDismiss = { scope.launch { goals.dismissL1Completion(goal.id) } },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                GoalCardTitleRow(
                    title = goal.title,
                    badges = buildList {
                        if (isProvisional) add(stringResource(R.string.float_goal_badge_ai))
                        provisionalBadge(goal)?.let { add(it) }
                    },
                )
                goal.successCriteria?.takeIf { it.isNotBlank() }?.let { GoalSmallText(it) }
                goal.deadlineAt?.let {
                    GoalSmallText(stringResource(R.string.float_goal_deadline_label, formatDate(it)))
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            if (isProvisional) {
                Button(onClick = {
                    scope.launch {
                        val result = goals.adoptProvisionalL1(goal.id)
                        if (result !is GoalOpResult.Ok) {
                            toastGoalResult(context, result, HORIZON_L1)
                        }
                    }
                }) { Text(stringResource(R.string.float_goal_adopt)) }
                OutlinedButton(onClick = { scope.launch { goals.setAbandoned(goal.id) } }) {
                    Text(stringResource(R.string.float_goal_ignore))
                }
            } else {
                Button(onClick = { scope.launch { goals.setDone(goal.id) } }) {
                    Text(stringResource(R.string.float_goal_done))
                }
                OutlinedButton(onClick = { scope.launch { goals.setAbandoned(goal.id) } }) {
                    Text(stringResource(R.string.float_goal_archive))
                }
            }
        }
    }
}

// ------------------------------------------------------------ ledger

@Composable
private fun L1CompletionHintBar(
    title: String,
    evidence: String,
    onComplete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.float_l1_complete_hint, title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (evidence.isNotBlank()) {
                Text(
                    text = evidence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Button(onClick = onComplete) {
                    Text(stringResource(R.string.float_l1_complete_mark))
                }
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.float_l1_complete_not_yet))
                }
            }
        }
    }
}

@Composable
private fun MissionLedgerRow(mission: com.verlintas.baic2.floatball.advisor.data.TalkMissionEntity) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = missionResultLabel(mission.result),
                    style = MaterialTheme.typography.labelSmall,
                    color = missionResultColor(mission.result),
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = relativeMissionTime(mission.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = mission.mission, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun missionResultLabel(result: String): String = when (result) {
    MISSION_ADVANCED -> stringResource(R.string.float_mission_result_advanced)
    MISSION_HELD -> stringResource(R.string.float_mission_result_held)
    MISSION_REGRESSED -> stringResource(R.string.float_mission_result_regressed)
    MISSION_IGNORED -> stringResource(R.string.float_mission_result_ignored)
    else -> stringResource(R.string.float_mission_result_pending)
}

@Composable
private fun missionResultColor(result: String) = when (result) {
    MISSION_ADVANCED -> MaterialTheme.colorScheme.primary
    MISSION_REGRESSED -> MaterialTheme.colorScheme.error
    MISSION_PENDING -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ------------------------------------------------------------ brief card（M0 参谋简报）

/**
 * 参谋简报卡（方案 §10 M0）：展示最新一份 weekly/onDemand 简报；
 * 首次进入惰性检查 weekly 新鲜度（超 7 天静默刷新一次，失败不打扰）；
 * 「生成简报」按需编译，窗口内无阶段/任务数据时给空态不调 LLM。
 */
@Composable
private fun AdvisorBriefCard(contactId: Long, briefs: BriefCompiler) {
    val scope = rememberCoroutineScope()
    var weekly by remember(contactId) { mutableStateOf<BriefOutputEntity?>(null) }
    var onDemand by remember(contactId) { mutableStateOf<BriefOutputEntity?>(null) }
    var busy by remember(contactId) { mutableStateOf(false) }
    var failed by remember(contactId) { mutableStateOf(false) }
    var noData by remember(contactId) { mutableStateOf(false) }

    LaunchedEffect(contactId) {
        weekly = briefs.latest(contactId, BRIEF_KIND_WEEKLY)
        onDemand = briefs.latest(contactId, BRIEF_KIND_ON_DEMAND)
        // 惰性周报刷新：静默进行，成功才刷新显示。
        briefs.refreshWeeklyIfStale(contactId)?.let { weekly = it }
    }

    val shown = listOfNotNull(weekly, onDemand).maxByOrNull { it.createdAt }
    GoalCardSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.float_brief_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            shown?.let {
                Text(
                    text = stringResource(
                        if (it.kind == BRIEF_KIND_WEEKLY) {
                            R.string.float_brief_kind_weekly
                        } else {
                            R.string.float_brief_kind_on_demand
                        },
                    ) + " · " + relativeMissionTime(it.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val content = shown?.let { entity ->
            remember(entity.contentJson) {
                runCatching {
                    strategyJson.decodeFromString(BriefContent.serializer(), entity.contentJson)
                }.getOrNull()
            }
        }
        if (content != null) {
            content.stagePosition.takeIf { it.isNotBlank() }?.let {
                GoalSmallText(stringResource(R.string.float_brief_stage_position, it))
            }
            BriefSection(stringResource(R.string.float_brief_recent_events), content.recentEvents)
            BriefSection(stringResource(R.string.float_brief_goal_progress), content.goalProgress)
            BriefSection(stringResource(R.string.float_brief_risks), content.risks)
            BriefSection(stringResource(R.string.float_brief_next_moves), content.nextMoves)
            // V-C §4.5：下周期废话方向（topic_plans latest，空表不渲染）。
            BriefSection(stringResource(R.string.float_brief_topic_directions), content.topicDirections)
        } else if (busy) {
            GoalSmallText(stringResource(R.string.float_brief_generating))
        } else if (noData) {
            GoalSmallText(stringResource(R.string.float_brief_empty_no_data))
        } else if (failed) {
            GoalSmallText(stringResource(R.string.float_brief_failed))
        } else {
            GoalSmallText(stringResource(R.string.float_brief_empty))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            OutlinedButton(onClick = {
                scope.launch {
                    busy = true
                    failed = false
                    noData = false
                    if (!briefs.hasRecentActivity(contactId)) {
                        noData = true
                    } else {
                        val result = briefs.compile(contactId, BRIEF_KIND_ON_DEMAND)
                        if (result != null) {
                            onDemand = result
                        } else {
                            failed = true
                        }
                    }
                    busy = false
                }
            }, enabled = !busy) {
                Text(stringResource(R.string.float_brief_generate))
            }
        }
    }
}

/** 简报分组小节：空组不渲染；每条截前 [BRIEF_ITEM_MAX] 字、最多 [BRIEF_ITEM_SHOW] 条。 */
@Composable
private fun BriefSection(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
    items.take(BRIEF_ITEM_SHOW).forEach { raw ->
        val clipped = raw.trim().takeIf { it.isNotEmpty() } ?: return@forEach
        val text = if (clipped.length > BRIEF_ITEM_MAX) clipped.take(BRIEF_ITEM_MAX) + "…" else clipped
        Text("· $text", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 行动建议卡（M1 方案 §10 M1-B）：ActionPlanner 慢车道产物展示 + 四态操作。
 * 手动触发（「给点子」），draft→采纳/放弃、adopted→完成/放弃，done/dropped 终态
 * 不再出操作；状态变更经 [ActionPlanner] 走 ActionPlayDao.updateStatus 后整表刷新。
 */
@Composable
private fun AdvisorPlaysCard(contactId: Long, planner: ActionPlanner) {
    val scope = rememberCoroutineScope()
    var items by remember(contactId) { mutableStateOf<List<ActionPlayEntity>>(emptyList()) }
    var busy by remember(contactId) { mutableStateOf(false) }
    var failed by remember(contactId) { mutableStateOf(false) }
    var noCandidates by remember(contactId) { mutableStateOf(false) }

    fun refresh() {
        scope.launch { items = planner.recent(contactId) }
    }

    LaunchedEffect(contactId) {
        items = planner.recent(contactId)
    }

    GoalCardSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.float_action_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        failed = false
                        noCandidates = false
                        when (planner.plan(contactId)) {
                            is ActionPlanResult.Done -> items = planner.recent(contactId)
                            ActionPlanResult.NoCandidates -> noCandidates = true
                            ActionPlanResult.Failed -> failed = true
                        }
                        busy = false
                    }
                },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.float_action_generate))
            }
        }
        if (busy) GoalSmallText(stringResource(R.string.float_action_generating))
        if (noCandidates) GoalSmallText(stringResource(R.string.float_action_no_candidates))
        if (failed) GoalSmallText(stringResource(R.string.float_action_failed))
        if (items.isEmpty() && !busy && !failed && !noCandidates) {
            GoalSmallText(stringResource(R.string.float_action_empty))
        }
        items.forEach { play ->
            ActionPlayRow(play = play, planner = planner, onChanged = { refresh() })
        }
    }
}

/** 单条行动建议行：谋略名/路径摘要/代价/风险/步骤 + 状态徽标 + 按状态出操作。 */
@Composable
private fun ActionPlayRow(play: ActionPlayEntity, planner: ActionPlanner, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val payload = remember(play.payloadJson) {
        runCatching {
            strategyJson.decodeFromString(ActionPlayPayload.serializer(), play.payloadJson)
        }.getOrNull()
    }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = payload?.title?.takeIf { it.isNotBlank() } ?: play.stratagemCode.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            GoalBadge(actionStatusBadge(play.status))
        }
        payload?.rationale?.takeIf { it.isNotBlank() }?.let { GoalSmallText(it) }
        payload?.cost?.takeIf { it.isNotBlank() }?.let {
            GoalSmallText(stringResource(R.string.float_action_cost, it))
        }
        payload?.risk?.takeIf { it.isNotBlank() }?.let {
            GoalSmallText(stringResource(R.string.float_action_risk, it))
        }
        payload?.prereq?.takeIf { it.isNotBlank() }?.let {
            GoalSmallText(stringResource(R.string.float_action_prereq, it))
        }
        val steps = payload?.steps.orEmpty()
        steps.take(ACTION_STEPS_SHOW).forEach { step ->
            Text(
                text = "· $step",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        when (play.status) {
            ACTION_STATUS_DRAFT -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(onClick = {
                    scope.launch {
                        planner.setStatus(play.id, ACTION_STATUS_ADOPTED)
                        onChanged()
                    }
                }) { Text(stringResource(R.string.float_action_adopt)) }
                OutlinedButton(onClick = {
                    scope.launch {
                        planner.setStatus(play.id, ACTION_STATUS_DROPPED)
                        onChanged()
                    }
                }) { Text(stringResource(R.string.float_action_drop)) }
            }
            ACTION_STATUS_ADOPTED -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(onClick = {
                    scope.launch {
                        planner.setStatus(play.id, ACTION_STATUS_DONE)
                        onChanged()
                    }
                }) { Text(stringResource(R.string.float_action_done)) }
                OutlinedButton(onClick = {
                    scope.launch {
                        planner.setStatus(play.id, ACTION_STATUS_DROPPED)
                        onChanged()
                    }
                }) { Text(stringResource(R.string.float_action_drop)) }
            }
        }
    }
}

/** 状态徽标文案映射（未知状态按 draft 处理，不崩）。 */
@Composable
private fun actionStatusBadge(status: String): String = stringResource(
    when (status) {
        ACTION_STATUS_ADOPTED -> R.string.float_action_status_adopted
        ACTION_STATUS_DONE -> R.string.float_action_status_done
        ACTION_STATUS_DROPPED -> R.string.float_action_status_dropped
        else -> R.string.float_action_status_draft
    },
)

/** 单条建议最多展示的步骤行数（落库上限 4 条，展示同额）。 */
private const val ACTION_STEPS_SHOW = 4

// ------------------------------------------------------------ proactive card（M2-b 主动保温）

/** 每日主动上限预设（条）。 */
private val PROACTIVE_CAP_OPTIONS = listOf(1, 2, 3)

/** 两次主动最小间隔预设（小时）。 */
private val PROACTIVE_GAP_OPTIONS = listOf(12, 24, 48, 72)

/** 静默开始/结束预设（HH:mm，编辑层只出合法值，跨午夜由规则表判定）。 */
private val PROACTIVE_QUIET_START_OPTIONS = listOf("20:00", "21:00", "22:00", "22:30", "23:30")
private val PROACTIVE_QUIET_END_OPTIONS = listOf("06:00", "07:00", "08:00", "08:30", "09:30")

/** 参数选择弹窗的目标项。 */
private enum class ProactivePick { CAP, GAP, QUIET_START, QUIET_END }

/**
 * 主动保温卡（M2-b，方案 §6 表四 proactive_policies）：联系人级开关 +
 * dailyCap/minGapHours/quiet 时段编辑（preferSmalltalk 固定 true 不出编辑位）。
 * 周期检点每班读取此配置过 DirectorRoutes 五分支；「发不发」由频控确定性裁决，
 * 产出只走起草通知，绝不自动发送。
 */
@Composable
private fun AdvisorProactiveCard(
    contactId: Long,
    policies: com.verlintas.baic2.floatball.director.ProactivePolicyRepository,
) {
    val scope = rememberCoroutineScope()
    val policy by policies.observeFor(contactId).collectAsStateWithLifecycle(initialValue = null)
    var pick by remember { mutableStateOf<ProactivePick?>(null) }
    val enabled = policy?.enabled == true

    GoalCardSurface {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.float_proactive_card_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.float_proactive_card_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        scope.launch {
                            policies.upsert(
                                (policy ?: com.verlintas.baic2.floatball.advisor.data.ProactivePolicyEntity(
                                    contactId = contactId,
                                )).copy(enabled = on, updatedAt = System.currentTimeMillis()),
                            )
                        }
                    },
                )
            }
            if (enabled && policy != null) {
                val current = policy!!
                ProactiveOptionRow(
                    label = stringResource(R.string.float_proactive_daily_cap),
                    valueText = stringResource(R.string.float_proactive_cap_value, current.dailyCap),
                ) { pick = ProactivePick.CAP }
                ProactiveOptionRow(
                    label = stringResource(R.string.float_proactive_min_gap),
                    valueText = stringResource(
                        R.string.float_proactive_gap_value,
                        current.minGapHours,
                    ),
                ) { pick = ProactivePick.GAP }
                ProactiveOptionRow(
                    label = stringResource(R.string.float_proactive_quiet),
                    valueText = stringResource(
                        R.string.float_proactive_quiet_value,
                        current.quietStart,
                        current.quietEnd,
                    ),
                ) { pick = ProactivePick.QUIET_START }
            }
        }
    }

    pick?.let { target ->
        val current = policy ?: return@let
        val (title, options, selectedIndex) = when (target) {
            ProactivePick.CAP -> Triple(
                stringResource(R.string.float_proactive_daily_cap),
                PROACTIVE_CAP_OPTIONS.map { stringResource(R.string.float_proactive_cap_value, it) },
                PROACTIVE_CAP_OPTIONS.indexOf(current.dailyCap).takeIf { it >= 0 } ?: 0,
            )
            ProactivePick.GAP -> Triple(
                stringResource(R.string.float_proactive_min_gap),
                PROACTIVE_GAP_OPTIONS.map { stringResource(R.string.float_proactive_gap_value, it) },
                PROACTIVE_GAP_OPTIONS.indexOf(current.minGapHours).takeIf { it >= 0 } ?: 0,
            )
            ProactivePick.QUIET_START -> Triple(
                stringResource(R.string.float_proactive_quiet),
                PROACTIVE_QUIET_START_OPTIONS,
                PROACTIVE_QUIET_START_OPTIONS.indexOf(current.quietStart).takeIf { it >= 0 } ?: 0,
            )
            ProactivePick.QUIET_END -> Triple(
                stringResource(R.string.float_proactive_quiet),
                PROACTIVE_QUIET_END_OPTIONS,
                PROACTIVE_QUIET_END_OPTIONS.indexOf(current.quietEnd).takeIf { it >= 0 } ?: 0,
            )
        }
        AlertDialog(
            onDismissRequest = { pick = null },
            title = { Text(title) },
            text = {
                Column {
                    options.forEachIndexed { index, option ->
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (index == selectedIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        policies.upsert(
                                            when (target) {
                                                ProactivePick.CAP -> current.copy(
                                                    dailyCap = PROACTIVE_CAP_OPTIONS[index],
                                                    updatedAt = System.currentTimeMillis(),
                                                )
                                                ProactivePick.GAP -> current.copy(
                                                    minGapHours = PROACTIVE_GAP_OPTIONS[index],
                                                    updatedAt = System.currentTimeMillis(),
                                                )
                                                ProactivePick.QUIET_START -> current.copy(
                                                    quietStart = PROACTIVE_QUIET_START_OPTIONS[index],
                                                    updatedAt = System.currentTimeMillis(),
                                                )
                                                ProactivePick.QUIET_END -> current.copy(
                                                    quietEnd = PROACTIVE_QUIET_END_OPTIONS[index],
                                                    updatedAt = System.currentTimeMillis(),
                                                )
                                            },
                                        )
                                    }
                                    pick = if (target == ProactivePick.QUIET_START) {
                                        ProactivePick.QUIET_END // 选完开始顺势接结束
                                    } else {
                                        null
                                    }
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pick = null }) {
                    Text(stringResource(R.string.float_profile_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProactiveOptionRow(label: String, valueText: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ------------------------------------------------------------ materials card（F6 我的素材库）

/** 素材列表每条展示字符上限（超长省略；全量内容在保温选题时使用）。 */
private const val MATERIAL_MAX_CHARS = 60

/**
 * 我的素材卡（F6，M1 方案 §7.2 素材三源之②「用户投喂」）：列表展示（内容+
 * tag+时间）+ 添加（对话框录入内容与可选 tag）+ 删除。素材经指纹去重入库；
 * 被保温选题采用后自动进话题冷却防短期重聊（见 TopicEngineStore）。
 */
@Composable
private fun AdvisorMaterialsCard(
    materials: com.verlintas.baic2.floatball.director.PersonalMaterialRepository,
) {
    val scope = rememberCoroutineScope()
    val items by materials.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var addOpen by remember { mutableStateOf(false) }

    GoalCardSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.float_material_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { addOpen = true }) {
                Text(stringResource(R.string.float_material_add))
            }
        }
        if (items.isEmpty()) {
            GoalSmallText(stringResource(R.string.float_material_empty))
        }
        items.forEach { material ->
            PersonalMaterialRow(
                material = material,
                onDelete = { scope.launch { materials.remove(material.id) } },
            )
        }
    }

    if (addOpen) {
        MaterialAddDialog(
            onConfirm = { content, tag ->
                scope.launch { materials.add(content, tag) }
                addOpen = false
            },
            onDismiss = { addOpen = false },
        )
    }
}

/** 单条素材行：内容（超长省略）+ tag/录入时间/最近使用小字 + 删除。 */
@Composable
private fun PersonalMaterialRow(
    material: PersonalMaterialEntity,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val clipped = if (material.content.length > MATERIAL_MAX_CHARS) {
                material.content.take(MATERIAL_MAX_CHARS) + "…"
            } else {
                material.content
            }
            Text(
                text = clipped,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.float_material_delete))
            }
        }
        GoalSmallText(
            listOfNotNull(
                material.tag?.takeIf { it.isNotBlank() },
                stringResource(R.string.float_material_created, relativeMissionTime(material.createdAt)),
                material.lastUsedAt?.let {
                    stringResource(R.string.float_material_used, relativeMissionTime(it))
                },
            ).joinToString(" · "),
        )
    }
}

/** 添加素材对话框：内容必填、tag 可选（趣事/见闻/资源等自由分类）。 */
@Composable
private fun MaterialAddDialog(
    onConfirm: (content: String, tag: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var content by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_material_add_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.float_material_content_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text(stringResource(R.string.float_material_tag_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(content, tag) },
                enabled = content.isNotBlank(),
            ) { Text(stringResource(R.string.float_material_add_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.float_profile_cancel)) }
        },
    )
}

// ------------------------------------------------------------ shared goal UI

@Composable
private fun GoalCardSurface(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) { content() }
    }
}

@Composable
private fun GoalCardTitleRow(title: String, badges: List<String>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        badges.forEach { badge -> GoalBadge(badge) }
    }
}

@Composable
private fun GoalBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun GoalSmallText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun GoalActionRow(
    status: String,
    isProvisional: Boolean,
    onEdit: () -> Unit,
    onConfirm: (() -> Unit)?,
    onDone: () -> Unit,
    onArchive: () -> Unit,
    onReactivate: (() -> Unit)?,
    onRecompile: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isProvisional && onConfirm != null) {
            Button(onClick = onConfirm) { Text(stringResource(R.string.float_goal_confirm_provisional)) }
        }
        if (status == GOAL_DONE || status == GOAL_ABANDONED) return@Row
        OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.float_goal_edit)) }
        OutlinedButton(onClick = onDone) { Text(stringResource(R.string.float_goal_done)) }
        if (onReactivate == null) {
            OutlinedButton(onClick = onArchive) { Text(stringResource(R.string.float_goal_archive)) }
        } else {
            OutlinedButton(onClick = onReactivate) { Text(stringResource(R.string.float_goal_reactivate)) }
        }
        Text(
            text = stringResource(R.string.float_goal_recompile),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onRecompile)
                .padding(4.dp),
        )
    }
}

@Composable
private fun provisionalBadge(goal: GoalEntity): String? =
    if (goal.status == GOAL_PROVISIONAL) stringResource(R.string.float_goal_badge_provisional) else null

// ------------------------------------------------------------ create/edit dialog

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalEditDialog(
    contactId: Long,
    existing: GoalEntity?,
    initialHorizon: Int,
    packs: List<ScenarioPack>,
    defaultScenario: String?,
    parentL2Options: List<GoalEntity>,
    contactName: String,
    aspiration: String,
    goals: GoalRepository,
    runtime: com.verlintas.baic2.floatball.advisor.strategy.StrategyRuntime,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var horizon by remember(existing?.id) { mutableStateOf(existing?.horizon ?: initialHorizon) }
    var scenario by remember(existing?.id) { mutableStateOf(existing?.scenarioCode ?: defaultScenario) }
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var criteria by remember(existing?.id) { mutableStateOf(existing?.successCriteria.orEmpty()) }
    var deadline by remember(existing?.id) {
        mutableStateOf(existing?.deadlineAt?.let { formatDate(it) }.orEmpty())
    }
    var parentId by remember(existing?.id) { mutableStateOf(existing?.parentId ?: parentL2Options.firstOrNull()?.id) }

    val pack = packs.firstOrNull { it.code == scenario }
    val templates = pack?.goalTemplates?.filter { it.horizon == horizon }.orEmpty()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(
                        if (existing != null) R.string.float_goal_edit else R.string.float_goal_title_label,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (existing == null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        listOf(
                            HORIZON_L3 to R.string.float_goal_horizon_l3,
                            HORIZON_L2 to R.string.float_goal_horizon_l2,
                            HORIZON_L1 to R.string.float_goal_horizon_l1,
                        ).forEach { (value, labelRes) ->
                            FilterChip(
                                selected = horizon == value,
                                onClick = { horizon = value },
                                label = { Text(stringResource(labelRes)) },
                            )
                        }
                    }
                }

                ScenarioGroupedChips(
                    packs = packs,
                    selected = scenario,
                    onSelect = { scenario = it },
                )

                if (templates.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.float_goal_template_hint),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        templates.forEach { tpl ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    title = TemplateFill.render(tpl.text, contactName, aspiration)
                                },
                                label = { Text(tpl.text.take(TEMPLATE_CHIP_CHARS), maxLines = 1) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.float_goal_title_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = criteria,
                    onValueChange = { criteria = it },
                    label = { Text(stringResource(R.string.float_goal_criteria)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = deadline,
                    onValueChange = { deadline = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.float_goal_deadline)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )

                if (horizon == HORIZON_L1) {
                    val options = parentL2Options.ifEmpty { emptyList() }
                    Text(
                        text = stringResource(R.string.float_goal_parent_l2),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { parent ->
                            FilterChip(
                                selected = parentId == parent.id,
                                onClick = { parentId = parent.id },
                                label = { Text(parent.title, maxLines = 1) },
                            )
                        }
                    }
                    if (options.isEmpty()) {
                        GoalEmptyHint(stringResource(R.string.float_goal_parent_required))
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Button(onClick = {
                        val cleanTitle = title.trim()
                        if (cleanTitle.isEmpty()) return@Button
                        val deadlineMs = deadline.trim().takeIf { it.isNotEmpty() }?.let { text ->
                            parseDate(text) ?: run {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.float_goal_invalid_date),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@Button
                            }
                        }
                        if (horizon == HORIZON_L1 && parentId == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.float_goal_parent_required),
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@Button
                        }
                        scope.launch {
                            val criteriaValue = criteria.trim().takeIf { it.isNotEmpty() }
                            val result = if (existing != null) {
                                goals.updateUserGoal(
                                    id = existing.id,
                                    title = cleanTitle,
                                    successCriteria = criteriaValue,
                                    deadlineAt = deadlineMs,
                                    priority = existing.priority,
                                    scenarioCode = scenario,
                                ).also {
                                    if (it is GoalOpResult.Ok && horizon != HORIZON_L1) {
                                        runtime.requestGoalCompile(existing.id, force = true)
                                    }
                                }
                            } else {
                                goals.createUserGoal(
                                    contactId = contactId,
                                    horizon = horizon,
                                    title = cleanTitle,
                                    successCriteria = criteriaValue,
                                    deadlineAt = deadlineMs,
                                    scenarioCode = scenario,
                                    parentId = parentId,
                                ).also {
                                    if (it is GoalOpResult.Ok && horizon != HORIZON_L1) {
                                        runtime.requestGoalCompile(it.id)
                                    }
                                }
                            }
                            if (result is GoalOpResult.Ok) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.float_goal_save_compiling),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                onDismiss()
                            } else {
                                toastGoalResult(context, result, horizon)
                            }
                        }
                    }) { Text(stringResource(R.string.float_style_save)) }
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.float_profile_cancel))
                    }
                }
            }
        }
    }
}

/** 场景五组固定顺序与标题（资产 group 驱动，新增场景包零代码改动）；目标页与风格页共用。 */
internal val SCENARIO_GROUPS: List<Pair<String, Int>> = listOf(
    GROUP_ROMANCE to R.string.float_scenario_group_romance,
    GROUP_WORKPLACE to R.string.float_scenario_group_workplace,
    GROUP_BUSINESS to R.string.float_scenario_group_business,
    GROUP_CONFLICT to R.string.float_scenario_group_conflict,
    GROUP_SOCIAL to R.string.float_scenario_group_social,
)

/** 场景选择 chips：按五组分段（资产 group 驱动，新增场景包零代码改动）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScenarioGroupedChips(
    packs: List<ScenarioPack>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Text(
        text = stringResource(R.string.float_goal_scenario),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.float_scenario_none)) },
        )
    }
    // 场景包按五组分段（与风格页同一顺序/标题）。
    SCENARIO_GROUPS.forEach { (group, labelRes) ->
        val groupPacks = packs.filter { it.group == group }
        if (groupPacks.isNotEmpty()) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groupPacks.forEach { pack ->
                    FilterChip(
                        selected = selected == pack.code,
                        onClick = { onSelect(pack.code) },
                        label = { Text(pack.label) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ helpers

private fun toastGoalResult(context: android.content.Context, result: GoalOpResult, horizon: Int? = null) {
    val resId = when (result) {
        is GoalOpResult.Limit -> when (horizon) {
            HORIZON_L3 -> R.string.float_goal_limit_l3
            HORIZON_L1 -> R.string.float_goal_limit_l1
            else -> R.string.float_goal_limit_l2
        }
        is GoalOpResult.Invalid -> when (result.reason) {
            GoalRepository.REASON_L1_NEEDS_L2 -> R.string.float_goal_parent_required
            else -> R.string.float_goal_title_label
        }
        else -> return
    }
    Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
}

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun parseDate(text: String): Long? = runCatching {
    LocalDate.parse(text.trim(), DATE_FORMATTER)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}.getOrNull()

private fun formatDate(ms: Long): String =
    DATE_FORMATTER.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate())

@Composable
private fun relativeMissionTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < MINUTE_MS -> stringResource(R.string.float_mission_time_justnow)
        diff < HOUR_MS -> stringResource(R.string.float_mission_time_min, (diff / MINUTE_MS).toInt())
        diff < DAY_MS -> stringResource(R.string.float_mission_time_hour, (diff / HOUR_MS).toInt())
        diff < WEEK_MS -> stringResource(R.string.float_mission_time_day, (diff / DAY_MS).toInt())
        else -> formatDate(ms)
    }
}

private const val DAY_MS = 86_400_000L
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val WEEK_MS = 7L * DAY_MS
private const val TEMPLATE_CHIP_CHARS = 16
