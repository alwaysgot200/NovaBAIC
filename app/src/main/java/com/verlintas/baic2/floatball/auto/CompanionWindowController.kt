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

package com.verlintas.baic2.floatball.auto

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.verlintas.baic2.R
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.FloatSettingsActivity
import com.verlintas.baic2.floatball.ScreenAdvisor
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.DirectorLogEntity
import com.verlintas.baic2.floatball.advisor.data.ROUTE_NOOP
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_AUTO
import com.verlintas.baic2.floatball.advisor.data.ProactivePolicyEntity
import com.verlintas.baic2.floatball.contact.ContactHistoryGateway
import com.verlintas.baic2.floatball.contact.ContactRef
import com.verlintas.baic2.floatball.director.DirectorRoutes
import com.verlintas.baic2.floatball.director.Gatekeeper
import com.verlintas.baic2.floatball.director.ProactivePolicyRepository
import com.verlintas.baic2.floatball.notify.EscalationNotifier
import com.verlintas.baic2.floatball.notify.NotifyGate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 三档发送决策位（方案 §5.1）：单次/连续（手动档）、自动陪伴窗口、起草档通知在途。
 * 同一联系人同一时刻只允许一档激活；窗口类档位（手动连续/自动窗口）因共用
 * 差分引擎与服务状态机，额外全局互斥。
 */
enum class AdvisorGear {
    /** 无档在途。 */
    NONE,

    /** 手动连续模式（双击差分循环）在途。 */
    MANUAL_CONTINUOUS,

    /** 自动陪伴窗口在途。 */
    COMPANION_WINDOW,

    /** 起草档通知在途（M2 代拟稿等待用户一键发送）。 */
    DRAFT_IN_TRANSIT,
}

/** 一个档位声明：档位 + 归属联系人（null = 共享会话/无归属）。 */
data class GearClaim(val gear: AdvisorGear, val contactId: Long? = null)

/**
 * 三档互斥判定（方案 §12「三档并存的状态混乱」对策 + v2 §13.3 薄弱点收口，
 * 纯函数 JVM 可单测；返回 null = 放行，否则为拒绝原因码）。
 *
 * 规则（以「申请方档位」决定裁决口径）：
 * - 任一方 NONE → 放行；
 * - 申请窗口类（手动连续/自动窗口，差分引擎与服务状态机均为单例）：与在途
 *   窗口类全局互斥（原因以在途方命名）；在途为起草档时仅同联系人（或双方
 *   均无归属的共享会话）互斥（REASON_DRAFT_TRANSIT），他联系人起草不挡窗口；
 * - 申请起草档：按联系人占用——同联系人已有窗口类在途 → REASON_CONTACT_OCCUPIED，
 *   已有起草档 → REASON_DRAFT_TRANSIT；他联系人互不干扰。
 */
object ModeGate {

    const val REASON_MANUAL_ACTIVE = "gear_manual_continuous_active"
    const val REASON_COMPANION_ACTIVE = "gear_companion_active"
    const val REASON_DRAFT_TRANSIT = "gear_draft_in_transit"
    const val REASON_CONTACT_OCCUPIED = "gear_contact_occupied"

    fun vetoReason(current: GearClaim, requested: GearClaim): String? {
        if (requested.gear == AdvisorGear.NONE || current.gear == AdvisorGear.NONE) return null
        val requestedWindowClass = requested.gear == AdvisorGear.MANUAL_CONTINUOUS ||
            requested.gear == AdvisorGear.COMPANION_WINDOW
        val currentWindowClass = current.gear == AdvisorGear.MANUAL_CONTINUOUS ||
            current.gear == AdvisorGear.COMPANION_WINDOW
        val sameContact = current.contactId == requested.contactId ||
            (current.contactId == null && requested.contactId == null)
        return when {
            requestedWindowClass && currentWindowClass ->
                // 窗口类全局互斥：以在途方命名原因（提示谁占着引擎）。
                when (current.gear) {
                    AdvisorGear.MANUAL_CONTINUOUS -> REASON_MANUAL_ACTIVE
                    else -> REASON_COMPANION_ACTIVE
                }
            requestedWindowClass -> if (sameContact) REASON_DRAFT_TRANSIT else null
            currentWindowClass -> if (sameContact) REASON_CONTACT_OCCUPIED else null
            else -> if (sameContact) REASON_DRAFT_TRANSIT else null
        }
    }
}

/**
 * 拟人节奏（方案 §8.2）：发送前随机延迟，均匀分布 8~90 秒（纯函数 JVM 可单测）。
 * 延迟在协程内 sleep，绝不碰主线程；调用方在 sleep 后须复查窗口仍在途。
 */
object HumanPace {

    const val MIN_DELAY_MS = 8_000L
    const val MAX_DELAY_MS = 90_000L

    /** [minMs, maxMs] 闭区间均匀采样；区间倒置自动纠正，等长区间返回该值。 */
    fun randomDelayMs(
        random: Random = Random.Default,
        minMs: Long = MIN_DELAY_MS,
        maxMs: Long = MAX_DELAY_MS,
    ): Long {
        val lo = minOf(minMs, maxMs)
        val hi = maxOf(minMs, maxMs)
        if (lo == hi) return lo
        val span = hi - lo
        val offset = (span + 1).toDouble() * random.nextDouble()
        return lo + offset.toLong().coerceIn(0L, span)
    }
}

/** 单窗口发送上限判定（纯函数）：[maxSends] ≤ 0 视为不限。 */
object WindowQuota {

    fun sendAllowed(sentCount: Int, maxSends: Int): Boolean =
        maxSends <= 0 || sentCount < maxSends
}

/** 窗口收尾汇总快照（方案 §8.1 W4：N 草稿 / M 已发送 / K 拦截）。 */
data class WindowTally(
    val drafts: Int = 0,
    val sent: Int = 0,
    val blocked: Int = 0,
    val degraded: Int = 0,
)

/**
 * 档位生命周期台账事件（方案 §12「三档并存的状态混乱」对策：director_logs
 * 记录档位）：陪伴窗口的开启/结束/被互斥拒绝落 trigger=auto 台账行，事件类型
 * 由 reason 承载（回放口径同 director_logs.reason 的规则键）。route 恒为
 * noop——生命周期事件不是回合路由决策，四路由+两旁路建议已收敛不再扩
 * （方案 D6），不为此新增 route 值。
 *
 * 手动连续档（MANUAL_CONTINUOUS）不落生命周期行：其每个差分回合已经由
 * executePrepared 汇聚点写入 trigger=incoming/route=reply 行（M0 设计），
 * 档位在途状态逐回合可见，再加 start/stop 属同信息重复。陪伴窗口落的是
 * 边界行，覆盖「窗口开启但尚无回合产出」与「被互斥拒绝」（后者无任何回合
 * 可查）两类无逐回合兜底的时段。
 */
internal object CompanionLedgerEvents {

    const val START = "companion_start"
    const val END_PREFIX = "companion_end:"
    const val VETOED_PREFIX = "companion_vetoed:"

    /** 接管通报挂起中跳过派发（2026-10-20 通报方案 §2.5，等待主人亲笔）。 */
    const val AWAIT_HUMAN_SKIP = "await_human_skip"

    /** 结束原因键（window_expired/service_stop/service_destroyed…）。 */
    fun endReason(why: String) = END_PREFIX + why

    /** 被拒原因键（拼 ModeGate 的 REASON_* 码，回放可见谁占着档位）。 */
    fun vetoedReason(gearVeto: String) = VETOED_PREFIX + gearVeto

    fun row(contactId: Long, reason: String, nowMs: Long): DirectorLogEntity = DirectorLogEntity(
        contactId = contactId,
        trigger = TRIGGER_AUTO,
        route = ROUTE_NOOP,
        reason = reason,
        createdAt = nowMs,
    )
}

/** 窗口期计数器（线程安全增量，[snapshot] 出只读快照；JVM 可单测）。 */
class WindowTallyAccumulator {

    private val lock = Any()
    private var drafts = 0
    private var sent = 0
    private var blocked = 0
    private var degraded = 0

    fun onDraft() = synchronized(lock) { drafts++ }
    fun onSent() = synchronized(lock) { sent++ }
    fun onBlocked() = synchronized(lock) { blocked++ }
    fun onDegraded() = synchronized(lock) { degraded++ }
    fun reset() = synchronized(lock) {
        drafts = 0; sent = 0; blocked = 0; degraded = 0
    }

    fun snapshot(): WindowTally = synchronized(lock) {
        WindowTally(drafts, sent, blocked, degraded)
    }
}

/**
 * 陪伴窗口控制器（M3-b，方案 §8.1 W1~W4）：自动档的「按纪律发」治理面。
 *
 * 职责与边界：窗口开启校验（总闸/白名单/三档互斥）→ 窗口期观察
 * [ScreenAdvisor.turnCompletions]（差分引擎照常由服务层拉起，本类不碰引擎与
 * 悬浮球）→ 每回合成稿过 Gatekeeper 自动档硬审 → 拟人延迟 → [ActionDispatcher]
 * 派发；单窗口上限/夜间静默/同联系人派发互斥不满足时一律降级起草通知（绝不
 * 丢弃草稿）；窗口到期静默结束并发布汇总通知（「续 30 分钟」按钮回服务重启窗口）。
 *
 * 全程无窗口 UI 依赖；协程全在自有 [windowScope]（Default/IO），主线程零参与。
 */
@Singleton
class CompanionWindowController @Inject constructor(
    private val screenAdvisor: ScreenAdvisor,
    private val historyGateway: ContactHistoryGateway,
    private val contactRepository: ContactRepository,
    private val policyRepository: ProactivePolicyRepository,
    private val dispatcher: ActionDispatcher,
    private val gatekeeper: Gatekeeper,
    private val escalationNotifier: EscalationNotifier,
    private val prefs: AdvisorPrefs,
    private val db: AdvisorDatabase,
    @ApplicationContext private val context: Context,
) {

    /** 窗口开启结果：[STARTED] 之外均为 REASON_* 码，用户文案由服务层映射。 */
    enum class StartResult {
        STARTED,

        /** 总闸未开（DispatchGate 口径）。 */
        REASON_AUTO_DISABLED,

        /** 联系人不在白名单。 */
        REASON_NOT_WHITELISTED,

        /** 三档互斥拒绝（ModeGate 口径）。 */
        REASON_GEAR_CONFLICT,

        /** 联系人无档案（取不到 imPackage，无法兜底派发）。 */
        REASON_NO_CONTACT,
    }

    /** 在途窗口快照（一个控制器同时至多一个窗口——差分引擎单例）。 */
    data class WindowSnapshot(
        val contactId: Long,
        val contactName: String,
        val startedAt: Long,
        val durationMs: Long,
    )

    private val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeWindow = MutableStateFlow<WindowSnapshot?>(null)

    /** 在途窗口观测流（主页状态签 / 自动档复位用；null = 无窗口）。 */
    val activeWindow: StateFlow<WindowSnapshot?> = _activeWindow.asStateFlow()

    private var deadlineJob: Job? = null
    private var pendingDispatchJob: Job? = null
    private var collectorJob: Job? = null

    @Volatile
    private var quietPolicy: ProactivePolicyEntity? = null

    private val tally = WindowTallyAccumulator()

    /** 已处理的成稿消息 id（防同稿二次派发；有界，最旧淘汰）。 */
    private val processedMessageIds = LinkedHashSet<Long>()

    /** 服务层手动连续模式入口的互斥检查用。 */
    fun windowActive(): Boolean = _activeWindow.value != null

    /** 当前在途窗口的联系人 id（无窗口返回 null；互斥判定入参）。 */
    fun activeWindowContactId(): Long? = _activeWindow.value?.contactId

    /** 按 id 重建联系人引用（通知「续 30 分钟」按钮路径；服务层用）。 */
    suspend fun contactRefFor(contactId: Long): ContactRef? = withContext(Dispatchers.IO) {
        val profile = contactRepository.profile(contactId) ?: return@withContext null
        ContactRef(
            contactId = profile.id,
            displayName = profile.displayName,
            aliases = emptyList(),
            goal = profile.goal,
            style = profile.style,
        )
    }

    /**
     * 开启陪伴窗口：总闸/白名单/三档互斥全过才置位窗口状态并挂到期定时。
     * [manualContinuousActive] 由服务层按状态机给定（RECORDING 且非窗口路径）。
     * 会话 id 允许缺省（首轮差分回合会解析绑定），成稿时读实时值。
     */
    suspend fun start(contact: ContactRef, manualContinuousActive: Boolean): StartResult =
        withContext(Dispatchers.IO) {
            if (contact.contactId <= 0L) return@withContext StartResult.REASON_NO_CONTACT
            val settings = prefs.state.value
            when (
                DispatchGate.vetoReason(
                    autoEnabled = settings.autoModeEnabled,
                    whitelist = settings.autoWhitelistIds,
                    contactId = contact.contactId,
                )
            ) {
                DispatchGate.REASON_AUTO_DISABLED ->
                    return@withContext StartResult.REASON_AUTO_DISABLED
                DispatchGate.REASON_NOT_WHITELISTED ->
                    return@withContext StartResult.REASON_NOT_WHITELISTED
            }
            val current = GearClaim(
                gear = when {
                    windowActive() -> AdvisorGear.COMPANION_WINDOW
                    manualContinuousActive -> AdvisorGear.MANUAL_CONTINUOUS
                    else -> AdvisorGear.NONE
                },
                contactId = activeWindowContactId(),
            )
            val requested = GearClaim(AdvisorGear.COMPANION_WINDOW, contact.contactId)
            val veto = ModeGate.vetoReason(current, requested)
            if (veto != null) {
                logGearEvent(contact.contactId, CompanionLedgerEvents.vetoedReason(veto))
                return@withContext StartResult.REASON_GEAR_CONFLICT
            }
            if (contactRepository.profile(contact.contactId) == null) {
                return@withContext StartResult.REASON_NO_CONTACT
            }
            quietPolicy = runCatching { policyRepository.forContact(contact.contactId) }.getOrNull()
            tally.reset()
            synchronized(processedMessageIds) { processedMessageIds.clear() }
            cancelPendingJobs()
            // 窗口时长：复用 AdvisorPrefs 连续模式时长上限（方案 W3），0=不限时按
            // 30 分钟常量封顶（陪伴窗口必须有限期，续期由收尾通知显式发起）。
            val durationMs = prefs.state.value.maxSessionMs.takeIf { it > 0L }
                ?: DEFAULT_WINDOW_MS
            _activeWindow.value = WindowSnapshot(
                contactId = contact.contactId,
                contactName = contact.displayName,
                startedAt = System.currentTimeMillis(),
                durationMs = durationMs,
            )
            logGearEvent(contact.contactId, CompanionLedgerEvents.START)
            ensureCollector()
            deadlineJob = windowScope.launch {
                delay(durationMs)
                finish("window_expired")
            }
            Log.i(TAG, "companion window started: contact=${contact.contactId} duration=${durationMs}ms")
            StartResult.STARTED
        }

    /**
     * 静默结束（方案 §8.1 W4）：取消挂起的延迟派发与到期定时 → 发布收尾汇总
     * 通知（草稿/已发送/拦截计数 + 「续 30 分钟」按钮）。幂等：无窗口或已被
     * 并发结束时 no-op。引擎与悬浮球复位由服务层负责（本类零窗口依赖）。
     */
    fun finish(reason: String) {
        val window = _activeWindow.value ?: return
        if (!_activeWindow.compareAndSet(window, null)) return
        cancelPendingJobs()
        logGearEvent(window.contactId, CompanionLedgerEvents.endReason(reason))
        val summary = tally.snapshot()
        postSummaryNotification(window, summary)
        Log.i(TAG, "companion window finished($reason): $summary")
    }

    /** 服务销毁等场景的静默清理：不发作何通知（进程都要没了）。 */
    fun cancelAllSilently() {
        val window = _activeWindow.value ?: return
        if (!_activeWindow.compareAndSet(window, null)) return
        cancelPendingJobs()
        logGearEvent(window.contactId, CompanionLedgerEvents.endReason("service_destroyed"))
        collectorJob?.cancel()
        collectorJob = null
    }

    private fun cancelPendingJobs() {
        pendingDispatchJob?.cancel()
        pendingDispatchJob = null
        deadlineJob?.cancel()
        deadlineJob = null
    }

    /**
     * 档位生命周期台账（方案 §12，F5）：fire-and-forget 落 director_logs
     * （trigger=auto/route=noop，事件见 [CompanionLedgerEvents]），滚动裁剪
     * 与既有台账同口径。旁路纪律：任何失败只 Log.d，绝不影响窗口控制流——
     * finish/cancel 在主线程调用，落库走自有 [windowScope]，绝不阻塞 UI。
     */
    private fun logGearEvent(contactId: Long, reason: String) {
        windowScope.launch {
            runCatching {
                db.directorLogDao().insert(
                    CompanionLedgerEvents.row(contactId, reason, System.currentTimeMillis()),
                )
                db.directorLogDao().trimFor(contactId)
            }.onFailure { Log.d(TAG, "companion gear log failed", it) }
        }
    }

    /** 回合完成流只挂一次；窗口不在途时为 no-op（手动连续模式零开销旁路）。 */
    private fun ensureCollector() {
        if (collectorJob?.isActive == true) return
        collectorJob = windowScope.launch {
            screenAdvisor.turnCompletions.collect { onTurnCompleted() }
        }
    }

    /**
     * 一回合成稿的窗口治理：只治理窗口联系人自己的回合（期间切到别的联系人的
     * 回合一律不管——那条链路自有起草/保温通知兜底）。成稿即计 draft，随后按
     * 上限/静默/硬审裁决：拦截走台账，通过走拟人延迟后派发，任何不满足走起草
     * 通知降级。
     */
    private suspend fun onTurnCompleted() {
        val window = _activeWindow.value ?: return
        val conversationId = screenAdvisor.transcript.value.conversationId
        if (conversationId <= 0L) return
        val active = screenAdvisor.activeContact.value ?: return
        if (active.contactId != window.contactId) return
        val messages = runCatching {
            historyGateway.recentMessages(conversationId, DRAFT_LOOKBACK)
        }.getOrNull() ?: return
        val draftMessage = messages.lastOrNull { it.role == com.verlintas.baic2.core.model.ChatRole.ASSISTANT }
            ?: return
        // 托管方案 §四.A.1：落库全文（分析+编号+reply 块）确定性抽取「实际回复」，
        // 发送/降级通知只带纯回复；来源码入台账（dispatch/degrade reason:src=）。
        val sendable = SendableDraftExtractor.extract(
            draftMessage.content,
            prefs.state.value.answerTargetChars,
        ) ?: return
        val draft = sendable.text
        synchronized(processedMessageIds) {
            if (!processedMessageIds.add(draftMessage.id)) return
            while (processedMessageIds.size > PROCESSED_CAP) {
                processedMessageIds.remove(processedMessageIds.iterator().next())
            }
        }
        tally.onDraft()
        // 接管通报挂起位（2026-10-20 通报方案 §2.5）：该联系人来信需要用户本人
        // 接管时，草稿绝不自动派发（AI 不得代答），台账留痕等待主人。
        if (escalationNotifier.isAwaitingHuman(window.contactId)) {
            logGearEvent(window.contactId, CompanionLedgerEvents.AWAIT_HUMAN_SKIP)
            return
        }
        val request = AutoSendRequest(
            contactId = active.contactId,
            contactName = active.displayName,
            conversationId = conversationId,
            imPackage = fetchImPackage(active.contactId) ?: return,
            draft = draft,
            turnMessageId = draftMessage.id,
            draftSource = sendable.source,
        )

        // 1) 单窗口发送上限：达上限后其余草稿走起草通知降级（方案 §8.2）。
        val maxSends = prefs.state.value.companionMaxSends
        if (!WindowQuota.sendAllowed(tally.snapshot().sent, maxSends)) {
            degrade(request, REASON_QUOTA_REACHED)
            return
        }
        // 2) 夜间静默：复用 M2-b quiet 口径（跨午夜判定），静默内只出草稿。
        if (inQuietNow()) {
            degrade(request, REASON_QUIET_HOURS)
            return
        }
        // 3) Gatekeeper 自动档硬审：确定性三查+四类词表 → 轻 LLM 审（双重）。
        val priorReplies = messages
            .filter { it.role == com.verlintas.baic2.core.model.ChatRole.ASSISTANT }
            .dropLast(1)
            .takeLast(GATE_REPEAT_WINDOW)
            .map { it.content }
        val maxChars = (prefs.state.value.answerTargetChars * 2).coerceAtLeast(MIN_GATE_CHARS)
        // T1-c：附带军师许可单做越许可检测（裁量层方案 §五）。许可单由
        // runAdvisorTurn 本回合写入 ScreenAdvisor.tacticRulings（90s 缓存共享，
        // 零重复 LLM）；无在途回合/未触发时为 null，走既有 auto 四类硬审兜底。
        val tactics = screenAdvisor.tacticRulingFor(window.contactId)
        // E-f 情绪红线（方案 §4.5 行 11）同口径：盛怒稿玩笑/推进命中也拦，
        // 自动档拒绝 = 降级起草档（tally.onBlocked 同款处置，绝不自动发）。
        val emotion = screenAdvisor.emotionRouteFor(window.contactId)
        val rejection = gatekeeper.deterministicReview(
            draft,
            priorReplies,
            maxChars,
            auto = true,
            tactics = tactics,
            emotion = emotion,
        ) ?: gatekeeper.llmReview(draft, priorReplies)
        if (rejection != null) {
            tally.onBlocked()
            runCatching { dispatcher.logGateBlocked(request, rejection.reason) }
                .onFailure { Log.d(TAG, "gate block log failed", it) }
            return
        }
        // 4) 拟人节奏调度：同一联系人同时只有一个在途延迟任务（方案 §8.2），
        //    在途即降级起草；窗口结束/取消时挂起任务随 cancelPendingJobs 撤销。
        val occupied = synchronized(this) {
            val busy = pendingDispatchJob?.isActive == true
            if (!busy) {
                val snapshot = window
                pendingDispatchJob = windowScope.launch {
                    delay(HumanPace.randomDelayMs())
                    // sleep 后复查：窗口仍是对同一联系人在途，且未滑入静默时段。
                    val still = _activeWindow.value ?: return@launch
                    if (still.contactId != snapshot.contactId) return@launch
                    if (inQuietNow()) {
                        degrade(request, REASON_QUIET_HOURS)
                        return@launch
                    }
                    when (dispatcher.dispatch(request)) {
                        is AutoSendResult.Sent -> tally.onSent()
                        is AutoSendResult.DegradedToDraft -> tally.onDegraded()
                        is AutoSendResult.Rejected -> Unit
                    }
                }
            }
            busy
        }
        if (occupied) degrade(request, REASON_PACE_PENDING)
    }

    /** 降级起草通知（有因）：草稿绝不丢弃，交人肉档一键发送。 */
    private suspend fun degrade(request: AutoSendRequest, reason: String) {
        tally.onDegraded()
        runCatching { dispatcher.degradeToDraft(request, reason) }
            .onFailure { Log.d(TAG, "degrade to draft failed", it) }
    }

    /** 夜间静默判定：联系人 proactive_policies 的 quiet 口径，缺省 22:30~08:30。 */
    private fun inQuietNow(): Boolean {
        val policy = quietPolicy
        val start = policy?.quietStart ?: DEFAULT_QUIET_START
        val end = policy?.quietEnd ?: DEFAULT_QUIET_END
        return DirectorRoutes.inQuietWindow(System.currentTimeMillis(), start, end)
    }

    private suspend fun fetchImPackage(contactId: Long): String? =
        withContext(Dispatchers.IO) { contactRepository.profile(contactId)?.imPackage }

    /**
     * 收尾汇总通知（方案 §8.1 W4）：「陪伴窗口已结束…是否续 30 分钟？」。
     * 点击正文进设置页（最小接线）；「续 30 分钟」按钮经 service action
     * 重启同联系人窗口（与双击连续同一状态机入口）。复用起草渠道（轻打扰）。
     */
    private fun postSummaryNotification(window: WindowSnapshot, summary: WindowTally) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.float_draft_channel),
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                Intent(context, FloatSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val renewIntent = PendingIntent.getForegroundService(
                context,
                RENEW_REQUEST_CODE,
                Intent(context, com.verlintas.baic2.floatball.FloatBallService::class.java)
                    .setAction(ACTION_RENEW_WINDOW)
                    .putExtra(EXTRA_CONTACT_ID, window.contactId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = context.getString(
                R.string.float_companion_end_notif_text,
                summary.drafts,
                summary.sent,
                summary.blocked,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.float_companion_end_notif_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .addAction(0, context.getString(R.string.float_companion_end_renew), renewIntent)
                .setAutoCancel(true)
                .build()
            NotifyGate.notifyIfPermitted(context, NOTIFICATION_ID, notification)
        }.onFailure { Log.d(TAG, "companion summary notification failed", it) }
    }

    companion object {
        private const val TAG = "CompanionWindow"

        /** 与 M2 起草通知同渠道分 id 发（渠道语义=参谋代拟，文案归一）。 */
        const val CHANNEL_ID = "floatball_draft"
        const val NOTIFICATION_ID = 2004
        const val REQUEST_CODE = 2004
        const val RENEW_REQUEST_CODE = 2005

        /** 通知「续 30 分钟」按钮 → 服务 action（与双击连续同一状态机入口）。 */
        const val ACTION_RENEW_WINDOW = "com.verlintas.baic2.floatball.action.COMPANION_RENEW"
        const val EXTRA_CONTACT_ID = "companion_contact_id"

        /** 窗口默认时长（AdvisorPrefs.maxSessionMs=0 时的封顶值，方案 W3）。 */
        const val DEFAULT_WINDOW_MS = 30 * 60_000L

        /** 硬审免判下限：Gatekeeper 重复判定的最短稿长口径之外的兜底长度。 */
        const val MIN_GATE_CHARS = 160

        /** 成稿回读窗口（最新 ASSISTANT 一条 + 近几条做重复判定）。 */
        private const val DRAFT_LOOKBACK = 6
        private const val GATE_REPEAT_WINDOW = 3
        private const val PROCESSED_CAP = 64

        /** 缺省静默时段（与 proactive_policies 表默认值同口径）。 */
        const val DEFAULT_QUIET_START = "22:30"
        const val DEFAULT_QUIET_END = "08:30"

        /** 降级原因码（REASON_* 口径，展示层映射文案）。 */
        const val REASON_QUOTA_REACHED = "window_quota_reached"
        const val REASON_QUIET_HOURS = "quiet_hours"
        const val REASON_PACE_PENDING = "pace_pending_exists"
    }
}
