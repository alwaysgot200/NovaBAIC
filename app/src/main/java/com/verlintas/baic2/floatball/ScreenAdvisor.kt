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
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.data.repository.MemoryRepository
import com.verlintas.baic2.core.engine.AgentEvent
import com.verlintas.baic2.core.engine.AgentLoop
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.CoreMemory
import com.verlintas.baic2.core.model.MemoryPrompt
import com.verlintas.baic2.core.model.MemoryText
import com.verlintas.baic2.core.model.Note
import com.verlintas.baic2.core.model.NoteKind
import com.verlintas.baic2.core.model.ProviderConfig
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.device.api.ScreenshotProvider
import com.verlintas.baic2.floatball.ScreenPerceiver.Perception
import com.verlintas.baic2.floatball.advisor.AdvisorResponder
import com.verlintas.baic2.floatball.advisor.ChatBriefBuilder
import com.verlintas.baic2.floatball.advisor.ContactNames
import com.verlintas.baic2.floatball.advisor.GoalRepository
import com.verlintas.baic2.floatball.advisor.HeatRules
import com.verlintas.baic2.floatball.advisor.MissionJudgeRunner
import com.verlintas.baic2.floatball.advisor.StrategyBrief
import com.verlintas.baic2.floatball.advisor.data.TalkMissionEntity
import com.verlintas.baic2.floatball.advisor.data.GOAL_ACTIVE
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L1
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L2
import com.verlintas.baic2.floatball.advisor.data.MISSION_IGNORED
import com.verlintas.baic2.floatball.advisor.data.MISSION_INFORMATIONAL
import com.verlintas.baic2.floatball.advisor.data.MISSION_PENDING
import com.verlintas.baic2.floatball.advisor.data.SCENE_CHAT
import com.verlintas.baic2.floatball.advisor.data.SCENE_NON_CHAT
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_BRANCH
import com.verlintas.baic2.floatball.advisor.strategy.EDGE_KIND_BRANCH
import com.verlintas.baic2.floatball.advisor.strategy.EdgeDef
import com.verlintas.baic2.floatball.advisor.strategy.PlanStreamFilter
import com.verlintas.baic2.floatball.advisor.strategy.StageGraph
import com.verlintas.baic2.floatball.advisor.strategy.StrategyPrompt
import com.verlintas.baic2.floatball.advisor.strategy.Tactician
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import kotlinx.serialization.encodeToString
import com.verlintas.baic2.floatball.advisor.PersonaExtractor
import com.verlintas.baic2.floatball.brief.ContactBriefSection
import com.verlintas.baic2.floatball.contact.BoundConversation
import com.verlintas.baic2.floatball.contact.ContactDirectory
import com.verlintas.baic2.floatball.contact.ContactHistoryGateway
import com.verlintas.baic2.floatball.contact.ContactMemoryReader
import com.verlintas.baic2.floatball.contact.ContactRef
import com.verlintas.baic2.floatball.contact.DealFact
import com.verlintas.baic2.floatball.contact.SessionReuse
import com.verlintas.baic2.floatball.director.Director
import com.verlintas.baic2.floatball.director.DirectorInput
import com.verlintas.baic2.floatball.director.DirectorTrigger
import com.verlintas.baic2.floatball.director.GateReasons
import com.verlintas.baic2.floatball.director.GateRejection
import com.verlintas.baic2.floatball.director.GateRules
import com.verlintas.baic2.floatball.director.Gatekeeper
import com.verlintas.baic2.floatball.director.TopicEngine
import com.verlintas.baic2.floatball.director.TopicEngineStore
import com.verlintas.baic2.floatball.director.WarmChatPrompt
import com.verlintas.baic2.keepalive.KeepAlivePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 「老参谋」的大脑。会话管道与主界面聊天同构：OCR/无障碍文本作为用户
 * 消息写入持久化「老参谋」会话，由同一个 [AgentLoop] 以 Chat 模式作答。
 *
 * 单次（长按）与连续（双击）共用 [submitTurn] 的串行队列；每完成一回合
 * 发出 [turnCompletions]（服务层据此点亮红点徽章）。感知本身委托
 * [ScreenPerceiver] 四级阶梯。不含任何窗口/UI 逻辑。
 */
@Singleton
class ScreenAdvisor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenshotProvider: ScreenshotProvider,
    private val ocrProvider: OcrProvider,
    private val conversationRepository: ConversationRepository,
    private val agentRepository: AgentRepository,
    private val memoryRepository: MemoryRepository,
    private val agentLoop: AgentLoop,
    private val auxiliaryTasks: AuxiliaryTasks,
    private val perceiver: ScreenPerceiver,
    private val prefs: AdvisorPrefs,
    private val keepAlivePrefs: KeepAlivePrefs,
    private val contactDirectory: ContactDirectory,
    private val historyGateway: ContactHistoryGateway,
    private val memoryReader: ContactMemoryReader,
    private val stitcher: MultiScreenStitcher,
    private val geometryProvider: ScreenGeometryProvider,
    private val noiseRules: NoiseRules,
    private val chatBriefBuilder: ChatBriefBuilder,
    private val strategyBrief: StrategyBrief,
    private val goalRepository: GoalRepository,
    private val missionJudge: MissionJudgeRunner,
    private val personaExtractor: PersonaExtractor,
    private val responder: AdvisorResponder,
    private val strategyRuntime: com.verlintas.baic2.floatball.advisor.strategy.StrategyRuntime,
    private val tacticalAdvisor: com.verlintas.baic2.floatball.advisor.TacticalAdvisor,
    private val director: Director,
    private val topicEngineStore: TopicEngineStore,
    private val gatekeeper: Gatekeeper,
) {

    /** 面板渲染所需的一切：会话消息 + 正在流式的回答 + 阶段状态。 */
    data class UiState(
        val conversationId: Long = 0L,
        val messages: List<ChatMessage> = emptyList(),
        val streaming: String? = null,
        val status: Status = Status.Idle,
        /** 最近一个完成回合的模型上报用量（面板小字；新回合开始清空）。 */
        val lastUsage: TurnTokens? = null,
        /**
         * 本会话最近任务令（P3 §九）：驱动参谋气泡行内「✓采纳」——
         * 只在最新 assistant 气泡、且存在 pending 任务令时可点。
         */
        val missions: List<MissionMark> = emptyList(),
        /** P3 §九：assistant 消息 id → 本回合依据关键词（内存态，随会话切换清空）。 */
        val basisByMessage: Map<Long, List<String>> = emptyMap(),
        /** P3 §九：已用过一次长按重写的「被修正回合」触发消息 id（每回合限一次）。 */
        val regeneratedTurns: Set<Long> = emptySet(),
        /**
         * 场景归属信号（v10，方案 §1.2）：当前会话是否 NON_CHAT（非聊天屏孤立
         * 参谋）。分屏据此隐藏人设区（不出现「未绑定联系人」的人设暗示），
         * 只做纯问答参谋；VA-2 UI 消费。
         */
        val isNonChat: Boolean = false,
    )

    /** 任务令在气泡行内交互所需的最小投影。 */
    data class MissionMark(
        val id: Long,
        val pending: Boolean,
        val adoptedIndex: Int?,
    )

    /** 单回合 token 用量（模型 Usage；个别供应商不上报则为 0）。 */
    data class TurnTokens(val input: Long, val output: Long)

    sealed interface Status {
        data object Idle : Status
        data object OcrRunning : Status
        data object Thinking : Status
        /** 零文本失败后的第 [attempt]/[total] 次重试等待中。 */
        data class Retrying(val attempt: Int, val total: Int) : Status
        data class Error(val reason: String) : Status
    }

    sealed interface Event {
        /** 需要用户授权投影（服务层走 FloatCaptureActivity，授权后重跑）。 */
        data object NeedsConsent : Event

        /** 问题已写入会话，LLM 回合开始。 */
        data class TurnStarted(val question: String) : Event

        /** 本屏与上一屏几乎相同，已短路（不写库、不调 LLM）。 */
        data object Unchanged : Event

        data class Failed(val reason: String) : Event
    }

    private val _transcript = MutableStateFlow(UiState())
    val transcript: StateFlow<UiState> = _transcript.asStateFlow()

    private val _turnCompletions = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val turnCompletions: SharedFlow<Unit> = _turnCompletions.asSharedFlow()

    /**
     * 连续模式 token 预算耗尽：后台回合完成后若本次会话用量达到设置上限，
     * 发出一次请求，服务层据此自动停止录制（不打断正在进行的回合）。
     */
    private val _budgetExceeded = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val budgetExceeded: SharedFlow<Unit> = _budgetExceeded.asSharedFlow()

    /**
     * 分屏策略条状态（P2 §5/§7.2）：三态互斥——
     * planning（定本轮策略中，最短展示 [PLAN_HINT_MIN_MS]）→
     * provisional（观察期 L2 轻确认问句）/ missionLine（本轮任务令，可展开看动作/红线）。
     * 非策略回合（无联系人/无场景阶段）恒为全空，分屏不显示该条。
     */
    data class MissionUiState(
        val planning: Boolean = false,
        val missionLine: String? = null,
        val expanded: Boolean = false,
        val missionDetail: MissionDetail? = null,
        val provisional: ProvisionalPrompt? = null,
        val pendingBranch: PendingBranch? = null,
        /** P3：连续 retreat×2 命中后展示「路线不对？重规划」入口（可与任务令并存）。 */
        val rerouteHint: Boolean = false,
        /** 重规划草稿生成中（入口按钮置忙）。 */
        val rerouteBusy: Boolean = false,
        /** P3：新路线草稿待确认（与分叉提议同级，优先于任务令展示）。 */
        val pendingReroute: PendingReroute? = null,
    ) {
        /** 任务令详情（展开态内容）。 */
        data class MissionDetail(
            val mission: String,
            val actions: List<String>,
            val forbidden: List<String>,
            val signalsToWatch: List<String>,
            val successWhen: String,
        )

        /** 观察期 L2 轻确认问句。 */
        data class ProvisionalPrompt(val goalId: Long, val title: String)

        /** P3 有向图分叉待确认（branch 边命中，需用户拍板才切支线）。 */
        data class PendingBranch(
            val contactId: Long,
            val scenarioCode: String,
            val targetCode: String,
            val targetLabel: String,
            val reason: String,
            val pathTag: String?,
        )

        /** P3 reroute 待确认：采用后按 stageMapping 迁站并激活新版路线。 */
        data class PendingReroute(
            val goalId: Long,
            val decompId: Long,
            val targetLabel: String,
        )
    }

    private val _missionUi = MutableStateFlow(MissionUiState())
    val missionUi: StateFlow<MissionUiState> = _missionUi.asStateFlow()

    /** 观察期 L2：用户确认（转 active）。 */
    suspend fun confirmProvisionalGoal(goalId: Long) {
        runCatching { goalRepository.confirmProvisionalL2(goalId) }
        _missionUi.update { it.copy(provisional = null) }
    }

    /** 观察期 L2：本次先跳过（仅 +1，达到沉默上限由 Repository 归档）。 */
    suspend fun skipProvisionalGoal(goalId: Long) {
        runCatching { goalRepository.skipProvisionalL2(goalId) }
        _missionUi.update { it.copy(provisional = null) }
    }

    /** 编辑观察期 L2 前先关闭问句（目标页编辑完成由目标页自行刷新）。 */
    fun dismissProvisionalGoal() {
        _missionUi.update { it.copy(provisional = null) }
    }

    /** P3：接受 Judge 发现的分叉支线——按 branch 边移动阶段并写 history。 */
    suspend fun acceptPendingBranch() {
        val offer = _missionUi.value.pendingBranch ?: return
        runCatching {
            val pack = strategyBrief.pack(offer.contactId) ?: return@runCatching
            val stage = goalRepository.stage(offer.contactId, offer.scenarioCode) ?: return@runCatching
            val activeL2 = goalRepository.activeGoals(offer.contactId, HORIZON_L2)
                .firstOrNull { it.status == GOAL_ACTIVE }?.id
            val roadmap = goalRepository.activeRoadmap(offer.contactId, offer.scenarioCode, activeL2)
            val graph = StageGraph.resolve(pack, roadmap)
            val edge = graph.outgoingFrom(stage.stageCode)
                .firstOrNull { it.kind == EDGE_KIND_BRANCH && it.to == offer.targetCode }
                ?: EdgeDef(from = stage.stageCode, to = offer.targetCode, kind = EDGE_KIND_BRANCH, pathTag = offer.pathTag)
            val now = System.currentTimeMillis()
            val applied = StageGraph.applyEdge(stage, edge, graph, now, offer.reason) ?: return@runCatching
            goalRepository.commitStage(applied, STAGE_EDGE_BRANCH, offer.reason, from = stage, now = now)
        }.onFailure { android.util.Log.d(TAG, "accept branch failed", it) }
        _missionUi.update { it.copy(pendingBranch = null) }
    }

    /** P3：暂不切支线（仅关闭提议；下回合信号再次命中时 Judge 可重新提议）。 */
    fun rejectPendingBranch() {
        _missionUi.update { it.copy(pendingBranch = null) }
    }

    /**
     * P3：用户点「路线不对？重规划」。找当前联系人的 active L2，慢车道生成新路线草稿；
     * 成功后挂 pendingReroute 轻确认条，失败静默收起忙碌态（降级矩阵）。
     */
    fun requestReroute() {
        val contactId = _activeContact.value?.contactId ?: return
        if (_missionUi.value.rerouteBusy || _missionUi.value.pendingReroute != null) return
        _missionUi.update { it.copy(rerouteBusy = true) }
        advisorScope.launch {
            val goalId = runCatching {
                goalRepository.activeGoals(contactId, HORIZON_L2).firstOrNull { it.status == GOAL_ACTIVE }?.id
            }.getOrNull()
            if (goalId == null) {
                _missionUi.update { it.copy(rerouteBusy = false) }
                return@launch
            }
            strategyRuntime.requestReroute(goalId) { plan ->
                _missionUi.update {
                    if (plan != null) {
                        it.copy(
                            rerouteBusy = false,
                            rerouteHint = false,
                            pendingReroute = MissionUiState.PendingReroute(
                                goalId = plan.goalId,
                                decompId = plan.decompId,
                                targetLabel = plan.targetLabel,
                            ),
                        )
                    } else {
                        it.copy(rerouteBusy = false)
                    }
                }
            }
        }
    }

    /** P3：采用新路线（迁站 + 激活新版 decomp）。 */
    fun acceptPendingReroute() {
        val pending = _missionUi.value.pendingReroute ?: return
        advisorScope.launch {
            val ok = strategyRuntime.acceptReroute(pending.goalId, pending.decompId)
            _missionUi.update { it.copy(pendingReroute = null) }
            if (ok) {
                // 下次回合 assess 会按新钉住的 roadmapVersion 重新取景；此处无需缓存操作。
                android.util.Log.d(TAG, "reroute accepted: goal=${pending.goalId}")
            }
        }
    }

    /** P3：先不（草稿留存不激活，状态机继续跑旧版）。 */
    fun rejectPendingReroute() {
        val pending = _missionUi.value.pendingReroute ?: return
        _missionUi.update { it.copy(pendingReroute = null) }
        advisorScope.launch { strategyRuntime.rejectReroute(pending.decompId) }
    }

    /**
     * P3：回合收尾规则层 retreat 裁决后调用——同站连续 retreat 命中达阈值，
     * 在分屏条亮「路线不对？重规划」入口（只建议，绝不静默重规划）。
     */
    private suspend fun maybeShowRerouteHint(contactId: Long, scenarioCode: String, stageCode: String) {
        val count = runCatching {
            goalRepository.consecutiveEdgeAtStage(
                contactId = contactId,
                scenarioCode = scenarioCode,
                stageCode = stageCode,
                edge = com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_RETREAT,
            )
        }.getOrDefault(0)
        if (count >= REROUTE_RETREAT_HITS) {
            _missionUi.update { it.copy(rerouteHint = true) }
        }
    }

    /** 展开/收起任务令详情。 */
    fun toggleMissionExpanded() {
        _missionUi.update { state ->
            if (state.missionLine == null) state else state.copy(expanded = !state.expanded)
        }
    }

    /** 新回合开始：进入 planning 态，清空上一回合任务令/问句。 */
    private fun beginMissionUi() {
        _missionUi.value = MissionUiState(planning = true)
    }

    /** 正文首字到达：清除 planning（最短展示由调用方 delay 保证）。 */
    private fun clearPlanningUi() {
        _missionUi.update { it.copy(planning = false) }
    }

    /** 回合结束无策略内容时复位（不留上一回合的条）。 */
    private fun resetMissionUi() {
        _missionUi.value = MissionUiState()
    }

    /**
     * 回合成功收尾（P2 §5/§7.2）：剥离器截到的 <plan> 经 Tactician 解析成功才入
     * talk_missions 台账；分屏策略条按「观察期 L2 轻确认 ＞ 本轮任务令」择一展示。
     * 任何失败只 Log.d，不影响已落库的参谋回答（plan 缺失退化为无条/仅问句）。
     */
    private suspend fun finalizeMission(
        snapshot: StrategyBrief.Snapshot?,
        filter: PlanStreamFilter?,
        conversationId: Long,
        contact: ContactRef?,
        turnMessageId: Long,
        smalltalk: TopicEngine.TopicPick? = null,
    ) {
        val planRaw = filter?.plan()
        val plan = planRaw?.let { raw ->
            runCatching { Tactician.parsePlan(raw) }.getOrNull()
        }
        if (smalltalk != null) {
            // 保温回合台账（M1，D4）：mission 带 warm_chat| 前缀供话题指纹去重
            // 解码，result 恒为 informational——计入台账但永不 pending，Judge 豁免
            // settle；话题为空（选题耗尽的自由轻聊）时无指纹可记。分屏不出任务令
            // 条，草稿本身就是本轮产出。
            if (smalltalk.topic.isNotBlank()) {
                runCatching {
                    goalRepository.insertMission(
                        TalkMissionEntity(
                            conversationId = conversationId,
                            contactId = contact?.contactId,
                            turnMessageId = turnMessageId,
                            stageCode = snapshot?.context?.stage?.stageCode,
                            mission = TopicEngine.encodeWarmChatMission(
                                topic = smalltalk.topic,
                                mission = plan?.mission?.takeIf { it.isNotBlank() }
                                    ?: context.getString(R.string.float_smalltalk_default_mission, smalltalk.topic),
                            ),
                            planBlockJson = planRaw,
                            result = MISSION_INFORMATIONAL,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }.onFailure { android.util.Log.d(TAG, "insert warm-chat mission failed", it) }
            }
            resetMissionUi()
            return
        }
        if (snapshot == null) {
            resetMissionUi()
            return
        }
        if (plan != null && plan.mission.isNotBlank()) {
            runCatching {
                goalRepository.insertMission(
                    TalkMissionEntity(
                        conversationId = conversationId,
                        contactId = contact?.contactId,
                        turnMessageId = turnMessageId,
                        stageCode = snapshot.context.stage.stageCode,
                        goalId = snapshot.context.activeL1?.id ?: snapshot.context.activeL2?.id,
                        mission = plan.mission,
                        actionsJson = strategyJson.encodeToString(plan.actions),
                        forbiddenJson = strategyJson.encodeToString(autoForbiddenMerged(plan.forbidden)),
                        signalsJson = strategyJson.encodeToString(plan.signalsToWatch),
                        planBlockJson = planRaw,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { android.util.Log.d(TAG, "insert mission failed", it) }
        }
        val prompt = snapshot.provisionalL2
            ?.takeIf { it.title.isNotBlank() }
            ?.let { MissionUiState.ProvisionalPrompt(it.id, it.title) }
        val detail = plan?.takeIf { prompt == null && it.mission.isNotBlank() }?.let {
            MissionUiState.MissionDetail(
                mission = it.mission,
                actions = it.actions,
                forbidden = it.forbidden,
                signalsToWatch = it.signalsToWatch,
                successWhen = it.successWhen,
            )
        }
        // P3：T1 阶段 Judge 可能已挂上分叉提议，回合末重建状态时保留（与轻确认条同生命周期）；
        // reroute 入口/草稿同此（finalize 可能在后台 retreat 裁决前后发生，各字段独立保留）。
        val prev = _missionUi.value
        _missionUi.value = MissionUiState(
            planning = false,
            missionLine = detail?.mission,
            expanded = false,
            missionDetail = detail,
            provisional = prompt,
            pendingBranch = prev.pendingBranch,
            rerouteHint = prev.rerouteHint,
            pendingReroute = prev.pendingReroute,
        )
    }

    /** 连续参谋开录前由服务层调用：清零本次会话级 token 计数。 */
    fun resetSessionUsage() = prefs.resetSessionUsage()

    /**
     * 最近一次参谋解析到的联系人（null=未识别/重名/模糊近邻，走共享会话）。
     * 分屏状态条据此显示「🎯 名称 ▾ / 未绑定 ▸」，只在每次 prepare 时更新。
     */
    private val _activeContact = MutableStateFlow<ContactRef?>(null)
    val activeContact: StateFlow<ContactRef?> = _activeContact.asStateFlow()

    /**
     * 即时「方向」状态（分屏顶栏 chip 渲染用）。[key] 随当前会话切换：
     * 绑定联系人 = `c:<contactId>`，共享会话 = `shared`；[suggestions]
     * 在用户点开下拉时按需由 AI 拟定并在回合内缓存。全部仅内存
     * （2026-10-15 方案 §2/§6 决策 B），服务重启即清空，不做持久化。
     */
    data class DirectionState(
        val key: String = DIRECTION_KEY_SHARED,
        val selected: String? = null,
        val suggestions: List<String> = emptyList(),
        val loading: Boolean = false,
    )

    private val _direction = MutableStateFlow(DirectionState())
    val direction: StateFlow<DirectionState> = _direction.asStateFlow()

    private val directionSelected = HashMap<String, String>()
    private val directionSuggestions = HashMap<String, List<String>>()
    private var directionJob: Job? = null

    private val advisorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null
    private var preheatJob: Job? = null

    @Volatile
    private var preheatedText: String? = null

    /** LLM 串行队列：连续模式差分注入与单次参谋共用，永不并发。 */
    private val turnMutex = Mutex()

    /**
     * 回合后沉淀（digest 刷新 + 画像萃取）的并发闸：两者素材都从全量回合
     * 重算，上一回合沉淀还没跑完时新回合直接跳过，避免慢 LLM 下重复消耗。
     */
    private val consolidationLock = Mutex()

    // ------------------------------------------------------ projection

    fun isCaptureReady(): Boolean = screenshotProvider.ready.value

    /** 无障碍服务连接状态（设置页徽章用）。 */
    val a11yConnected: StateFlow<Boolean> get() = perceiver.a11yConnected

    fun createPermissionIntent(): android.content.Intent =
        screenshotProvider.createPermissionIntent()

    fun onPermissionResult(resultCode: Int, data: android.content.Intent): Boolean =
        screenshotProvider.onPermissionResult(resultCode, data)

    fun releaseProjection() {
        runCatching { screenshotProvider.release() }
    }

    // -------------------------------------------------------- direction

    /**
     * 切换当前会话对应的即时方向（prepareTurn 解析完联系人后调用）：
     * 装载该 key 已选方向与缓存建议；key 未变不重置。
     */
    private fun switchDirection(key: String) {
        if (_direction.value.key == key) return
        directionJob?.cancel()
        _direction.value = DirectionState(
            key = key,
            selected = directionSelected[key],
            suggestions = directionSuggestions[key].orEmpty(),
        )
    }

    /**
     * 用户打开「方向」下拉时调用：AI 建议为空则现场拟定（同一 key
     * 已有建议或正在生成时直接复用，不重复消耗）。生成失败给静态兜底项，
     * 菜单始终可用。
     */
    fun suggestDirections() {
        val st = _direction.value
        if (st.loading || st.suggestions.isNotEmpty()) return
        directionJob?.cancel()
        _direction.update { it.copy(loading = true) }
        directionJob = advisorScope.launch {
            val key = st.key
            // 弱网下 provider 流可能长时间不结束也不报错：超时按失败处理，
            // 落静态兜底项，保证下拉菜单不会永远停在「拟定中」。
            val generated = runCatching {
                withTimeout(DIRECTION_TIMEOUT_MS) { generateDirections() }
            }.getOrNull().orEmpty()
            val list = generated.ifEmpty {
                context.resources.getStringArray(R.array.float_dir_fallback).toList()
            }
            directionSuggestions[key] = list
            _direction.update {
                if (it.key == key) it.copy(loading = false, suggestions = list) else it
            }
        }
    }

    /** 设定即时方向（自定义输入或点选建议）；只影响之后的回合。 */
    fun setDirection(text: String) {
        val value = text.trim().take(FloatUi.DIRECTION_MAX_CHARS)
        if (value.isBlank()) return
        val key = _direction.value.key
        directionSelected[key] = value
        _direction.update { it.copy(selected = value) }
    }

    /** 清除即时方向，回到仅按长期目标参谋。 */
    fun clearDirection() {
        directionSelected.remove(_direction.value.key)
        _direction.update { it.copy(selected = null) }
    }

    /**
     * 即时方向 AI 拟定：素材 = 当前屏正文 + 联系人长期目标 + 滚动摘要，
     * 走 [AuxiliaryTasks] 一次性小任务（不手搓 HTTP）。三者全空时返回
     * 空列表，由调用方落静态兜底。
     */
    private suspend fun generateDirections(): List<String> {
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
            ?: return emptyList()
        val contact = _activeContact.value
        val goal = contact?.goal.orEmpty()
        val digest = contact?.let { c ->
            runCatching { contactDirectory.digest(c.contactId) }.getOrNull()?.digest
        } ?: prefs.screenDigest()
        val lastScreen = _transcript.value.messages
            .asReversed()
            .firstOrNull { it.role == ChatRole.USER && it.content.startsWith(screenLabel) }
            ?.let { stripScreenLabel(it.content) }
            .orEmpty()
        if (goal.isBlank() && digest.isNullOrBlank() && lastScreen.isBlank()) return emptyList()

        val material = buildString {
            append("长期目标：").append(goal.ifBlank { "无" }).append('\n')
            append("背景摘要：").append(digest?.take(DIGEST_MAX_CHARS) ?: "无").append('\n')
            append("当前屏幕：\n").append(lastScreen.take(DIRECTION_SCREEN_CHARS))
        }
        val raw = auxiliaryTasks.complete(
            config = config,
            systemPrompt = DIRECTION_SYSTEM,
            userPrompt = material,
            maxTokens = 160,
        )
        return parseDirections(raw)
    }

    /** 解析编号列表输出：去序号/项目符号/引号，过滤长度，去重，限 5 条。 */
    private fun parseDirections(raw: String): List<String> {
        val numberPrefix = Regex("""^\s*\d+\s*[.、)）:：-]?\s*""")
        return raw.lineSequence()
            .map { line ->
                line.trim()
                    .trimStart('-', '·', '•', '*', '、')
                    .replace(numberPrefix, "")
                    .trim('「', '」', '“', '”', '"', ' ', '：', ':')
            }
            .filter { it.length in DIRECTION_MIN_CHARS..DIRECTION_ITEM_MAX_CHARS }
            .distinct()
            .take(FloatUi.DIRECTION_MENU_MAX)
            .toList()
    }

    // --------------------------------------------------------- preheat

    /** 触点落下即预热（长按第一触点）：只读无障碍文本，未走长按即丢弃。 */
    fun preheat() {
        if (preheatJob?.isActive == true || preheatedText != null) return
        preheatJob = advisorScope.launch {
            val text = perceiver.readA11yText()
            if (text != null) preheatedText = text
        }
    }

    /** 取走预热结果并取消预热任务（无结果返回 null）。 */
    fun consumePreheat(): String? {
        preheatJob?.cancel()
        preheatJob = null
        val text = preheatedText
        preheatedText = null
        return text
    }

    // -------------------------------------------------------- one shot

    /**
     * 单次参谋。文本感知命中时不需要撤窗（无障碍树只含活动应用窗口）；
     * 像素路径由服务层事先撤掉自身浮层。投影拍到的帧用完即释放。
     * [awaitProjection] 供 MediaProjection 授权同意后的重跑：投影服务正在
     * 异步启动，感知阶梯须等待就绪而非立刻回 NeedsConsent。
     */
    fun adviseOnce(
        preheated: String?,
        awaitProjection: Boolean = false,
    ): Flow<Event> = flow {
        // 多屏上滑拼接（§6.6）：像素路径且前置条件全满足时，先滚动采集多屏，
        // 成功则直接进入回合；返回 null 静默回退现行单屏感知阶梯。
        val stitched = tryMultiScreenStitch(preheated)
        if (stitched != null) {
            if (stitched.viaProjection) releaseProjection()
            _transcript.updateStatus(Status.OcrRunning)
            emitScreenTurn(stitched.text)
            return@flow
        }
        when (val perception = perceiver.quickPerceive(preheated, awaitProjection)) {
            is Perception.Text -> emitScreenTurn(perception.text)

            is Perception.Image -> {
                if (perception.viaProjection) releaseProjection()
                _transcript.updateStatus(Status.OcrRunning)
                val recognized = recognizeCleanBody(perception.bytes)
                if (recognized?.body.isNullOrEmpty()) {
                    _transcript.updateStatus(Status.Error(REASON_NO_TEXT))
                    emit(Event.Failed(REASON_NO_TEXT))
                } else {
                    // L2：a11y 窗口标题缺失时，用 OCR 标题带文本兜底联系人识别。
                    // 此处 recognized 经 isNullOrEmpty()==false 已智能转换为非空。
                    emitScreenTurn(recognized.body, recognized.titleBand)
                }
            }

            Perception.NeedsProjection -> emit(Event.NeedsConsent)

            is Perception.Failed -> {
                _transcript.updateStatus(Status.Error(perception.reason))
                emit(Event.Failed(perception.reason))
            }
        }
    }

    /**
     * 多屏拼接前置校验（方案 §6.6，任一不满足静默回退单屏）：
     * 设置开启；a11y 文本未命中（文本路径无坐标、不滚动）；已绑定联系人
     * （未绑定可能是网页/地图）；无障碍已连接（滚动依赖 swipe）；包名不在
     * 禁滑名单；IME 已收拢（先 back 一次，仍聚焦则放弃）。
     */
    private suspend fun tryMultiScreenStitch(preheated: String?): MultiScreenStitcher.Stitched? {
        val settings = prefs.state.value
        if (settings.autoScrollScreens <= 0) return null
        if (preheated != null && preheated.length >= ScreenPerceiver.MIN_TEXT_CHARS) return null
        if (!perceiver.accessibilityConnected) return null

        // 前置校验已把富文本场景早退，此处只认 L1/L2 标题识别出的联系人。
        val contact = resolveContact() ?: return null
        val pkg = perceiver.foregroundPackage()
        if (pkg != null && pkg in settings.swipeBlockedPackages) return null

        if (perceiver.editableFocused()) {
            perceiver.pressBack() // IM 首按只收键盘不退页
            delay(KEYBOARD_COLLAPSE_WAIT_MS)
            if (perceiver.editableFocused()) return null
        }

        val names = listOf(contact.displayName) + contact.aliases
        return runCatching {
            stitcher.run(
                config = MultiScreenStitcher.Config(
                    extraScreens = settings.autoScrollScreens,
                    settleMs = settings.scrollSettleMs,
                    swipeRatio = settings.swipeHeightRatio,
                    scrollBack = settings.scrollBack,
                ),
                whitelistNames = names,
            )
        }.onFailure { android.util.Log.w("ScreenAdvisor", "multi-screen stitch failed", it) }
            .getOrNull()
    }

    /**
     * 像素帧的三层噪声治理（§12.2）：底部带位图掩膜 → OCR → 按包围盒分流
     * （状态栏丢、标题带不进正文）→ 行级正则库。标题带行同时产出，供 L2
     * 联系人识别（§11.1）：取带内最上方、穿过行级噪声规则的短行。
     */
    private suspend fun recognizeCleanBody(bytes: ByteArray): RecognizedScreen? {
        val imeOpen = perceiver.editableFocused()
        return withContext(Dispatchers.Default) {
            val (width, height) = FrameQuality.bitmapSize(bytes) ?: return@withContext null
            val geometry = geometryProvider.compute(width, height, imeOpen)
            val cropped = FrameQuality.cropBands(bytes, width, height, geometry.bottomBandPx)
            val lines = ocrProvider.recognize(cropped).getOrNull() ?: return@withContext null
            val partition = OcrNoiseFilter.partition(lines, geometry)
            val body = OcrNoiseFilter.clean(partition.bodyLines, noiseRules.get(), emptyList())
                .joinToString("\n") { it.text.trim() }
                .takeIf { it.isNotBlank() }
            val titleBand = pickTitleBand(partition.titleLines)
            body?.let { RecognizedScreen(it, titleBand) }
        }
    }

    /**
     * 标题带选名：titleLines 已按 top,left 排序；优先取穿过噪声规则的首行，
     * 全被规则滤掉时退回首个长度合理的原始短行（群名偶含规则字符的容错）。
     * 角标/群人数/壳后缀的规范化在 ContactRepository.resolve 内完成。
     */
    private fun pickTitleBand(titleLines: List<com.verlintas.baic2.device.api.OcrLine>): String? {
        val cleaned = OcrNoiseFilter.clean(titleLines, noiseRules.get(), emptyList())
            .map { OcrNoiseFilter.normalize(it.text) }
            .firstOrNull { it.length in TITLE_MIN_CHARS..TITLE_MAX_CHARS }
        val raw = cleaned ?: titleLines.asSequence()
            .map { OcrNoiseFilter.normalize(it.text) }
            .firstOrNull { it.length in TITLE_MIN_CHARS..TITLE_MAX_CHARS }
        return raw?.takeIf { !it.matches(TITLE_SYMBOL_ONLY) }
    }

    /** recognizeCleanBody 的产出：正文 + 标题带候选名（可空）。 */
    private data class RecognizedScreen(val body: String, val titleBand: String?)

    /**
     * 单次参谋的统一发射点：先 prepare（去重/落库），确认要真正开跑后才
     * 发 [Event.TurnStarted]（面板随即展开），避免与上一屏重复时白开一次 LLM。
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<Event>.emitScreenTurn(
        raw: String,
        titleFallback: String? = null,
    ) {
        when (val prepared = prepareTurn(autoScreenContent(raw), titleFallback)) {
            is PreparedTurn.Ready -> {
                emit(Event.TurnStarted(raw))
                executePrepared(
                    prepared.conversationId,
                    prepared.contact,
                    backgroundTurn = false,
                    turnMessageId = prepared.turnMessageId,
                )
            }
            PreparedTurn.Unchanged -> emit(Event.Unchanged)
            PreparedTurn.Empty -> {
                _transcript.updateStatus(Status.Error(REASON_NO_TEXT))
                emit(Event.Failed(REASON_NO_TEXT))
            }
        }
    }

    // ---------------------------------------------------- shared turns

    /** 自动屏幕消息的统一标记：转录视图据此折叠，主界面据此识别来源。 */
    fun autoScreenContent(text: String): String =
        context.getString(R.string.float_ask_question_label) + "\n" + text

    /** prepareTurn 的结果：可以开跑 / 与上一屏重复 / 内容为空。[Ready.turnMessageId] 为触发消息 id。 */
    private sealed interface PreparedTurn {
        data class Ready(
            val conversationId: Long,
            val contact: ContactRef?,
            val turnMessageId: Long,
        ) : PreparedTurn

        data object Unchanged : PreparedTurn
        data object Empty : PreparedTurn
    }

    /** 连续模式（差分引擎）回合的处置结果。 */
    enum class SubmitOutcome { RAN, SKIPPED_UNCHANGED, EMPTY }

    /**
     * 连续（差分）模式入口：prepare + 执行一体；重复画面静默跳过。
     */
    suspend fun submitTurn(content: String, backgroundTurn: Boolean = false): SubmitOutcome =
        when (val prepared = prepareTurn(content)) {
            is PreparedTurn.Ready -> {
                executePrepared(
                    prepared.conversationId,
                    prepared.contact,
                    backgroundTurn,
                    turnMessageId = prepared.turnMessageId,
                )
                SubmitOutcome.RAN
            }
            PreparedTurn.Unchanged -> SubmitOutcome.SKIPPED_UNCHANGED
            PreparedTurn.Empty -> SubmitOutcome.EMPTY
        }

    /** 通知代拟回合结果：仅 [SubmitOutcome.RAN] 且草稿非空时监听器才发起草通知。 */
    data class NotificationTurnResult(
        val outcome: SubmitOutcome,
        val conversationId: Long? = null,
        val draft: String? = null,
    )

    /**
     * M2-a 起草档入口（方案 §8.1 N2）：通知监听匹配到已建档联系人后的后台
     * 回合。与屏幕回合同径——正文带屏幕标签，去重/瘦身/IncomingCue/
     * Gatekeeper/Director 台账全部复用；差异仅在联系人由监听器解析（通知
     * 场景前台未必是聊天页，不能依赖 perceiver 识别），回合结束后回读最新
     * ASSISTANT 草稿交监听器发起草通知。
     */
    suspend fun submitNotificationTurn(content: String, contact: ContactRef): NotificationTurnResult {
        val body = content.trim().take(MAX_SCREEN_CHARS)
        if (body.isEmpty()) return NotificationTurnResult(SubmitOutcome.EMPTY)
        val question = autoScreenContent(body)
        _activeContact.value = contact
        switchDirection("c:${contact.contactId}")
        val conversationId = resolveConversation(contact, question)
        if (isDuplicateScreen(conversationId, question)) {
            return NotificationTurnResult(SubmitOutcome.SKIPPED_UNCHANGED, conversationId)
        }
        // V-C §4.3 通知链路的 heat 信号：通知正文可得，gap=距对方上次来信；
        // 顺带补 markIncomingAt（通知本身就是对方来信到达的最直接观测）。与连续
        // 模式差分路互斥不重复记：此后若屏幕再见到同文来信，会被上方去重拦住。
        runCatching {
            val gap = contactDirectory.lastIncomingAt(conversationId)
                ?.let { System.currentTimeMillis() - it }
            HeatRules.delta(HeatRules.Signal(body, gap))?.let { d ->
                contactDirectory.recordHeatDelta(contact.contactId, d)
            }
            contactDirectory.markIncomingAt(conversationId, System.currentTimeMillis())
        }
        val turnMessageId = conversationRepository.append(
            ChatMessage(
                conversationId = conversationId,
                role = ChatRole.USER,
                content = question,
                createdAt = System.currentTimeMillis(),
            ),
        )
        executePrepared(conversationId, contact, backgroundTurn = true, turnMessageId = turnMessageId)
        val draft = historyGateway.recentMessages(conversationId, DRAFT_LOOKBACK_MESSAGES)
            .lastOrNull { it.role == ChatRole.ASSISTANT }?.content
        return NotificationTurnResult(SubmitOutcome.RAN, conversationId, draft)
    }

    /**
     * 分屏「来点闲聊」手动入口（M1，方案 §10/§13.2 矛盾#3）：Director 路由
     * SMALL_TALK → TopicEngine 确定性选题 → 走既有 Tactician→Responder 管线
     * 出一条开场草稿，分屏展示、绝不自动发送。会话路由与手动追问同径（绑定
     * 联系人进专属会话，否则续当前面板会话/共享会话）；专属会话靠 warm_chat|
     * 任务令台账去重，共享会话（无台账）以提问文本中的「话题」近似指纹去重。
     * 选题/取数全在 advisorScope 后台线程，失败只 Log.d，绝不影响悬浮球状态。
     */
    fun requestSmalltalk() {
        if (_transcript.value.status == Status.Thinking) return
        advisorScope.launch {
            runCatching {
                val contact = _activeContact.value
                val conversationId = resolveConversation(
                    contact,
                    context.getString(R.string.float_smalltalk_question_free),
                )
                val pick = if (contact != null) {
                    topicEngineStore.selectFor(contact)
                } else {
                    topicEngineStore.selectFor(null, sharedUsedTopics(conversationId))
                }
                val question = pick?.topic
                    ?.takeIf { it.isNotBlank() }
                    ?.let { context.getString(R.string.float_smalltalk_question_fmt, it) }
                    ?: context.getString(R.string.float_smalltalk_question_free)
                val turnMessageId = conversationRepository.append(
                    ChatMessage(
                        conversationId = conversationId,
                        role = ChatRole.USER,
                        content = question,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                executePrepared(
                    conversationId,
                    contact,
                    backgroundTurn = false,
                    smalltalk = pick,
                    turnMessageId = turnMessageId,
                )
            }.onFailure { android.util.Log.d(TAG, "requestSmalltalk failed", it) }
        }
    }

    /**
     * 主动保温回合的产出：会话 id、最新 ASSISTANT 草稿（空 = 回合失败无草稿）
     * 与触发消息 id（供检点回填台账回合锚点，闭合 §6 三层回放链）。
     */
    data class ProactiveTurnResult(
        val conversationId: Long?,
        val draft: String?,
        val turnMessageId: Long? = null,
    )

    /**
     * PROACTIVE 保温回合入口（M2-b，方案 §5.5 SmallTalk 路）：周期检点命中
     * 频控后来调，复用 M1 保温管线（TopicEngine 选题 → Tactician→Responder），
     * 差异仅在后台执行（token 预算/串行队列同后台回合）。路由决策与台账由
     * 检点方（ProactiveCheckpoint）唯一落库，[executePrepared] 传
     * [skipDirectorRoute]=true 防同回合双记；触发消息 id 随结果返回，由检点
     * 回填台账行（决策先于回合产生，§6 回放链由此闭合）。产出走 M2-a 起草
     * 通知（人肉一键档，绝不自动发送）。
     */
    suspend fun runProactiveSmalltalk(contact: ContactRef): ProactiveTurnResult {
        val pick = topicEngineStore.selectFor(contact)
        val question = pick?.topic
            ?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.float_smalltalk_question_fmt, it) }
            ?: context.getString(R.string.float_smalltalk_question_free)
        _activeContact.value = contact
        switchDirection("c:${contact.contactId}")
        val conversationId = resolveConversation(contact, question)
        val turnMessageId = conversationRepository.append(
            ChatMessage(
                conversationId = conversationId,
                role = ChatRole.USER,
                content = question,
                createdAt = System.currentTimeMillis(),
            ),
        )
        executePrepared(
            conversationId,
            contact,
            backgroundTurn = true,
            smalltalk = pick,
            skipDirectorRoute = true,
        )
        val draft = historyGateway.recentMessages(conversationId, DRAFT_LOOKBACK_MESSAGES)
            .lastOrNull { it.role == ChatRole.ASSISTANT }?.content
        return ProactiveTurnResult(conversationId, draft, turnMessageId)
    }

    /** 共享会话已用话题：近若干条用户消息文本里的「话题」提取为指纹（新→旧）。 */
    private suspend fun sharedUsedTopics(conversationId: Long): List<TopicEngine.UsedTopic> =
        runCatching {
            historyGateway.recentMessages(conversationId, SHARED_USED_MESSAGES)
                .filter { it.role == ChatRole.USER }
                .asReversed()
                .mapNotNull { message ->
                    TopicEngine.topicOfQuestion(stripScreenLabel(message.content))
                        ?.let { TopicEngine.UsedTopic(TopicEngine.fingerprintOf(it), message.createdAt) }
                }
                .distinctBy { it.fingerprint }
        }.getOrDefault(emptyList())

    /**
     * 阶段一：场景分类、识别联系人、路由会话、截断、去重、把屏幕消息写入
     * 会话（不跑 LLM）。自动屏幕回合先过 [SceneGate]（v10 方案 §1.3）：
     * NON_CHAT/UNCERTAIN 保守走孤立参谋会话（零人设管线，不查档案不建卡）；
     * CHAT 才进 [resolveContact]。自动屏幕消息若与上一条屏幕消息高度相似
     * （[FrameQuality.textSimilarity] ≥ [DUP_SCREEN_SIMILARITY]），直接
     * [PreparedTurn.Unchanged]——不写库、不调模型，主屏/锁屏反复点亮不再
     * 制造垃圾回合。
     */
    private suspend fun prepareTurn(content: String, titleFallback: String? = null): PreparedTurn {
        val question = content.trim().take(MAX_SCREEN_CHARS)
        if (question.isEmpty()) return PreparedTurn.Empty
        // 手动追问不重新识别（保持当前绑定，面板续聊时状态条不闪「未绑定」）；
        // 自动屏幕回合带正文做内容级兜底（T11）。
        val isScreenTurn = content.startsWith(screenLabel)
        val screenBody = if (isScreenTurn) stripScreenLabel(content) else null
        var nonChat = false
        val contact = if (isScreenTurn) {
            when (SceneGate.classify(perceiver.foregroundPackage(), screenBody)) {
                SceneGate.Scene.Chat -> resolveContact(titleFallback, screenBody)
                SceneGate.Scene.NonChat, SceneGate.Scene.Uncertain -> {
                    // TODO(V-A-2): UNCERTAIN 的轻 LLM 兜底 hook（AuxiliaryTasks
                    // ≤80tok「这是聊天界面吗」，命中 CHAT 再回 resolveContact）；
                    // 本版保守判 NON_CHAT，宁可不关联也不错关联。
                    nonChat = true
                    null
                }
            }
        } else {
            _activeContact.value
        }
        _activeContact.value = contact
        // 手动追问保持面板当前场景；屏幕回合随本次分类刷新（UI 由 VA-2 消费）。
        if (isScreenTurn) _transcript.update { it.copy(isNonChat = nonChat) }
        switchDirection(contact?.let { "c:${it.contactId}" } ?: DIRECTION_KEY_SHARED)
        val conversationId = resolveConversation(contact, question, nonChat)
        // 场景落行（v10 §1.2）：非聊天回合把会话标成 non_chat 孤立会话（联系人
        // 绑定行有守卫不会被误标）；聊天屏幕回合若复用了先前 non_chat 的共享
        // 会话则把场景校正回 chat。任何落行失败静默降级，不阻断参谋。
        if (isScreenTurn) {
            runCatching {
                contactDirectory.markScene(
                    conversationId,
                    if (nonChat) SCENE_NON_CHAT else SCENE_CHAT,
                )
            }
        }
        if (isDuplicateScreen(conversationId, question)) {
            _transcript.updateStatus(Status.Idle)
            return PreparedTurn.Unchanged
        }
        val turnMessageId = conversationRepository.append(
            ChatMessage(
                conversationId = conversationId,
                role = ChatRole.USER,
                content = question,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return PreparedTurn.Ready(conversationId, contact, turnMessageId)
    }

    /**
     * 联系人识别（v3 §11.1 + T11 + v10 §2.1）：仅 [SceneGate.Scene.Chat] 后调用。
     * L1 = 前台包名 + a11y 窗口标题；L1 未命中且 [titleFallback] 非空（像素路径
     * OCR 标题带，L2）时用标题带再试一次。两级标题均先过壳标题过滤（浏览器等
     * 非 IM 应用的窗口标题是应用自身名，如 Chrome 的「Chrome」——既会误命中
     * 同名键档案，也会被懒建成 Stub）。正文级先走 [EntityResolver] 三态：
     * 多发言人 → 群（群名取标题带，无标题孤立返回，绝不据正文建个人 stub）；
     * 否则标题两级未命中时以正文唯一发言人兜底——此处 allowStub=true 为方案
     * §2.1 自动建档唯一入口（调用前已由 IM 白名单 + 聊天结构双重确认，且建档
     * 即双建 self_personas）。规范化、决策表与懒创建由 [ContactDirectory]
     * 实现侧完成；任何失败按未绑定处理，不阻断参谋。
     */
    private suspend fun resolveContact(
        titleFallback: String? = null,
        screenBody: String? = null,
    ): ContactRef? {
        val pkg = perceiver.foregroundPackage()
        // 桌面/系统壳不是聊天窗口：不建档、不识别，整轮回落到共享老参谋会话（方案 §6.8④）。
        if (!isChatWindow(pkg)) return null
        val l1 = perceiver.windowTitle()?.takeUnless { isShellTitle(pkg, it) }
        val l2 = titleFallback?.takeUnless { it.isBlank() || isShellTitle(pkg, it) }
        // 实体判别（v10 §2.1）：正文多发言人先判群——群标题若先走个人
        // resolve 会误建个人 stub，故 Group 分支优先。
        if (EntityResolver.judge(screenBody) is EntityResolver.Entity.Group) {
            val groupTitle = l1 ?: l2 ?: return null
            return runCatching { contactDirectory.resolveGroup(pkg, groupTitle) }.getOrNull()
        }
        runCatching { contactDirectory.resolve(pkg, l1) }
            .getOrNull()
            ?.let { return it }
        if (l2 != null) {
            runCatching { contactDirectory.resolve(pkg, l2) }.getOrNull()?.let { return it }
        }
        if (screenBody.isNullOrBlank()) return null
        val speaker = IncomingCue.speakerOf(screenBody) ?: return null
        return runCatching {
            contactDirectory.resolve(pkg, speaker, allowStub = true)
        }.getOrNull()
    }

    /** 应用壳标题缓存（包名 → 应用标签）；解析失败缓存空串避免重复 binder 查询。 */
    private val appLabelCache = ConcurrentHashMap<String, String>()

    /**
     * 壳标题判定（T11）：窗口标题与应用自身标签规范化后一致（如 Chrome 的
     * 「Chrome」）时标识的是应用而非聊天对象，不得作为联系人识别输入。
     */
    private fun isShellTitle(pkg: String?, title: String?): Boolean {
        if (pkg.isNullOrBlank() || title.isNullOrBlank()) return false
        val label = appLabelCache.getOrPut(pkg) {
            runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(pkg, 0),
                ).toString()
            }.getOrDefault("")
        }
        if (label.isBlank()) return false
        return ContactNames.normalize(label) == ContactNames.normalize(title)
    }

    /**
     * 判断前台包是否可能是聊天窗口。桌面包按 HOME Intent 动态发现（覆盖三方
     * 启动器）；系统固定壳走 [NON_CHAT_PACKAGES]。包名缺失时不阻断既有行为。
     */
    private fun isChatWindow(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        // 主应用自身不是 IM 聊天窗口：自家长按属自反测试，回落共享会话（T11）。
        if (pkg == context.packageName) return false
        if (pkg in NON_CHAT_PACKAGES) return false
        return pkg !in homePackages
    }

    private val homePackages: Set<String> by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching {
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * 回合会话路由（V-B，UltraAdvisor v2.2 方案 §3）：绑定联系人 → 按内容
     * 相似度回看该联系人全部会话取最高者续用（[resolveContactConversation]）；
     * 未绑定分两类：输入行手动追问（无屏幕前缀）延续面板正在展示的会话；
     * 自动屏幕回合（带屏幕前缀）按内容相似度决定复用或新建共享会话
     * （[resolveSharedConversation]，[nonChat] 决定 non_chat 池隔离）。
     */
    private suspend fun resolveConversation(
        contact: ContactRef?,
        incoming: String,
        nonChat: Boolean = false,
    ): Long {
        if (contact != null) {
            // 自动屏幕回合带屏正文做相似度比对；手动追问/通知链回合无可比正文，
            // 复用最近会话（复用优先）。
            val screenBody = stripScreenLabel(incoming)
                .takeIf { incoming.startsWith(screenLabel) && it.isNotBlank() }
            val conversationId = resolveContactConversation(contact, screenBody)
            subscribeConversation(conversationId)
            return conversationId
        }
        if (!incoming.startsWith(screenLabel)) {
            // 手动追问：面板显示什么就续什么（含联系人会话）；服务重启等
            // 无当前会话的场景退回持久化共享会话。
            val current = _transcript.value.conversationId
            if (current > 0) {
                subscribeConversation(current)
                return current
            }
            return ensureSharedConversation()
        }
        return resolveSharedConversation(stripScreenLabel(incoming), nonChat)
    }

    /**
     * 绑定联系人回合的会话路由（V-B，方案 §3 复用优先强化）：该联系人名下
     * **全部**会话（新→旧，含合并带来的多个）逐一与当前屏正文回看比对相似度
     * （[FrameQuality.textSimilarity]），取最高者续用——复用优先，永不主动新建；
     * 仅当最高相似度低于断崖阈值（[AdvisorPrefs.Settings.reuseCliffSimilarity]）
     * 才判定重大话题转变，自动新开会话并**关联同档案**（旧会话保留可回看，
     * 验收口径：同联系人连续使用不新建、断崖后档案不变）。分叉的 Tactician
     * 轻判定留 hook：目前纯规则（相似度 < 阈值 X 即断崖），后续可在
     * [SessionReuse.Route.Cliff] 分支前置一次 ≤80tok LLM 复核再放行新建。
     * 候选会话内还没有屏幕消息（空/仅手动追问）时无从比较，复用最近一个
     * 仍存在的会话（宁可续用）；全部绑定行失效才新建。
     */
    private suspend fun resolveContactConversation(contact: ContactRef, screenBody: String?): Long {
        val bound = contactDirectory.boundConversations(contact.contactId)
        if (bound.isEmpty()) return contactDirectory.createConversationFor(contact)
        val body = screenBody?.takeIf { it.isNotBlank() } ?: return reuseNewestAlive(bound)
            ?: contactDirectory.createConversationFor(contact)
        val scored = buildList {
            for (candidate in bound) {
                // 会话可能已被用户删除：取不到最近屏幕消息就跳过（消息随会话删）。
                val last = historyGateway.latestScreenBody(candidate.conversationId, screenLabel)
                    ?: continue
                add(
                    SessionReuse.Scored(
                        candidate.conversationId,
                        FrameQuality.textSimilarity(body, stripScreenLabel(last)),
                    ),
                )
            }
        }
        return when (val route = SessionReuse.route(scored, prefs.state.value.reuseCliffSimilarity.toDouble())) {
            is SessionReuse.Route.Reuse -> route.conversationId
            is SessionReuse.Route.Cliff -> contactDirectory.createConversationFor(contact)
            SessionReuse.Route.NoComparable -> reuseNewestAlive(bound)
                ?: contactDirectory.createConversationFor(contact)
        }
    }

    /** 复用优先兜底：绑定行新→旧取第一个上游会话仍存在的；全部失效返回 null。 */
    private suspend fun reuseNewestAlive(bound: List<BoundConversation>): Long? =
        bound.firstOrNull { candidate ->
            runCatching { conversationRepository.get(candidate.conversationId) }.getOrNull() != null
        }?.conversationId

    /**
     * 非联系人自动屏幕回合的共享会话路由（V-B 增加池隔离）：当前屏正文与最近
     * 共享会话的最近一条屏幕消息相似（[SHARED_REUSE_SIMILARITY]）→ 复用（同场景
     * 连续滚动追问）；内容已切到别的场景 → 再向最近 [SHARED_LOOKBACK_TURNS] 条
     * 屏幕消息回看找相似（A→B→A 切回原场景时回到原会话，像素 OCR 漏认联系人
     * 的回合也借内容相似找回原会话）；都不像 → 另起新会话并更新偏好指向，
     * 旧会话留在库中可回看。会话内还没有屏幕消息（空/仅手动）时无从比较，
     * 直接复用。
     *
     * 池隔离（V-B，方案 §3 NON_CHAT 行）：[nonChat]=true 时只与 non_chat 会话
     * 比对复用（桌面/新闻屏的孤立参谋上下文），聊天会话同理不串入 non_chat
     * 池——偏好会话场景不同视同无偏好，回看窗口内不同场景的候选一律跳过
     * （[SessionReuse.samePool]）。命中 contact 绑定的聊天会话属既有设计
     * （T11 内容级兜底），保持不动。
     */
    private suspend fun resolveSharedConversation(screenBody: String, nonChat: Boolean): Long {
        val scene = if (nonChat) SCENE_NON_CHAT else SCENE_CHAT
        val existing = prefs.conversationId().takeIf { it > 0 }
            ?.let { id -> runCatching { conversationRepository.get(id) }.getOrNull() }
        // 偏好会话场景不同池（如聊天屏偏好指向 non_chat 会话）时不比对不复用。
        val preferred = existing
            ?.takeIf { SessionReuse.samePool(scene, contactDirectory.sceneFor(it.id)) }
        if (preferred != null) {
            val last = historyGateway.latestScreenBody(preferred.id, screenLabel)
            if (last == null ||
                FrameQuality.textSimilarity(screenBody, stripScreenLabel(last)) >= SHARED_REUSE_SIMILARITY
            ) {
                subscribeConversation(preferred.id)
                return preferred.id
            }
        }
        for ((candidateId, body) in historyGateway.recentScreenBodies(screenLabel, SHARED_LOOKBACK_TURNS)) {
            if (candidateId == preferred?.id) continue // 偏好会话刚比对过，不相似
            // 池隔离：回看窗口里只认同场景会话（non_chat 不串聊天、聊天不串 non_chat）。
            if (!SessionReuse.samePool(scene, contactDirectory.sceneFor(candidateId))) continue
            if (FrameQuality.textSimilarity(screenBody, stripScreenLabel(body)) < SHARED_REUSE_SIMILARITY) continue
            // 会话可能已被用户删除：取不到就跳过这一条。
            val conversation = runCatching { conversationRepository.get(candidateId) }.getOrNull()
                ?: continue
            subscribeConversation(conversation.id)
            prefs.setConversationId(conversation.id)
            return conversation.id
        }
        val conversationId = conversationRepository.create(
            agentId = null,
            mode = AppMode.CHAT,
            title = context.getString(R.string.float_ask_conversation_title),
        ).also { id -> prefs.setConversationId(id) }
        subscribeConversation(conversationId)
        return conversationId
    }

    /**
     * 阶段二：串行队列里跑一个 LLM 回合；[smalltalk] 非空 = 保温闲聊回合（M1）。
     * [skipDirectorRoute]=true（M2-b PROACTIVE 保温回合专用）：路由决策已由
     * 检点方落台账，此处不再重复记行（一回合一条台账，频控计数不失真）。
     * [turnMessageId] 为触发消息 id（回合 USER 消息，prepare/入口落库即产生；
     * 方案 §6「决策→回合→结局」三层回放链的决策→回合锚点）。
     */
    private suspend fun executePrepared(
        conversationId: Long,
        contact: ContactRef?,
        backgroundTurn: Boolean,
        smalltalk: TopicEngine.TopicPick? = null,
        skipDirectorRoute: Boolean = false,
        turnMessageId: Long? = null,
    ) {
        // M0 调度中枢旁路（方案 §5）：连续/后台回合=INCOMING，手动回合=MANUAL；
        // 「来点闲聊」入口记 SMALL_TALK（manual_smalltalk_entry，方案 §10）。
        // 未绑定联系人时 Director 内部只决策不落库。
        if (!skipDirectorRoute) {
            director.route(
                DirectorInput(
                    trigger = if (backgroundTurn) DirectorTrigger.INCOMING else DirectorTrigger.MANUAL,
                    requestSmalltalk = smalltalk != null,
                    stageDwellDays = contact?.let { stageDwellDays(it.contactId) } ?: 0L,
                    nowMs = System.currentTimeMillis(),
                ),
                contactId = contact?.contactId,
                conversationId = conversationId,
                turnMessageId = turnMessageId,
            )
        }
        _transcript.update { it.copy(status = Status.Thinking, lastUsage = null) }
        turnMutex.withLock {
            runAdvisorTurn(conversationId, contact, backgroundTurn, smalltalk)
        }
    }

    /**
     * 当前阶段驻留天数（F7 action_hint 旁路原料，方案 §5.6）：无联系人/无
     * 场景包/无阶段态时 0（不触发）。取数失败静默降级同 TopicEngineStore——
     * 旁路信号绝不阻断回合。stageCode 取数路径与 TopicEngineStore.selectFor
     * 同径（strategyBrief.pack → goals.stage）。
     */
    private suspend fun stageDwellDays(contactId: Long): Long {
        if (contactId <= 0L) return 0L
        val pack = runCatching { strategyBrief.pack(contactId) }.getOrNull() ?: return 0L
        val state = runCatching { goalRepository.stage(contactId, pack.code) }.getOrNull() ?: return 0L
        return (System.currentTimeMillis() - state.enteredAt) / DAY_MS
    }

    /** 自动屏幕消息与上一条屏幕消息的正文相似度判定（前缀行不参与）。 */
    private suspend fun isDuplicateScreen(conversationId: Long, content: String): Boolean {
        if (!content.startsWith(screenLabel)) return false
        // LIMIT 1 有界查询，不随会话长度增长装载整表。
        val last = historyGateway.latestScreenBody(conversationId, screenLabel) ?: return false
        return FrameQuality.textSimilarity(
            stripScreenLabel(content),
            stripScreenLabel(last),
        ) >= prefs.state.value.screenDedupSimilarity
    }

    private fun stripScreenLabel(content: String): String =
        content.removePrefix(screenLabel).removePrefix("\n").trim()

    private val screenLabel: String
        get() = context.getString(R.string.float_ask_question_label)

    /** 一次尝试的结果：成功 / 零文本失败（可重试）/ 已有部分输出失败（不重试）。 */
    private sealed interface TurnOutcome {
        /** @param assistantMessageId 落库的参谋回复消息 id（P3 §九依据小字挂接用）。 */
        data class Done(val assistantMessageId: Long) : TurnOutcome
        data class ZeroOutputFailure(val reason: String) : TurnOutcome
        data class PartialOutputFailure(val reason: String, val partial: String) : TurnOutcome
    }

    /** 交往脉络段并行取数的原始产物（跨段去重前）。 */
    private data class ContactFacts(
        val entityNotes: List<Note>,
        val dueNotes: List<Note>,
        val dealFacts: List<DealFact>,
    )

    /** T1 并行取数收口后的全部回合素材（去重/渲染在其上进行）。 */
    private data class TurnSources(
        val core: CoreMemory?,
        val primed: List<Note>,
        val boundDigest: String?,
        val brief: ChatBriefBuilder.Brief?,
        val facts: ContactFacts?,
        val strategy: StrategyBrief.Snapshot?,
    )

    /**
     * 与 ChatViewModel 的聊天轮同构：AgentLoop 产出、仓储落库。
     * 零文本失败（首字超时/连接失败/零文本 Failed）按递增间隔重试；
     * 已见文本（含思考过程）后失败绝不重试，保存 partial 并报错。
     */
    private suspend fun runAdvisorTurn(
        conversationId: Long,
        contact: ContactRef?,
        tightRetry: Boolean,
        smalltalk: TopicEngine.TopicPick? = null,
    ) {
        val settings = prefs.state.value
        val config: ProviderConfig = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
            ?: run {
                _transcript.updateStatus(Status.Error(REASON_NO_CONFIG))
                return
            }
        // 通道选择（省 token 的核心开关）：
        // 智能档=连续模式的自动回合走无工具直连，手动参谋/追问保留全功能记忆；
        // 增强档=全部走 AgentLoop；精简档=全部直连。两条通道的 App 侧记忆
        // 注入（core/primed/联系人事实/承诺/成交要件）完全一致。
        val lean = settings.memoryMode == AdvisorPrefs.MEMORY_MODE_LEAN ||
            (settings.memoryMode == AdvisorPrefs.MEMORY_MODE_AUTO && tightRetry)
        // 回答长度档硬上限：两条通道最终都以该 maxTokens 构建 ChatRequest。
        val boundedConfig = config.copy(maxTokens = settings.answerMaxTokens)

        // 有界读取：瘦身历史最多使用最后 60 条（AdvisorHistory.MAX_MESSAGES），
        // 不整表加载随会话变长的全部消息。问题文本是后续全部取数的唯一输入，
        // 必须先于 T1 并行块拿到。
        val allMessages = historyGateway.recentMessages(conversationId, HISTORY_LOAD_MESSAGES)
        val question = allMessages.lastOrNull { it.role == ChatRole.USER } ?: return
        val screenBody = stripScreenLabel(question.content)
        val goal = contact?.goal?.takeIf { it.isNotBlank() }

        // 召回 query 收窄（v4 §8.2）：聊天界面用「对方最后一条来信」，
        // 非聊天界面/提取失败回退整屏；绑定联系人追加业务目标关键词。
        val incoming = IncomingCue.latestIncoming(screenBody)
        val recallCue = IncomingCue.recallQuery(screenBody, goal, RECALL_QUERY_CHARS)

        // P3 §12.2：来信指纹确已变化（非 OCR 抖动的同屏重复）才写来信时间戳，
        // 供 StrategyBrief 的 24h 中性时序信号消费。共享会话无绑定行，UPDATE 静默无效。
        if (incoming != null) {
            val priorIncoming = allMessages.asReversed().asSequence()
                .drop(1) // 跳过本回合 question 自身
                .filter { it.role == ChatRole.USER }
                .mapNotNull { IncomingCue.latestIncoming(stripScreenLabel(it.content)) }
                .firstOrNull()
            if (priorIncoming == null || !PromptDedup.isDuplicate(incoming, priorIncoming)) {
                runCatching {
                    // V-C §4.3 连续模式差分路的 heat 信号：文本（来信段）可得，
                    // gap=距上次感知到对方来信的间隔；增量经 HeatRules 算出后落分，
                    // 共享会话（contact=null）只补时间戳不记分。先读后写防自污染。
                    if (contact != null) {
                        val gap = contactDirectory.lastIncomingAt(conversationId)
                            ?.let { System.currentTimeMillis() - it }
                        HeatRules.delta(HeatRules.Signal(incoming, gap))?.let { d ->
                            contactDirectory.recordHeatDelta(contact.contactId, d)
                        }
                    }
                    contactDirectory.markIncomingAt(conversationId, System.currentTimeMillis())
                }
            }
        }

        // T1 并行取数（v4 §11）：核心记忆/通用召回/滚动摘要/ChatBrief/
        // 交往脉络五类只读数据并发，互不依赖；任一失败 runCatching 静默降级，
        // 绝不让参谋回合开天窗（P-7）。
        val sources = coroutineScope {
            val coreDeferred = async {
                runCatching { memoryRepository.getCore() }.getOrNull()
            }
            val primedDeferred = async {
                // 检索是只读浏览，不强化（feedback=false），看屏不反写记忆权重。
                runCatching {
                    memoryRepository.recall(
                        terms = MemoryText.terms(recallCue),
                        limit = PRIMED_NOTES,
                        feedback = false,
                    )
                }.getOrDefault(emptyList()).map { it.note }
            }
            // 绑定联系人只用自己的 digest；尚无 digest 时为 null（省略历史行），
            // 绝不回退共享会话 digest——别的屏幕摘要串进客户线程会误导话术。
            val digestDeferred = async {
                contact?.let { c ->
                    runCatching { contactDirectory.digest(c.contactId) }.getOrNull()?.digest
                }
            }
            val strategyDeferred = async {
                contact?.let { c ->
                    runCatching {
                        // 先取景，再对「上一回合遗留的 pending 任务令」做条件裁决；
                        // Judge 可能推动阶段跃迁，裁决落库后重新取景，保证本回合
                        // 提示词与分屏条用新站（裁决失败静默沿用第一次取景结果）。
                        val first = strategyBrief.assess(
                            contactId = c.contactId,
                            conversationId = conversationId,
                            incomingText = incoming.orEmpty(),
                            screenBody = screenBody,
                        ) ?: return@runCatching null
                        val judgeText = incoming ?: screenBody
                        val lastReply = runCatching {
                            historyGateway.recentScreenTurns(conversationId, screenLabel)
                                .lastOrNull()?.replyContent
                        }.getOrNull()
                        // P3 §十：只把 active L1（≤3）交给 Judge 判 completedL1。
                        val activeL1ForJudge = runCatching {
                            goalRepository.activeGoals(c.contactId, HORIZON_L1)
                                .filter { it.status == GOAL_ACTIVE }
                                .take(GoalRepository.CAP_L1)
                        }.getOrDefault(emptyList())
                        // 保温回合不触发对旧 pending 的裁决（D4：informational 回合
                        // 不参与裁决链，「对方来信」语义对闲聊草稿不成立）。
                        val outcome = if (smalltalk == null) {
                            runCatching {
                                missionJudge.judgePending(
                                    contactId = c.contactId,
                                    pack = first.context.pack,
                                    stage = first.context.stage,
                                    roadmap = first.context.roadmap,
                                    incomingText = judgeText,
                                    replyText = lastReply,
                                    activeL1 = activeL1ForJudge,
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                        val judged = outcome?.judged == true
                        outcome?.branch?.let { b ->
                            _missionUi.update {
                                it.copy(
                                    pendingBranch = MissionUiState.PendingBranch(
                                        contactId = c.contactId,
                                        scenarioCode = first.context.pack.code,
                                        targetCode = b.targetCode,
                                        targetLabel = b.targetLabel,
                                        reason = b.reason,
                                        pathTag = b.pathTag,
                                    ),
                                )
                            }
                        }
                        // P3 §十：高置信完成只入内存建议态，GoalTab 出建议条，绝不自动勾选。
                        outcome?.l1Completions?.forEach { suggestion ->
                            goalRepository.suggestL1Completion(
                                goalId = suggestion.goalId,
                                title = suggestion.title,
                                evidence = suggestion.evidence,
                            )
                        }
                        if (judged) {
                            runCatching {
                                strategyBrief.assess(
                                    contactId = c.contactId,
                                    conversationId = conversationId,
                                    incomingText = incoming.orEmpty(),
                                    screenBody = screenBody,
                                    countProvisionalPrompt = false,
                                )
                            }.getOrDefault(first)
                        } else {
                            first
                        }
                    }.getOrNull()
                }
            }
            val briefDeferred = async {
                contact?.let { c ->
                    // 风格段站码与业务目标抑制依赖策略取景结果，先 await 再组装 brief。
                    val snap = strategyDeferred.await()
                    runCatching {
                        val relevanceSignals = snap?.context?.let { ctx ->
                            (ctx.runtimeStage?.advanceSignals.orEmpty() +
                                ctx.runtimeStage?.retreatSignals.orEmpty() +
                                ctx.pack.retreatSignals).toSet()
                        }.orEmpty()
                        chatBriefBuilder.build(
                            contactId = c.contactId,
                            latestScreenText = screenBody,
                            goalOverride = goal,
                            stageCode = snap?.context?.stage?.stageCode,
                            packStageCodes = snap?.stageCodes,
                            suppressGoal = snap?.suppressGoal == true,
                            relevanceSignals = relevanceSignals,
                            pathKind = snap?.context?.pack?.pathKind,
                        )
                    }.getOrNull()
                }
            }
            val factsDeferred = async {
                contact?.let { c ->
                    runCatching { fetchContactFacts(c, question.content) }.getOrNull()
                }
            }
            TurnSources(
                core = coreDeferred.await(),
                primed = primedDeferred.await(),
                boundDigest = digestDeferred.await(),
                brief = briefDeferred.await(),
                facts = factsDeferred.await(),
                strategy = strategyDeferred.await(),
            )
        }

        // 落库原文完整，但发给模型的是「瘦身历史」：当前屏全文 + 最近若干屏
        // 缩写 + 更早屏幕的一句场景占位/滚动摘要（见 AdvisorHistory）。
        val history = AdvisorHistory.build(
            messages = allMessages,
            screenLabel = screenLabel,
            digest = if (contact != null) sources.boundDigest else prefs.screenDigest(),
            bound = contact != null,
        )

        // 跨段去重（v4 §8.2，修 D5）：同一事实不在 承诺/画像/脉络/通用召回
        // 四处重复注入。保留优先级：承诺与约定 ＞ 已确认画像 ＞ 交往脉络 ＞
        // 通用笔记。承诺/计划/事件/小结只按 id 去重，不被文本指纹吞掉。
        val dueNotes = sources.facts?.dueNotes.orEmpty()
        val dueIds = dueNotes.mapTo(HashSet()) { it.id }
        val curatedTexts = sources.brief?.curatedTexts.orEmpty()
        val entityKept = sources.facts?.entityNotes
            ?.sortedByDescending { it.whenAt ?: it.updatedAt }
            ?.let { sorted ->
                PromptDedup.dedupeNotes(
                    PromptDedup.filterNotes(
                        notes = sorted,
                        keepIds = dueIds,
                        keepTexts = curatedTexts,
                        protectedKinds = RESERVED_NOTE_KINDS,
                    ),
                    protectedKinds = RESERVED_NOTE_KINDS,
                )
            }
            .orEmpty()
        val reservedIds = dueIds + entityKept.mapTo(HashSet()) { it.id }
        val reservedTexts = curatedTexts +
            dueNotes.map { it.content } +
            entityKept.map { it.content }
        val primedKept = PromptDedup.filterNotes(
            notes = sources.primed,
            keepIds = reservedIds,
            keepTexts = reservedTexts,
        )
        val dealKept = sources.facts?.dealFacts?.let { deals ->
            val keptTexts = PromptDedup.filterTexts(deals.map { it.content }, reservedTexts).toSet()
            deals.filter { it.content in keptTexts }
        }.orEmpty()
        android.util.Log.d(
            TAG,
            "prompt sources: incoming=${incoming != null}, primed=${sources.primed.size}->${primedKept.size}, " +
                "entity=${sources.facts?.entityNotes?.size ?: 0}->${entityKept.size}, " +
                "due=${dueNotes.size}, deal=${sources.facts?.dealFacts?.size ?: 0}->${dealKept.size}",
        )
        sources.strategy?.let { snap ->
            // P2 步骤6：先只观察取数；strategySection/outputProtocol 在步骤7 拼入提示词。
            android.util.Log.d(
                TAG,
                "strategy snapshot: pack=${snap.context.pack.code}, stage=${snap.context.stage.stageCode}, " +
                    "l2=${snap.context.activeL2?.id}, l1=${snap.context.activeL1?.id}, " +
                    "card=${snap.context.card != null}, retreat=${snap.context.retreatHits}, " +
                    "provisional=${snap.provisionalL2?.id}, suppressGoal=${snap.suppressGoal}",
            )
        }

        val memoryContext = MemoryPrompt.context(
            core = sources.core,
            primed = primedKept,
            now = System.currentTimeMillis(),
        )

        // 绑定联系人：交往脉络（到期承诺置顶 + 按人事实 + 成交要件，均已去重）
        // 独立段落拼进 custom（与上游 memoryContext 分轨；取数纪律见 §6.5）。
        val contactSection = contact?.let { c ->
            runCatching {
                ContactBriefSection.render(
                    contact = c,
                    entityNotes = entityKept,
                    dueNotes = dueNotes,
                    dealFacts = dealKept,
                )
            }.getOrNull()
        }
        // 长度档：人设尾句动态生成，软约束（提示词）+ 硬约束（maxTokens）双保险。
        val styleWithLength = ADVISOR_STYLE_BASE +
            "请用简体中文给出简洁、可直接参考的分析，回答控制在 ${settings.answerTargetChars} 字以内，" +
                "不啰嗦、不复述原文。"
        // 即时方向（顶栏「方向」chip）：仅次于总纲的高优先级段落，置于长期
        // 目标/人设 brief 之前；未设置时整段省略，行为与现状一致。
        val directionSection = _direction.value.selected
            ?.takeIf { it.isNotBlank() }
            ?.let { "$DIRECTION_SECTION\n$it\n$DIRECTION_REQUIRE" }
        // P2 策略段（角色/阶段/近期目标/红线/自我约束/素材/态势）+ 两段式输出协议：
        // 仅在策略取景命中（绑定联系人且有场景阶段）时出现，否则链路与 P1 完全一致。
        val strategySnapshot = sources.strategy
        // P3 §七：增强档 + 智能通道回合，主调用之前串行一次战术预分析。
        // 轻通道（lean）不插以控成本；任何失败静默回退一段式（行为退化为档 1）。
        val tacticalBrief = if (
            settings.strategyMode == AdvisorPrefs.STRATEGY_ENHANCED &&
            !lean &&
            strategySnapshot != null
        ) {
            runCatching {
                tacticalAdvisor.preAnalyze(
                    ctx = strategySnapshot.context,
                    latestIncoming = incoming ?: screenBody,
                )
            }.onFailure {
                android.util.Log.d(TAG, "$REASON_ENHANCED_FALLBACK: preAnalyze failed", it)
            }.getOrNull().also {
                if (it == null) android.util.Log.d(TAG, "$REASON_ENHANCED_FALLBACK: empty/rejected brief")
            }
        } else {
            null
        }
        val strategySection = strategySnapshot?.let { snap ->
            runCatching { StrategyPrompt.strategySection(snap.context, tacticalBrief) }
                .onFailure { android.util.Log.d(TAG, "strategySection render failed", it) }
                .getOrNull()
        }
        val outputProtocolSection = if (strategySnapshot != null) {
            StrategyPrompt.outputProtocol()
        } else {
            null
        }
        // 每回合独立一个剥离器（非线程安全，设计为单流使用）。
        val planFilter = strategySnapshot?.let { PlanStreamFilter() }
        val planningStartAt = if (strategySnapshot != null) System.currentTimeMillis() else null
        if (strategySnapshot != null) beginMissionUi() else resetMissionUi()
        // 规约总纲置 system prompt 首位（v4 §8.1）：renderSystemPrompt 会把
        // custom 拼在最前，总纲因此先于上游一切基线条目。
        val customSystemPrompt = listOfNotNull(
            PREAMBLE_SECTION,
            // M3-a：自动档激活（总闸开+联系人白名单内）时追加生成端红线段。
            autoDirectiveSection(),
            styleWithLength,
            strategySection,
            // 保温闲聊段紧跟策略段（KPI 覆盖：自然度优先于任务推进，见 WarmChatPrompt）。
            smalltalk?.let { WarmChatPrompt.section(it) },
            sources.brief?.styleSection,
            directionSection,
            sources.brief?.section,
            contactSection,
            outputProtocolSection,
        )
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        // 直连通道没有上游 renderSystemPrompt 的记忆段：App 侧注入的记忆在这里
        // 以独立段落显式给出（业务能力与 AgentLoop 通道等价）。
        val leanSystemPrompt = listOfNotNull(
            customSystemPrompt,
            memoryContext?.takeIf { it.isNotBlank() }?.let { "$MEMORY_LEAD_IN\n$it" },
        ).joinToString("\n\n")

        val ka = keepAlivePrefs.state.value
        val maxRetries = if (tightRetry) TIGHT_RETRY_TIMES else ka.retryTimes
        val firstTextTimeoutMs =
            (if (tightRetry) TIGHT_RETRY_TIMEOUT_SEC else ka.retryTimeoutSec) * 1000L
        val delayBaseSec = ka.retryDelaySec.coerceAtLeast(1).toLong()

        // 本回合用量（AgentLoop 若触发工具会有多轮 Usage，累加；重试重新计数）。
        var turnInput = 0L
        var turnOutput = 0L
        val usageSink: (Long, Long) -> Unit = { input, output ->
            turnInput += input.coerceAtLeast(0L)
            turnOutput += output.coerceAtLeast(0L)
        }

        try {
            var attempt = 0
            while (true) {
                turnInput = 0L
                turnOutput = 0L
                val outcome = if (lean) {
                    collectLeanOnce(
                        config = boundedConfig,
                        conversationId = conversationId,
                        systemPrompt = leanSystemPrompt,
                        history = history,
                        maxTokens = settings.answerMaxTokens,
                        triggerMessageId = question.id,
                        firstTextTimeoutMs = firstTextTimeoutMs,
                        onUsage = usageSink,
                        planFilter = planFilter,
                        planningStartAt = planningStartAt,
                    )
                } else {
                    collectAdvisorOnce(
                        config = boundedConfig,
                        conversationId = conversationId,
                        history = history,
                        memoryContext = memoryContext,
                        customSystemPrompt = customSystemPrompt,
                        triggerMessageId = question.id,
                        firstTextTimeoutMs = firstTextTimeoutMs,
                        onUsage = usageSink,
                        planFilter = planFilter,
                        planningStartAt = planningStartAt,
                    )
                }
                when (outcome) {
                    is TurnOutcome.Done -> {
                        recordTurnUsage(turnInput, turnOutput, tightRetry, settings.sessionTokenBudget)
                        // P3 §九：依据关键词挂到新参谋气泡（内存态）。
                        if (outcome.assistantMessageId > 0L) {
                            val basis = sources.brief?.basis.orEmpty()
                            if (basis.isNotEmpty()) {
                                _transcript.update { st ->
                                    st.copy(
                                        basisByMessage = st.basisByMessage +
                                            (outcome.assistantMessageId to basis),
                                    )
                                }
                            }
                        }
                        // P2：plan 块解析入库 + 分屏策略条（任务令/观察期轻确认）；
                        // 保温回合走 informational 台账分支（D4）。
                        finalizeMission(
                            snapshot = strategySnapshot,
                            filter = planFilter,
                            conversationId = conversationId,
                            contact = contact,
                            turnMessageId = question.id,
                            smalltalk = smalltalk,
                        )
                        // M1-C Gatekeeper 事前审（方案 §10/§13.2 矛盾#4）：连续模式
                        // 回合（tightRetry=INCOMING）与保温闲聊回合（smalltalk≠null，
                        // §12 以 Gatekeeper AI 腔检测为保温闲聊对策之一）成稿后过审；
                        // 单次手动参谋/追问不挂（M2 随起草档迁移）。拦截走「降级不丢
                        // 弃」：分屏渲染副本换上替换/截断版本与原因，落库原文不动。
                        if (tightRetry || smalltalk != null) {
                            gateTurn(
                                conversationId = conversationId,
                                assistantMessageId = outcome.assistantMessageId,
                                priorReplies = allMessages
                                    .filter { it.role == ChatRole.ASSISTANT }
                                    .takeLast(GATE_REPEAT_WINDOW)
                                    .map { it.content },
                                maxChars = (settings.answerTargetChars * 2)
                                    .coerceAtLeast(GATE_LENGTH_FLOOR),
                            )
                        }
                        // 后台滚动摘要 + 画像萃取：独立协程，不占串行队列、不阻塞 UI。
                        // 上一回合的沉淀仍在跑则跳过（素材每次按全量回合重算，不丢信息）。
                        advisorScope.launch {
                            if (consolidationLock.tryLock()) {
                                try {
                                    runCatching { maybeRefreshDigest(conversationId, contact) }
                                    if (contact != null) {
                                        runCatching { maybeExtractPersona(conversationId, contact) }
                                        // P2 Judge 规则层：本回合 retreat 命中 → 上一回合
                                        // pending 任务令直接 regressed（LLM 层在下回合 T1）。
                                        strategySnapshot?.let { snap ->
                                            runCatching {
                                                val retreated = missionJudge.applyRuleRetreat(
                                                    contactId = contact.contactId,
                                                    pack = snap.context.pack,
                                                    stage = snap.context.stage,
                                                    roadmap = snap.context.roadmap,
                                                    incomingText = incoming ?: screenBody,
                                                )
                                                // P3：连续 retreat×2 → 亮重规划入口（裁决可能已回站，重取当前站码）。
                                                if (retreated) {
                                                    val currentCode = goalRepository
                                                        .stage(contact.contactId, snap.context.pack.code)
                                                        ?.stageCode ?: snap.context.stage.stageCode
                                                    maybeShowRerouteHint(
                                                        contact.contactId,
                                                        snap.context.pack.code,
                                                        currentCode,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    consolidationLock.unlock()
                                }
                            }
                        }
                        return
                    }
                    is TurnOutcome.PartialOutputFailure -> {
                        // 已出文本后失败：计划提示不再停留；plan 即使截到也不入台账（回合未成功）。
                        clearPlanningUi()
                        if (outcome.partial.isNotBlank()) {
                            conversationRepository.append(
                                ChatMessage(
                                    conversationId = conversationId,
                                    role = ChatRole.ASSISTANT,
                                    content = outcome.partial,
                                    createdAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        _transcript.updateStatus(Status.Error(outcome.reason))
                        return
                    }
                    is TurnOutcome.ZeroOutputFailure -> {
                        if (attempt >= maxRetries) {
                            _transcript.updateStatus(Status.Error(outcome.reason))
                            return
                        }
                        attempt++
                        _transcript.updateStatus(Status.Retrying(attempt, maxRetries))
                        // 递增等待：第 n 次重试前等 base × n（5、10、15…），
                        // 可取消 delay 不占线程，turnMutex 内安全。
                        delay(delayBaseSec * 1000L * attempt)
                    }
                }
            }
        } finally {
            _transcript.update { it.copy(streaming = null) }
        }
    }

    /**
     * 绑定联系人脉络段的并行取数（设计文档 §6.5–6.6，T1 并发的子步骤）：
     * ① recallByEntity 按主名+别名直取该联系人的事实（天然防串档）；
     * ② ContactMemoryReader 按人只读取窗内承诺/约见（AGREEMENT/PLAN，
     *   不 touch、不强化，避开上游 upcoming() 的全局截断与权重反写）；
     * ③ 当前屏出现成交信号时，跨该联系人全部会话检索成交要件。
     * 三路互不依赖、同屏并发；全部只读、feedback=false；去重与渲染由调用方
     * 在并行块收口后统一完成。
     */
    private suspend fun fetchContactFacts(contact: ContactRef, currentScreen: String): ContactFacts {
        val names = (listOf(contact.displayName) + contact.aliases)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(ENTITY_NAME_LIMIT)
        val now = System.currentTimeMillis()
        return coroutineScope {
            val entityDeferred = async {
                if (names.isEmpty()) {
                    emptyList()
                } else {
                    runCatching {
                        memoryRepository.recallByEntity(
                            entities = names,
                            limit = ENTITY_NOTES,
                            feedback = false,
                        )
                    }.getOrDefault(emptyList()).map { it.note }
                }
            }
            val dueDeferred = async {
                runCatching { memoryReader.dueFor(names, now = now) }
                    .getOrDefault(emptyList())
                    .take(DUE_NOTES)
            }
            val dealDeferred = async {
                if (!historyGateway.hasClosingSignal(stripScreenLabel(currentScreen))) {
                    emptyList()
                } else {
                    val ids = runCatching { contactDirectory.conversationIds(contact.contactId) }
                        .getOrDefault(emptyList())
                    runCatching { historyGateway.dealFacts(ids) }.getOrDefault(emptyList())
                }
            }
            ContactFacts(
                entityNotes = entityDeferred.await(),
                dueNotes = dueDeferred.await(),
                dealFacts = dealDeferred.await(),
            )
        }
    }

    /**
     * 滚动摘要（总开关/频率读 AdvisorPrefs）：间隔满足时把最近
     * [DIGEST_TURN_WINDOW] 个已完成回合（屏幕正文/参谋意见各截
     * [DIGEST_ITEM_CHARS] 字）压成 ≤[DIGEST_MAX_CHARS] 字的连续背景。
     * 绑定联系人存 session_bindings（经 [ContactDirectory]），共享会话存
     * AdvisorPrefs。任何失败都静默保留旧摘要，不影响主回合。手动追问不计入。
     */
    private suspend fun maybeRefreshDigest(conversationId: Long, contact: ContactRef?) {
        if (!prefs.state.value.digestEnabled) return
        val stored = contact?.let { c ->
            runCatching { contactDirectory.digest(c.contactId) }.getOrNull()
        }
        // 绑定联系人首次无摘要时计数从 0 起；绝不继承共享会话的覆盖回合数，
        // 否则旧共享会话用得越多，新联系人的滚动摘要越被永久压制。
        val coveredTurns = when {
            contact == null -> prefs.digestCoveredTurns()
            stored != null -> stored.coveredTurns
            else -> 0
        }
        // 绝对回合序号走 COUNT 查询（不装载消息实体）；素材只取最近窗口的配对。
        val total = historyGateway.countScreenTurns(conversationId, screenLabel)
        if (total - coveredTurns < prefs.state.value.digestEveryTurns) return
        val screenTurns = historyGateway.recentScreenTurns(conversationId, screenLabel)

        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull() ?: return
        val previousDigest = if (contact != null) stored?.digest else prefs.screenDigest()
        val material = buildString {
            previousDigest?.let { append("已有摘要：").append(it).append("\n\n") }
            screenTurns.takeLast(DIGEST_TURN_WINDOW).forEachIndexed { index, turn ->
                append("屏幕").append(index + 1).append("：")
                append(stripScreenLabel(turn.screenContent).take(DIGEST_ITEM_CHARS)).append('\n')
                append("参谋：").append(turn.replyContent.take(DIGEST_ITEM_CHARS)).append("\n\n")
            }
        }
        val digest = auxiliaryTasks.complete(
            config = config,
            systemPrompt = if (contact != null) DIGEST_SYSTEM_BOUND else DIGEST_SYSTEM,
            userPrompt = material,
            maxTokens = 220,
        ).take(DIGEST_MAX_CHARS)
        if (digest.isNotBlank()) {
            if (contact != null) {
                runCatching { contactDirectory.saveDigest(contact.contactId, digest, total) }
            } else {
                prefs.setScreenDigest(digest, total)
            }
        }
    }

    /**
     * 对方画像萃取（§4.2）：总开关与节流频率读 AdvisorPrefs；字段目录/
     * 草稿冷却在 [PersonaExtractor] 与 ContactRepository 内完成，这里只
     * 负责组装最近窗口的回合文本。任何失败静默，不影响参谋主链路；
     * 草稿落库后由画像页供用户 ✓/✕。
     */
    private suspend fun maybeExtractPersona(conversationId: Long, contact: ContactRef) {
        if (!prefs.state.value.extractEnabled) return
        val settings = prefs.state.value
        val turns = historyGateway.recentScreenTurns(conversationId, screenLabel).map { turn ->
            PersonaExtractor.Turn(
                screen = stripScreenLabel(turn.screenContent),
                reply = turn.replyContent,
                at = turn.screenCreatedAt,
            )
        }
        personaExtractor.maybeExtract(
            contactId = contact.contactId,
            conversationId = conversationId,
            turns = turns,
            everyTurns = settings.extractEveryTurns,
        )
    }

    /**
     * 完成回合的用量入账：累计持久化 + 面板小字；连续模式下若本次会话用量
     * 达到预算上限，发出自动停录请求（在回合落库之后，不丢这条回答）。
     */
    private fun recordTurnUsage(
        input: Long,
        output: Long,
        backgroundTurn: Boolean,
        sessionBudget: Long,
    ) {
        prefs.recordTurnUsage(input, output)
        _transcript.update { it.copy(lastUsage = TurnTokens(input, output)) }
        if (backgroundTurn && sessionBudget > 0L) {
            val used = prefs.tokenStats.value.sessionTotal
            if (used >= sessionBudget) _budgetExceeded.tryEmit(Unit)
        }
    }

    /**
     * 精简直连通道的单轮流式收集：无工具，故只有一轮，事件处理与
     * [collectAdvisorOnce] 同构（首字超时→可重试；已出文本后失败→保 partial）。
     */
    private suspend fun collectLeanOnce(
        config: ProviderConfig,
        conversationId: Long,
        systemPrompt: String,
        history: List<ChatMessage>,
        maxTokens: Int,
        triggerMessageId: Long,
        firstTextTimeoutMs: Long,
        onUsage: (Long, Long) -> Unit,
        planFilter: PlanStreamFilter? = null,
        planningStartAt: Long? = null,
    ): TurnOutcome = kotlinx.coroutines.coroutineScope {
        val chunks = responder.stream(
            config = config,
            systemPrompt = systemPrompt,
            messages = history,
            maxTokens = maxTokens,
        ).produceIn(this)

        val live = StringBuilder()
        var sawText = false
        var failureReason: String? = null
        // 正文首字揭示：策略回合在首段可见输出到达后，保证「正在定本轮策略」
        // 最短展示 PLAN_HINT_MIN_MS 再清除，避免提示一闪而过。
        var bodyRevealed = false
        var pendingReveal = false
        suspend fun revealBody() {
            if (bodyRevealed) return
            bodyRevealed = true
            if (planFilter != null && planningStartAt != null) {
                val elapsed = System.currentTimeMillis() - planningStartAt
                if (elapsed < PLAN_HINT_MIN_MS) delay(PLAN_HINT_MIN_MS - elapsed)
            }
            clearPlanningUi()
        }

        fun handle(chunk: AdvisorResponder.Chunk) {
            when (chunk) {
                is AdvisorResponder.Chunk.Delta -> {
                    sawText = true
                    val visible = planFilter?.feed(chunk.text) ?: chunk.text
                    if (visible.isNotEmpty()) {
                        live.append(visible)
                        pendingReveal = true
                        _transcript.update { it.copy(streaming = live.toString()) }
                    }
                }
                is AdvisorResponder.Chunk.Usage -> onUsage(chunk.inputTokens, chunk.outputTokens)
                is AdvisorResponder.Chunk.Failure -> failureReason = chunk.reason
                AdvisorResponder.Chunk.Done -> Unit
            }
        }

        try {
            withTimeout(firstTextTimeoutMs) {
                while (true) {
                    val result = chunks.receiveCatching()
                    if (result.isClosed) break
                    handle(result.getOrThrow())
                    if (pendingReveal) {
                        revealBody()
                        pendingReveal = false
                    }
                    if (sawText) break
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            chunks.cancel()
            return@coroutineScope TurnOutcome.ZeroOutputFailure(REASON_FIRST_TEXT_TIMEOUT)
        } catch (cancelled: CancellationException) {
            chunks.cancel()
            throw cancelled
        } catch (error: Exception) {
            chunks.cancel()
            return@coroutineScope if (sawText) {
                TurnOutcome.PartialOutputFailure(error.message ?: "unknown", live.toString())
            } else {
                TurnOutcome.ZeroOutputFailure(error.message ?: REASON_NETWORK)
            }
        }

        if (!sawText) {
            return@coroutineScope TurnOutcome.ZeroOutputFailure(failureReason ?: REASON_NO_RESPONSE)
        }
        try {
            for (chunk in chunks) {
                handle(chunk)
                if (pendingReveal) {
                    revealBody()
                    pendingReveal = false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@coroutineScope TurnOutcome.PartialOutputFailure(
                error.message ?: "unknown",
                live.toString(),
            )
        }
        if (failureReason != null && live.isBlank()) {
            return@coroutineScope TurnOutcome.ZeroOutputFailure(failureReason ?: REASON_NO_RESPONSE)
        }
        if (failureReason != null) {
            return@coroutineScope TurnOutcome.PartialOutputFailure(failureReason ?: "unknown", live.toString())
        }
        // 未闭合 plan 的截留文本在此补还（endStream），保证落库消息完整。
        planFilter?.endStream()?.takeIf { it.isNotEmpty() }?.let { tail ->
            live.append(tail)
            _transcript.update { it.copy(streaming = live.toString()) }
        }
        val assistantMessageId = conversationRepository.append(
            ChatMessage(
                conversationId = conversationId,
                role = ChatRole.ASSISTANT,
                content = live.toString(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        _transcript.update { it.copy(streaming = null, status = Status.Idle) }
        _turnCompletions.tryEmit(Unit)
        TurnOutcome.Done(assistantMessageId)
    }

    /**
     * 两阶段收集：首个文本事件（TextDelta/ThinkingDelta/AssistantMessage）
     * 在 [firstTextTimeoutMs] 内必须到达，之后流式总时长不限。超时即取消并
     * 丢弃整条上游 flow——防止迟到回答在重试后重复落库。
     */
    private suspend fun collectAdvisorOnce(
        config: ProviderConfig,
        conversationId: Long,
        history: List<ChatMessage>,
        memoryContext: String?,
        customSystemPrompt: String,
        triggerMessageId: Long,
        firstTextTimeoutMs: Long,
        onUsage: (Long, Long) -> Unit,
        planFilter: PlanStreamFilter? = null,
        planningStartAt: Long? = null,
    ): TurnOutcome = kotlinx.coroutines.coroutineScope {
        val events: ReceiveChannel<AgentEvent> = agentLoop.run(
            config = config,
            mode = AppMode.CHAT,
            customSystemPrompt = customSystemPrompt,
            history = history,
            conversationId = conversationId,
            memoryContext = memoryContext,
            triggerMessageId = triggerMessageId,
        ).produceIn(this)

        val live = StringBuilder()
        var sawText = false
        var completed = false
        var completedMessageId: Long? = null
        var failureReason: String? = null
        // 正文首字揭示（与 lean 通道同口径）；思考过程不算正文。
        var bodyRevealed = false
        suspend fun revealBody() {
            if (bodyRevealed) return
            bodyRevealed = true
            if (planFilter != null && planningStartAt != null) {
                val elapsed = System.currentTimeMillis() - planningStartAt
                if (elapsed < PLAN_HINT_MIN_MS) delay(PLAN_HINT_MIN_MS - elapsed)
            }
            clearPlanningUi()
        }

        suspend fun handle(event: AgentEvent) {
            when (event) {
                is AgentEvent.TextDelta -> {
                    sawText = true
                    val visible = planFilter?.feed(event.text) ?: event.text
                    if (visible.isNotEmpty()) {
                        live.append(visible)
                        _transcript.update { it.copy(streaming = live.toString()) }
                        revealBody()
                    }
                }
                // 推理模型的思考过程也算「活信号」：思考超过首字超时不应误重试。
                is AgentEvent.ThinkingDelta -> sawText = true
                is AgentEvent.AssistantMessage -> {
                    sawText = true
                    // 上游最终消息是完整文本：用一次性剥离去掉 <plan> 块（增量剥离可能跨 chunk）。
                    val visibleContent = if (planFilter != null) {
                        PlanStreamFilter.split(event.message.content).first
                    } else {
                        event.message.content
                    }
                    if (visibleContent.isNotBlank()) {
                        completed = true
                        completedMessageId = conversationRepository.append(
                            event.message.copy(
                                conversationId = conversationId,
                                content = visibleContent,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        live.setLength(0)
                        _transcript.update { it.copy(streaming = null, status = Status.Idle) }
                        _turnCompletions.tryEmit(Unit)
                    } else if (live.isNotBlank()) {
                        // 末条消息为空但已有流式文本：以累积文本为准落库，
                        // 不写空气泡、不丢已生成内容。
                        completed = true
                        completedMessageId = conversationRepository.append(
                            ChatMessage(
                                conversationId = conversationId,
                                role = ChatRole.ASSISTANT,
                                content = live.toString(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        live.setLength(0)
                        _transcript.update { it.copy(streaming = null, status = Status.Idle) }
                        _turnCompletions.tryEmit(Unit)
                    } else {
                        // 空消息且此前没有任何输出：按零文本失败处理（可重试）。
                        failureReason = failureReason ?: REASON_NO_RESPONSE
                    }
                }
                is AgentEvent.Usage ->
                    onUsage(event.promptTokens ?: 0L, event.completionTokens ?: 0L)
                is AgentEvent.Failed -> failureReason = event.error.message
                else -> Unit
            }
        }

        // 阶段 1：等首个文本事件（RoundStarted/ToolCall 等不算活信号）。
        // 注意 TimeoutCancellationException 是 CancellationException 的子类，
        // 必须先于后者 catch，否则超时会被误当外部取消向上抛。
        try {
            withTimeout(firstTextTimeoutMs) {
                while (true) {
                    val result = events.receiveCatching()
                    if (result.isClosed) break
                    handle(result.getOrThrow())
                    if (sawText) break
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            events.cancel()
            return@coroutineScope TurnOutcome.ZeroOutputFailure(REASON_FIRST_TEXT_TIMEOUT)
        } catch (cancelled: CancellationException) {
            events.cancel()
            throw cancelled
        } catch (error: Exception) {
            events.cancel()
            return@coroutineScope if (sawText) {
                TurnOutcome.PartialOutputFailure(error.message ?: "unknown", live.toString())
            } else {
                TurnOutcome.ZeroOutputFailure(error.message ?: REASON_NETWORK)
            }
        }

        if (!sawText) {
            return@coroutineScope TurnOutcome.ZeroOutputFailure(
                failureReason ?: REASON_NO_RESPONSE,
            )
        }

        // 阶段 2：已有文本，剩余流式不再限时，多长都等完。
        try {
            for (event in events) handle(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@coroutineScope TurnOutcome.PartialOutputFailure(
                error.message ?: "unknown",
                live.toString(),
            )
        }

        val reason = failureReason
        when {
            completed -> TurnOutcome.Done(completedMessageId ?: 0L)
            reason != null -> TurnOutcome.PartialOutputFailure(reason, live.toString())
            // 兜底：上游有文本流出却没给 AssistantMessage 收尾，保住已生成内容。
            live.isNotBlank() -> {
                planFilter?.endStream()?.takeIf { it.isNotEmpty() }?.let { live.append(it) }
                val fallbackId = conversationRepository.append(
                    ChatMessage(
                        conversationId = conversationId,
                        role = ChatRole.ASSISTANT,
                        content = live.toString(),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                _transcript.update { it.copy(streaming = null, status = Status.Idle) }
                _turnCompletions.tryEmit(Unit)
                TurnOutcome.Done(fallbackId)
            }
            else -> TurnOutcome.ZeroOutputFailure(REASON_NO_RESPONSE)
        }
    }

    /** 找到（或新建）未绑定场景共享的持久化「老参谋」会话。 */
    private suspend fun ensureSharedConversation(): Long {
        val existing = prefs.conversationId().takeIf { it > 0 }
            ?.let { id -> runCatching { conversationRepository.get(id) }.getOrNull() }
        val conversationId = existing?.id
            ?: conversationRepository.create(
                agentId = null,
                mode = AppMode.CHAT,
                title = context.getString(R.string.float_ask_conversation_title),
            ).also { id -> prefs.setConversationId(id) }
        subscribeConversation(conversationId)
        return conversationId
    }

    /** 切换转录订阅到目标会话（共享会话与联系人专属会话共用）。 */
    private fun subscribeConversation(conversationId: Long) {
        if (_transcript.value.conversationId == conversationId && observeJob != null) return
        observeJob?.cancel()
        // 先切状态再挂收集器：若收集器先发射、状态后更新，会话守卫会把首次
        // 发射整条丢弃，导致 missions 停留为上一会话的数据；同时清掉全部
        // 会话相关瞬态，杜绝跨会话泄漏（missions/依据/重生成名额/消息）。
        _transcript.update {
            it.copy(
                conversationId = conversationId,
                messages = emptyList(),
                missions = emptyList(),
                basisByMessage = emptyMap(),
                regeneratedTurns = emptySet(),
            )
        }
        observeJob = advisorScope.launch {
            launch {
                conversationRepository.observeMessages(conversationId).collect { messages ->
                    _transcript.update {
                        if (it.conversationId == conversationId) {
                            it.copy(messages = applyGateOverlays(messages))
                        } else {
                            it
                        }
                    }
                }
            }
            launch {
                // P3 §九：任务令 pending/已采纳驱动参谋气泡行内「✓采纳」。
                goalRepository.observeRecentMissionsForConversation(conversationId).collect { rows ->
                    _transcript.update { st ->
                        if (st.conversationId != conversationId) {
                            st
                        } else {
                            st.copy(
                                missions = rows.map {
                                    MissionMark(
                                        id = it.id,
                                        pending = it.result == MISSION_PENDING,
                                        adoptedIndex = it.adoptedIndex,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * M2-a 起草通知点开（方案 §8.1 N4）：把分屏面板切到代拟会话并订阅，
     * 供预览草稿与「改后发送」编辑。已绑定同一联系人时只确保订阅，不重置
     * 面板瞬态；联系人档案信息由订阅的消息流自然带出，这里不回查。
     */
    fun showDraftConversation(conversationId: Long, contactId: Long, contactName: String) {
        if (_activeContact.value?.contactId == contactId) {
            subscribeConversation(conversationId)
            return
        }
        _activeContact.value = ContactRef(
            contactId = contactId,
            displayName = contactName,
            aliases = emptyList(),
            goal = null,
            style = null,
        )
        switchDirection("c:$contactId")
        subscribeConversation(conversationId)
    }

    /** 浮窗关闭时清掉流式/状态等瞬态，会话与消息保持原样。 */
    fun resetTransient() {
        _transcript.update { it.copy(streaming = null, status = Status.Idle) }
    }

    // ── M1-C：Gatekeeper 事前审（方案 §5.3 critic·事前 / §10 / §13.2 矛盾#4） ──

    /**
     * 分屏降级渲染层：assistantMessageId → 降级后的气泡文本。落库原文不动
     * （M1 无发送概念，起草档 M2 才存在；与 AdvisorHistory「只改发送副本」
     * 同一纪律），只在消息观察者的发射路径上套一层。仅内存、有界（最旧淘汰），
     * 消息 id 全局唯一，会话切换后依旧生效。
     */
    private val gateOverlays = LinkedHashMap<Long, String>()

    /**
     * 对一个已成稿回合执行事前审：确定性三查（红线/超长/重复）同步先行——
     * 纯函数零成本，命中立刻挂降级层，抢在 Room 观察者首次发射之前；未命中
     * 再在后台发起轻 LLM 审（≤120tok，只看 AI 腔/换皮重复）。批准偏置
     * （方案 §5.3）：LLM 审任何失败一律放行，只拦阻断项，不挑刺文风。
     */
    private suspend fun gateTurn(
        conversationId: Long,
        assistantMessageId: Long,
        priorReplies: List<String>,
        maxChars: Int,
    ) {
        if (assistantMessageId <= 0L) return
        // 本回合成稿是会话最新一条消息（回合内无并发写入）；LIMIT 1 有界读取。
        val draft = runCatching {
            historyGateway.recentMessages(conversationId, 1).lastOrNull()?.content.orEmpty()
        }.getOrDefault("")
        if (draft.isBlank()) return
        val rejection = gatekeeper.deterministicReview(draft, priorReplies, maxChars)
        if (rejection != null) {
            applyGateRejection(assistantMessageId, draft, rejection)
            return
        }
        advisorScope.launch {
            val llm = runCatching {
                gatekeeper.llmReview(draft, priorReplies.takeLast(GATE_LLM_PRIOR_WINDOW))
            }.getOrNull() ?: return@launch
            applyGateRejection(assistantMessageId, draft, llm)
        }
    }

    /** 拒绝落地：原因码映射用户文案 + 替换/截断文本挂降级层，并刷新当前分屏。 */
    private fun applyGateRejection(
        messageId: Long,
        original: String,
        rejection: GateRejection,
    ) {
        val body = when {
            rejection.replacement == null -> original
            rejection.replacement.isNotBlank() -> rejection.replacement
            else -> ""
        }
        val reasonText = context.getString(
            when (rejection.reason) {
                GateReasons.BLACKLIST -> R.string.float_gate_reason_blacklist
                GateReasons.TOO_LONG -> R.string.float_gate_reason_too_long
                GateReasons.REPETITIVE -> R.string.float_gate_reason_repetitive
                GateReasons.AUTO_MONEY -> R.string.float_gate_reason_auto_money
                GateReasons.AUTO_PROMISE -> R.string.float_gate_reason_auto_promise
                GateReasons.AUTO_PRIVACY -> R.string.float_gate_reason_auto_privacy
                GateReasons.AUTO_OVERSTEP -> R.string.float_gate_reason_auto_overstep
                else -> R.string.float_gate_reason_ai_tone
            },
        )
        val notice = context.getString(R.string.float_gate_notice, reasonText)
        val text = if (body.isBlank()) {
            "$notice\n\n" + context.getString(R.string.float_gate_blocked_empty)
        } else {
            "$notice\n\n$body"
        }
        synchronized(gateOverlays) {
            gateOverlays[messageId] = text
            while (gateOverlays.size > GATE_OVERLAY_CAP) {
                gateOverlays.remove(gateOverlays.keys.first())
            }
        }
        _transcript.update { st -> st.copy(messages = applyGateOverlays(st.messages)) }
    }

    /** 消息观察者的降级渲染：命中降级层的气泡换文本，其余原样。 */
    private fun applyGateOverlays(messages: List<ChatMessage>): List<ChatMessage> {
        if (gateOverlays.isEmpty()) return messages
        val snapshot = synchronized(gateOverlays) { gateOverlays.toMap() }
        return messages.map { message ->
            snapshot[message.id]?.let { message.copy(content = it) } ?: message
        }
    }

    // ── M3-a：自动档条件注入（总闸开 + 联系人在白名单内才生效） ────────────

    /**
     * 自动档激活判定（与 ActionDispatcher 的 DispatchGate 同口径）：总闸开启且
     * 当前回合联系人在白名单内。未激活返回 null（提示词/台账零改动）。
     */
    private fun autoModeActive(): Boolean {
        val settings = prefs.state.value
        val contactId = _activeContact.value?.contactId ?: return false
        return settings.autoModeEnabled && contactId in settings.autoWhitelistIds
    }

    /** 生成端红线段（规约总纲之后条件拼接，见 GateRules.autoForbiddenSection）。 */
    private fun autoDirectiveSection(): String? =
        if (autoModeActive()) GateRules.autoForbiddenSection() else null

    /** 任务令 forbidden 台账合并：自动档激活时追加三条固定红线（distinct 去重）。 */
    private fun autoForbiddenMerged(planForbidden: List<String>): List<String> =
        if (autoModeActive()) {
            (planForbidden + GateRules.AUTO_FORBIDDEN_ITEMS).distinct()
        } else {
            planForbidden
        }

    // ── P3 §九：行内纠偏（采纳 / 长按三档重写 / 记错了） ─────────────────

    /**
     * 长按建议条的三档固定反馈：只用固定中文修正指令，不新造 LLM 自由文本。
     * 指令拼进重写 system prompt，走轻通道（无工具直连）即时重生成。
     */
    enum class CorrectionKind(val directive: String) {
        TOO_DIRECT(
            "上一版太直接了。请更含蓄委婉：先共情接住对方情绪，再用商量、留余地的方式表达，" +
                "避免命令式和结论式措辞，不要一上来就亮底牌。",
        ),
        TOO_SOFT(
            "上一版太软了。请更明确、有立场地表达诉求与边界，态度友好但不含糊，" +
                "给出具体可执行的说法。",
        ),
        CHANGE_DIRECTION(
            "上一版方向不对。请换一个角度：先判断对方真正的顾虑或诉求，" +
                "给出与上一版明显不同的新思路，不要沿着上一版的说法小修小补。",
        ),
    }

    /**
     * 行内「✓采纳」：回写本会话最新 pending 任务令的 adoptedIndex（1 起）。
     * 复制该条正文由 UI 负责；无 pending 任务令时静默不写。
     */
    suspend fun adoptSuggestion(index: Int) {
        val conversationId = _transcript.value.conversationId
        if (conversationId <= 0L) return
        val mission = goalRepository.latestPendingMissionForConversation(conversationId) ?: return
        goalRepository.adoptMission(mission.id, index)
    }

    /** 长按三档入口：串行队列内轻通道重写，每回合限一次（名额失败时退回）。 */
    fun regenerateSuggestionAsync(kind: CorrectionKind) {
        advisorScope.launch {
            turnMutex.withLock {
                runCatching { regenerateSuggestion(kind) }
                    .onFailure { Log.e(TAG, REASON_REGENERATE_FAILED, it) }
            }
        }
    }

    private suspend fun regenerateSuggestion(kind: CorrectionKind) {
        val st0 = _transcript.value
        val conversationId = st0.conversationId
        if (conversationId <= 0L || st0.status !is Status.Idle) return
        val messages = st0.messages.takeIf { it.isNotEmpty() }
            ?: historyGateway.recentMessages(conversationId, HISTORY_LOAD_MESSAGES)
        val lastUser = messages.lastOrNull { it.role == ChatRole.USER } ?: return
        if (lastUser.id in st0.regeneratedTurns) return
        val priorReply = messages.lastOrNull { it.role == ChatRole.ASSISTANT }
            ?.content?.takeIf { it.isNotBlank() } ?: return

        val settings = prefs.state.value
        val boundedConfig = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
            ?.copy(maxTokens = settings.answerMaxTokens)
            ?: run {
                _transcript.updateStatus(Status.Error(REASON_NO_CONFIG))
                return
            }

        // 先占名额：重复长按只生效一次；下方失败路径会退回。
        _transcript.update { it.copy(regeneratedTurns = it.regeneratedTurns + lastUser.id) }
        _transcript.updateStatus(Status.Thinking)
        val oldMission = goalRepository.latestPendingMissionForConversation(conversationId)
        try {
            // 原任务令作废；重写成功后以新行挂新任务令（adoptedIndex 归零）。
            oldMission?.let { goalRepository.settleMission(it.id, MISSION_IGNORED) }
            // 去掉尾部参谋回复：重写基于「对方来信」重新作答，旧回复仅作对照。
            val history = messages.dropLastWhile { it.role == ChatRole.ASSISTANT }
            val systemPrompt = REGEN_SYSTEM_PROMPT
                .replace(REGEN_DIRECTIVE_PLACEHOLDER, kind.directive)
                .replace(
                    REGEN_PRIOR_PLACEHOLDER,
                    priorReply.take(REGEN_PRIOR_MAX_CHARS),
                )
            val outcome = collectLeanOnce(
                config = boundedConfig,
                conversationId = conversationId,
                systemPrompt = systemPrompt,
                history = history,
                maxTokens = settings.answerMaxTokens,
                triggerMessageId = lastUser.id,
                firstTextTimeoutMs = REGEN_FIRST_TEXT_TIMEOUT_MS,
                onUsage = { _, _ -> },
            )
            if (outcome !is TurnOutcome.Done) {
                // 零文本/部分失败：恢复名额与旧任务令（仍 pending 可采纳），
                // 用户可重试长按或直接采纳旧建议。
                oldMission?.let { goalRepository.settleMission(it.id, MISSION_PENDING) }
                _transcript.update {
                    it.copy(
                        regeneratedTurns = it.regeneratedTurns - lastUser.id,
                        status = Status.Error(REASON_REGENERATE_FAILED),
                    )
                }
                return
            }
            oldMission?.let { old ->
                goalRepository.insertMission(
                    old.copy(
                        id = 0L,
                        result = MISSION_PENDING,
                        adoptedIndex = null,
                        planBlockJson = null,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, REASON_REGENERATE_FAILED, error)
            // 异常路径同样恢复名额与旧任务令（仍 pending 可采纳）。
            oldMission?.let { goalRepository.settleMission(it.id, MISSION_PENDING) }
            _transcript.update {
                it.copy(
                    regeneratedTurns = it.regeneratedTurns - lastUser.id,
                    status = Status.Error(REASON_REGENERATE_FAILED),
                )
            }
        }
    }

    /**
     * 「记错了」：把用户修正写进记忆（走 MemoryRepository 冲突裁决，绝不直删）。
     * @param basis 气泡展示的原依据关键词（仅供拼装备注，不用作删除目标）。
     * @param correction 用户在输入框里确认/编辑后的正确说法。
     * @return true=已写入；false=空内容。
     */
    suspend fun correctBasisMemory(basis: List<String>, correction: String): Boolean {
        val text = correction.trim()
        if (text.isEmpty()) return false
        val conversationId = _transcript.value.conversationId.takeIf { it > 0 }
        val tagged = if (basis.isEmpty()) {
            text
        } else {
            "$text（老参谋依据更正，原依据：${basis.joinToString("、")}）"
        }
        return runCatching {
            memoryRepository.addNote(
                kind = NoteKind.FACT,
                content = tagged,
                importance = 4,
                conversationId = conversationId,
            )
        }.onFailure { Log.e(TAG, "correctBasisMemory failed", it) }.isSuccess
    }

    /** 管道外意外异常（如收集侧崩溃）的兜底上报。 */
    fun reportUnexpected(reason: String) {
        _transcript.updateStatus(Status.Error(reason))
    }

    /** 供设置页调用：清空老参谋会话消息（会话本身保留）。 */
    fun clearConversationAsync() {
        advisorScope.launch { clearConversation() }
    }

    suspend fun clearConversation() {
        val id = prefs.conversationId().takeIf { it > 0 } ?: return
        conversationRepository.clearMessages(id)
        prefs.resetScreenDigest()
        _transcript.update { it.copy(messages = emptyList(), streaming = null, status = Status.Idle) }
    }

    fun advisorScopeCancel() {
        directionJob?.cancel()
        advisorScope.cancel()
    }

    private fun MutableStateFlow<UiState>.updateStatus(status: Status) {
        update { it.copy(status = status) }
    }

    private companion object {
        const val TAG = "ScreenAdvisor"

        const val REASON_NO_TEXT = "no_text"
        const val REASON_NO_CONFIG = "no_config"

        /**
         * 非联系人自动屏幕回合的共享会话复用阈值：当前屏正文与会话内最近
         * 一条屏幕消息的 [FrameQuality.textSimilarity] 不低于该值视为「同一
         * 场景延续」，复用会话；低于则另起新会话（旧会话保留可回看）。
         */
        const val SHARED_REUSE_SIMILARITY = 0.45f

        /**
         * 共享会话回看窗口：当前偏好会话不相似时，再向最近这么多条跨会话
         * 屏幕消息找相似（覆盖 A→B→A 的最近几个场景窗口）。
         */
        const val SHARED_LOOKBACK_TURNS = 12

        /** P3 reroute：同站连续 retreat 命中达此数即建议重规划（不自动）。 */
        const val REROUTE_RETREAT_HITS = 2

        /** 一天的毫秒数（stageDwellDays 换算用）。 */
        const val DAY_MS = 86_400_000L

        /** 即时方向的会话键：共享会话用；绑定联系人拼 `c:<contactId>`。 */
        const val DIRECTION_KEY_SHARED = "shared"

        /** 即时方向拟定时喂给模型的当前屏正文字数上限。 */
        const val DIRECTION_SCREEN_CHARS = 800

        /** AI 拟定方向的最长等待：超时落静态兜底，不卡住下拉菜单。 */
        const val DIRECTION_TIMEOUT_MS = 20_000L

        /** AI 建议方向的长度过滤窗（去序号后）。 */
        const val DIRECTION_MIN_CHARS = 2
        const val DIRECTION_ITEM_MAX_CHARS = 16

        /** 注入系统提示词的即时方向段落（置于长期目标之前）。 */
        const val DIRECTION_SECTION = "【本次参谋的即时方向】"
        const val DIRECTION_REQUIRE =
            "本回合的建议回复必须服务于这个即时方向；若当前屏幕话题偏离，" +
                "也要指出如何把对话自然引回该方向。"

        /** 即时方向 AI 拟定的系统提示词：只输出编号列表。 */
        const val DIRECTION_SYSTEM =
            "你是销售参谋的即时方向助手。根据用户当前聊天屏幕、长期业务目标与已有摘要，" +
                "拟定当下最值得推进的 2~5 个即时方向：每条不超过 12 字、动宾短语、" +
                "互不包含、可直接执行（如「先问出预算区间」）；信息不足时给泛化方向。" +
                "只输出编号列表，每行一条，不要解释、不要寒暄。"

        /** 零文本失败原因（展示层映射成中文文案；均允许重试）。 */
        const val REASON_FIRST_TEXT_TIMEOUT = "first_text_timeout"
        const val REASON_NETWORK = "network"
        const val REASON_NO_RESPONSE = "no_response"

        /** P3 §七：增强档战术预调用失败/被拒（超时/解析失败），静默退化为智能档。 */
        const val REASON_ENHANCED_FALLBACK = "enhanced_fallback"

        /** P3 §九：长按三档重写失败（展示层映射中文文案）。 */
        const val REASON_REGENERATE_FAILED = "regenerate_failed"

        /** P3 §九：长按重写轻通道 system prompt（占位符运行时替换）。 */
        const val REGEN_DIRECTIVE_PLACEHOLDER = "{DIRECTIVE}"
        const val REGEN_PRIOR_PLACEHOLDER = "{PRIOR_REPLY}"
        const val REGEN_PRIOR_MAX_CHARS = 1500
        const val REGEN_FIRST_TEXT_TIMEOUT_MS = 12_000L
        const val REGEN_SYSTEM_PROMPT =
            "你是用户的聊天参谋。用户对上一条建议回复不满意，你要基于聊天记录里对方最后一条来信" +
                "重新给出可直接发送的中文回复。\n\n" +
                "用户的修正要求：\n{DIRECTIVE}\n\n" +
                "上一版回复（仅供对照，不要复述其中的措辞，也不要解释你改了什么）：\n" +
                "{PRIOR_REPLY}\n\n" +
                "输出要求：只给 2~3 条备选回复，每条以「1. 」「2. 」编号开头，" +
                "每条不超过 60 字；不要寒暄、不要分析、不要输出计划或标签。"

        /** 连续（差分）参谋的强制重试预算：坏网络下不堵死帧队列。 */
        const val TIGHT_RETRY_TIMES = 1
        const val TIGHT_RETRY_TIMEOUT_SEC = 8

        /** 「正在定本轮策略…」最短展示时长（避免一闪而过；正文首字到达后补差）。 */
        const val PLAN_HINT_MIN_MS = 400L

        /** 多屏滚动前发 back 收拢 IME 后的复检等待。 */
        const val KEYBOARD_COLLAPSE_WAIT_MS = 300L

        /** L2 OCR 标题带候选名的合理长度（剥角标/群人数在 repository 侧）。 */
        const val TITLE_MIN_CHARS = 2
        const val TITLE_MAX_CHARS = 16

        /** 桌面之外的固定系统壳：来电/系统 UI/设置/授权器等，永不作为聊天窗口建档。 */
        val NON_CHAT_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.incallui",
            "com.android.phone",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
        )

        /** 纯标点/符号行（如返回箭头被识成 "<"）不算联系人名。 */
        val TITLE_SYMBOL_ONLY = Regex("""^[\p{P}\p{S}\s\d]+$""")

        /** 单屏 OCR/无障碍文本的入库上限：150 字结论用不到整篇长文。 */
        const val MAX_SCREEN_CHARS = 3_000

        /** M2-a 代拟回合后回读最新草稿的有界读取条数。 */
        const val DRAFT_LOOKBACK_MESSAGES = 10
        const val PRIMED_NOTES = 4

        /** 召回收窄 query 的字符上限（来信候选 + 目标关键词；terms 还会再截断）。 */
        const val RECALL_QUERY_CHARS = 600

        /**
         * 跨段去重时受保护的笔记类别：承诺/计划/事件/小结只按 id 精确去重，
         * 不被画像值或其他文本的指纹判同吞掉（独立承诺必须各自保留）。
         */
        val RESERVED_NOTE_KINDS = setOf(
            NoteKind.AGREEMENT,
            NoteKind.PLAN,
            NoteKind.EVENT,
            NoteKind.SUMMARY,
        )

        /** recallByEntity 参与匹配的主名+别名数量上限。 */
        const val ENTITY_NAME_LIMIT = 8

        /** 绑定联系人每轮直取的交往脉络笔记上限。 */
        const val ENTITY_NOTES = 6

        /** 置顶的到期承诺/约见上限。 */
        const val DUE_NOTES = 3

        /** 主回合历史的有界读取条数（与 AdvisorHistory.MAX_MESSAGES 对齐）。 */
        const val HISTORY_LOAD_MESSAGES = 60

        /** 保温选题共享会话回看的用户消息条数（与专属会话台账口径一致）。 */
        const val SHARED_USED_MESSAGES = 60

        /**
         * 刷新摘要时回看的最近屏幕回合数。摘要是带旧摘要的增量压缩，
         * 喂 6 个新屏即可接续背景（原 12 屏输入减半，输出质量不变）。
         */
        const val DIGEST_TURN_WINDOW = 6

        /** 摘要素材里屏幕正文/参谋意见各自的截断长度。 */
        const val DIGEST_ITEM_CHARS = 200

        /** 滚动摘要的持久化长度上限。 */
        const val DIGEST_MAX_CHARS = 150

        /** 滚动摘要任务的系统提示词：只输出摘要本身。 */
        const val DIGEST_SYSTEM =
            "你是「老参谋」的看屏记录摘要器。把用户连续看过的屏幕与你此前的参谋意见，" +
                "压缩成不超过 150 字的简体中文连续背景：只保留场景脉络、悬而未决的问题和用户的" +
                "连续意图，丢弃重复屏幕与 OCR 噪声。只输出摘要正文，不要标题、不要分点、" +
                "不要寒暄。"

        /**
         * 绑定联系人会话的摘要提示词：强制带日期与意向阶段、保留报价/承诺/
         * 异议原话要点——它要在跨天回合充当「交往背景」，无时态结论会让模型
         * 对已经转意的客户沿用旧话术（设计文档 §6.4）。
         */
        const val DIGEST_SYSTEM_BOUND =
            "你是销售参谋的对话档案摘要器。把与同一客户的连续聊天屏幕与参谋意见，" +
                "压缩成不超过 150 字的简体中文事实背景：必须以「截至 MM-dd，阶段=X」开头" +
                "（X 取破冰/询盘/比价/犹豫/促单/成交/售后之一，无法判断写未知）；" +
                "随后只保留谈过的型号与价格、我方承诺、对方明确表达的顾虑或意向，" +
                "丢弃寒暄、重复屏幕与 OCR 噪声。只输出摘要正文，不要标题分点与寒暄。"

        /**
         * 规约总纲（v4 §8.1）：system prompt 固定首位，冲突时自上而下取舍。
         * 由 v3 仅绑定联系人段尾部的「转折铁律」升级而来，共享/绑定回合统一生效。
         */
        const val PREAMBLE_SECTION =
            "【规约总纲】\n" +
                "你是用户的随身参谋，依据当前屏幕与以下材料分析，不要提及本规约或材料的组织方式。" +
                "信息冲突时严格按优先级取舍：当前屏幕事实 ＞ 有效承诺与约定 ＞ 历史画像与通用记忆。" +
                "历史只代表当时；当前屏幕出现矛盾信号时一律顺应当前屏——对方由拒绝/犹豫转为意向或" +
                "同意购买时，立即停止说服、辩解或逼单，转入型号、数量、付款方式、交付时间的闭环确认；" +
                "历史脉络中谈过的报价、承诺仍然有效，回复须与之自洽，不得凭空改价或食言。"

        /**
         * 参谋人设骨架：作为 customSystemPrompt 拼进 AgentLoop / 直连通道的系统
         * 提示词。长度档的尾句由调用方动态追加（见 answerTargetChars）。
         */
        const val ADVISOR_STYLE_BASE =
            "你是「老参谋」，用户的随身参谋。用户在手机上轻点了一下悬浮球，" +
                "最近一条用户消息是由 OCR 或无障碍树从当前屏幕提取的文本（可能有识别噪声、排版可能混乱），" +
                "更早的屏幕只以一句场景占位或摘要存在，原始文本已经省略——严禁复述旧屏幕，" +
                "始终以当前屏幕为唯一分析对象；用户手动输入的追问优先于一切屏幕内容。" +
                "若屏幕是对话/聊天，概括各方立场并给出建议的回应要点；若是文章/资料，提炼要点；" +
                "若是操作界面，说明当前状态与下一步选项。"

        /** 直连通道记忆段的引导语（替代上游 renderSystemPrompt 的英文记忆协议）。 */
        const val MEMORY_LEAD_IN =
            "以下是与当前参谋相关的用户记忆与联系人档案，请在分析时自然参考，" +
                "不要逐条罗列或主动提及记忆的存在："

        // ── M1-C：Gatekeeper 事前审 ─────────────────────────────────────

        /** 重复判定的近期回复取样条数（成稿前的历史快照里取末尾若干条）。 */
        const val GATE_REPEAT_WINDOW = 4

        /** 轻 LLM 审随稿附带的近期回复条数（新→旧取末尾，Gatekeeper 内再截 2 条）。 */
        const val GATE_LLM_PRIOR_WINDOW = 2

        /** 降级渲染层容量上限（最旧淘汰；仅内存，进程重启即清）。 */
        const val GATE_OVERLAY_CAP = 64

        /** 超长判定的字数下限兜底（长度档最小时 2×120 已达此值，防御性保留）。 */
        const val GATE_LENGTH_FLOOR = 240
    }
}
