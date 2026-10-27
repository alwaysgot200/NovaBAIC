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
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.FloatSettingsActivity
import com.verlintas.baic2.floatball.IncomingCue
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
import com.verlintas.baic2.floatball.director.GateReasons
import com.verlintas.baic2.floatball.director.GateRejection
import com.verlintas.baic2.floatball.director.Gatekeeper
import com.verlintas.baic2.floatball.director.ProactivePolicyRepository
import com.verlintas.baic2.floatball.home.AdvisorRunState
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

/**
 * 自回声判定（纯函数，JVM 可测；2026-10-27 真机修复）：差分引擎只能读到
 * 「屏幕上的文字」，无法区分气泡归属。我方消息上屏后，下一回合的来信候选
 * 就是自己的气泡，若不拦截会对自己的消息再回复一次（自问自答循环）。
 *
 * 判定口径：来信与「近期已发/已生成的纯回复」做 CJK-only 归一化（OCR 常丢
 * 字母数字与标点，如 v1 的 v）后比较前 [PREFIX_CHARS] 字；再来信整段被已发
 * 回复包含（OCR 截断）也算回声。低于 [MIN_CHARS] 的短句（「好的」「收到」）
 * 不判回声，避免把真实短回复误吞。
 *
 * 2026-10-27 真机补强：OCR 会把同一条气泡拆行、把输入法「随写」条一并读入，
 * 首字/标点漂移（「明白了…」→「自明白了…」）使前缀与整段包含双双漏判，
 * 实测出现自问自答连发。另加双字片覆盖率（[echoCoverage]）：来信的 CJK
 * 双字片多数能在某条已发回复中找到即判回声，专治 OCR 噪声/截断。
 *
 * 2026-10-27 真机二次修正：IncomingCue 会把末尾两行拼成候选，于是出现
 * 「我方旧气泡末行 + 对方新短句『很好』」的混合段。若对整段做覆盖率判定，
 * 对方真实答复会被误吞。改为按行/空格切段后**只判最新一段**：最新一段
 * 是我方的话才拦，短句（「很好」）天然落在 [MIN_CHARS] 以下不受影响。
 */
object ReplyEchoGuard {

    /** 参与比较的最短 CJK 字数。 */
    const val MIN_CHARS = 6

    /** 前缀比较长度。 */
    const val PREFIX_CHARS = 8

    /** 覆盖率判定的最短来信字数：太短的信（「行，就按这个口径来」）不据此误吞。 */
    const val COVERAGE_MIN_CHARS = 10

    /** 来信双字片在已发回复中的命中占比阈值（OCR 噪声容忍）。 */
    const val COVERAGE_THRESHOLD = 0.6f

    fun isSelfEcho(incoming: String?, sentReplies: List<String>): Boolean {
        val raw = incoming ?: return false
        // 只判「最新一段」：多段候选里较早的段可能是对方上一轮来信或我方旧气泡，
        // 拿整段覆盖率会误吞对方刚发的短答复（真机「很好」被跳过事故）。
        val segments = raw.split('\n', ' ')
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        val latest = segments.last()
        if (latest.length < MIN_CHARS) {
            // 太短的一段无法构成完整回声：只有它正好是某条已发回复的结尾
            // （我方长气泡被 OCR 拆出的尾行，「…行不行」「…对细节」）才判回声；
            // 「很好」「收到」这类真实短答复不命中后缀，照常放行。
            if (latest.length < 2) return false
            return sentReplies.any { reply ->
                val replyNorm = normalize(reply)
                replyNorm.length >= MIN_CHARS && replyNorm.endsWith(latest)
            }
        }
        return sentReplies.any { reply ->
            val replyNorm = normalize(reply)
            if (replyNorm.length < MIN_CHARS) return@any false
            val common = minOf(PREFIX_CHARS, latest.length, replyNorm.length)
            if (common >= MIN_CHARS &&
                latest.take(common) == replyNorm.take(common)
            ) {
                return@any true
            }
            if (replyNorm.contains(latest)) return@any true
            latest.length >= COVERAGE_MIN_CHARS &&
                echoCoverage(latest, replyNorm) >= COVERAGE_THRESHOLD
        }
    }

    /**
     * 来信 CJK 双字片落在回复里的比例（0..1）。只用来判「来信是不是自己刚
     * 发的气泡」，不用于内容质量评估：OCR 丢字/断行时双字片仍大量重合。
     */
    internal fun echoCoverage(incomingNorm: String, replyNorm: String): Float {
        if (incomingNorm.length < 2 || replyNorm.length < 2) return 0f
        val grams = incomingNorm.windowed(2).toSet()
        if (grams.isEmpty()) return 0f
        return grams.count { replyNorm.contains(it) }.toFloat() / grams.size
    }

    /** 只保留 CJK：OCR 对字母/数字/标点的识别不稳定（v1 → 1、，→ . 等）。 */
    internal fun normalize(text: String): String = text.filter { it in '\u4e00'..'\u9fff' }
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

    /** 屏幕差分读到的是我方刚发出的气泡（自回声），本轮不回复（2026-10-27）。 */
    const val ECHO_SKIP = "auto_echo_skip"

    /** 审查拦截后合规改写成功并重新过闸（2026-10-27，自动档不再静默漏发）。 */
    const val GATE_REWRITE_PREFIX = "auto_gate_rewrite:"

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
    private val replyResolver: ReplyResolver,
    private val escalationNotifier: EscalationNotifier,
    private val prefs: AdvisorPrefs,
    private val db: AdvisorDatabase,
    private val runState: AdvisorRunState,
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
        /** 本次托管锁定的 APP 包名：自动发送必须落在眼前这个 IM 会话。 */
        val imPackage: String,
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

    /** 窗口内近期确实上屏的纯回复（自回声判定素材；有界，新→旧）。 */
    private val recentSentReplies = ArrayDeque<String>()

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
     * [imPackage] 为本次托管的 APP（分屏入口按当前识别通道传入），缺省回退档案。
     * 会话 id 允许缺省（首轮差分回合会解析绑定），成稿时读实时值。
     */
    suspend fun start(
        contact: ContactRef,
        imPackage: String? = null,
        manualContinuousActive: Boolean,
    ): StartResult =
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
            val profile = contactRepository.profile(contact.contactId)
                ?: return@withContext StartResult.REASON_NO_CONTACT
            quietPolicy = runCatching { policyRepository.forContact(contact.contactId) }.getOrNull()
            tally.reset()
            synchronized(recentSentReplies) { recentSentReplies.clear() }
            synchronized(processedMessageIds) { processedMessageIds.clear() }
            cancelPendingJobs()
            // 窗口时长：复用 AdvisorPrefs 连续模式时长上限（方案 W3），0=不限时按
            // 30 分钟常量封顶（陪伴窗口必须有限期，续期由收尾通知显式发起）。
            val durationMs = prefs.state.value.maxSessionMs.takeIf { it > 0L }
                ?: DEFAULT_WINDOW_MS
            val window = WindowSnapshot(
                contactId = contact.contactId,
                contactName = contact.displayName,
                imPackage = imPackage?.takeIf { it.isNotBlank() } ?: profile.imPackage,
                startedAt = System.currentTimeMillis(),
                durationMs = durationMs,
            )
            _activeWindow.value = window
            // 钉住联系人/APP：无 a11y 时屏幕回合解析不到前台包名，靠此绑定
            // 保持 onTurnCompleted 的归属判定（2026-10-27 真机修复）。
            screenAdvisor.bindCompanionContact(contact, window.imPackage)
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
        screenAdvisor.bindCompanionContact(null)
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
        screenAdvisor.bindCompanionContact(null)
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
            // 单回合异常不得掐死整条完成流（2026-10-27 真机 conv=19 静默丢回合）：
            // 收口逻辑里任何一处抛出，collectorJob 被取消后没有任何日志，此后
            // 所有回合都不再进治理——外观上就是「自动档忽然不动了」。
            screenAdvisor.turnCompletions.collect { completion ->
                runCatching { onTurnCompleted(completion) }
                    .onFailure { Log.d(TAG, "turn governance failed", it) }
            }
        }
    }

    /**
     * 「回合完成了但进不了治理」的留痕（2026-10-27 真机 conv=19 事故）：这些
     * 分支原先都是裸 return，台账上表现为「回合凭空消失」，排障时只能靠猜。
     * reason 拼进 `auto_timing:out_skip_*` 段名，与正常出口（out_dispatch/
     * out_echo/out_gate_blocked…）同口径可 grep，不参与任何决策。
     */
    private suspend fun logSkip(contactId: Long, conversationId: Long, reason: String) {
        Log.i(AutoTiming.TAG, "skip conv=$conversationId reason=$reason contact=$contactId")
        runCatching {
            dispatcher.logTiming(contactId, conversationId, "auto_timing:out_skip_$reason=0ms")
        }
    }

    /**
     * 一回合成稿的窗口治理：只治理窗口联系人自己的回合（期间切到别的联系人的
     * 回合一律不管——那条链路自有起草/保温通知兜底）。成稿即计 draft，随后按
     * 上限/静默/硬审裁决：拦截走台账，通过走拟人延迟后派发，任何不满足走起草
     * 通知降级。
     *
     * 2026-10-27 稳定性修复：完成回合的会话/联系人一律取自 [completion] 与
     * 持久化绑定（[ContactRepository.contactForConversation]），**不再读可变的
     * 当前状态**。真机 12:01 事故：来信回合 conv=21 完成时，差分回声回合已把
     * transcript 切到 conv=22、`activeContact` 也被清空，于是整条回复被判
     * `no_active_contact` 丢弃（对方真实来信无人应答）。
     */
    private suspend fun onTurnCompleted(completion: ScreenAdvisor.TurnCompletion) {
        val window = _activeWindow.value ?: return
        val conversationId = completion.conversationId
        // 前置闸全部留痕（2026-10-27 真机 conv=19）：真机上出现过「回合完成、
        // 日志有、治理段一行没有」的黑洞，正是这些裸 return 之一。
        if (conversationId <= 0L) return logSkip(window.contactId, conversationId, "no_conversation")
        val active = contactRepository.contactForConversation(conversationId)
        if (active == null) return logSkip(window.contactId, conversationId, "no_contact_binding")
        if (active.id != window.contactId) {
            return logSkip(window.contactId, conversationId, "foreign_contact")
        }
        // 回合 LLM 段账单（ScreenAdvisor 写、取一次即清，2026-10-27 归因）：
        // 独立一行先落，保证无论后续走哪条治理分支，「模型段耗时」都不丢。
        screenAdvisor.consumeTurnTiming(conversationId)?.let { llmRow ->
            Log.i(AutoTiming.TAG, "turn conv=$conversationId $llmRow")
            runCatching { dispatcher.logTiming(window.contactId, conversationId, llmRow) }
        }
        // 治理段账单：read/extract/gate/rewrite/gov_total + 出口标记（`out_*` 段名
        // 即本回合结局，0ms 仅作标记）。与执行段（pace/send）分两行，定位更快。
        val trace = TimingTrace()
        val govStartNanos = System.nanoTime()
        suspend fun flush(outcome: String) {
            trace.add("out_$outcome", 0L)
            trace.add("gov_total", (System.nanoTime() - govStartNanos) / 1_000_000)
            val row = trace.render()
            Log.i(AutoTiming.TAG, "gov conv=$conversationId $row")
            runCatching { dispatcher.logTiming(window.contactId, conversationId, row) }
        }
        val messages = trace.stageSuspend("read") {
            runCatching { historyGateway.recentMessages(conversationId, DRAFT_LOOKBACK) }.getOrNull()
        } ?: return flush("no_read")
        val draftMessage = messages.lastOrNull { it.role == ChatRole.ASSISTANT } ?: run {
            flush("no_draft")
            return
        }
        // 同稿只治理一次：去重放到抽取/修复调用之前，避免同一回合重复触发
        // 代言者修复调用（每次都是一次真实 LLM 开销）。去重命中同样要落一行
        // （2026-10-27）：以前是裸 return，两次完成信号撞同一稿时台账少一行，
        // 看起来就像回合丢了。
        val duplicate = synchronized(processedMessageIds) {
            val added = processedMessageIds.add(draftMessage.id)
            if (added) {
                while (processedMessageIds.size > PROCESSED_CAP) {
                    processedMessageIds.remove(processedMessageIds.iterator().next())
                }
            }
            !added
        }
        if (duplicate) {
            flush("dup")
            return
        }
        // 自回声防抖（2026-10-27 真机修复）：屏幕差分只能读文字、分不清气泡归
        // 属；我方消息上屏后下一回合的「来信」就是自己的气泡，必须跳过，否则会
        // 自问自答。判定素材 = 历史已发纯回复 + 本窗口确实上屏的回复。
        val targetChars = prefs.state.value.answerTargetChars
        val incoming = latestIncomingOf(messages)
        val priorReplies = messages
            .filter { it.role == ChatRole.ASSISTANT }
            .dropLast(1)
            .takeLast(GATE_REPEAT_WINDOW)
            .mapNotNull { SendableDraftExtractor.extract(it.content, targetChars)?.text }
        val sentReplies = priorReplies + synchronized(recentSentReplies) {
            recentSentReplies.toList()
        }
        if (ReplyEchoGuard.isSelfEcho(incoming, sentReplies)) {
            logGearEvent(window.contactId, CompanionLedgerEvents.ECHO_SKIP)
            flush("echo")
            return
        }
        tally.onDraft()
        // 接管通报挂起位（2026-10-20 通报方案 §2.5）：该联系人来信需要用户本人
        // 接管时，草稿绝不自动派发（AI 不得代答），台账留痕等待主人。
        if (escalationNotifier.isAwaitingHuman(window.contactId)) {
            logGearEvent(window.contactId, CompanionLedgerEvents.AWAIT_HUMAN_SKIP)
            flush("await_human")
            return
        }
        // 托管方案 §四.A.1 + §二十四：落库全文（分析+编号+reply 块）先做确定性
        // 抽取；抽取不到再走一次代言者修复调用。两段都失败 = 本轮不发送，
        // 绝不复用分析稿全文。来源码入台账（dispatch/degrade reason:src=）。
        val sendable = trace.stageSuspend("extract") {
            replyResolver.resolve(
                full = draftMessage.content,
                answerTargetChars = targetChars,
                incoming = incoming,
                recentSent = priorReplies,
            )
        }
        if (sendable == null) {
            tally.onBlocked()
            runCatching {
                dispatcher.logNoSendable(
                    window.contactId,
                    conversationId,
                    AutoSessionScheduler.REASON_NO_REPLY,
                )
            }.onFailure { Log.d(TAG, "no-sendable log failed", it) }
            flush("no_sendable")
            return
        }
        // 修复调用可能耗时数秒；期间窗口被结束/切换则本轮作废（不降级、不发通知）。
        if (_activeWindow.value?.contactId != window.contactId) {
            flush("window_gone")
            return
        }
        val draft = sendable.text
        val request = AutoSendRequest(
            contactId = active.id,
            contactName = active.displayName,
            conversationId = conversationId,
            imPackage = window.imPackage,
            draft = draft,
            turnMessageId = draftMessage.id,
            draftSource = sendable.source,
        )

        // 1) 单窗口发送上限：达上限后其余草稿走起草通知降级（方案 §8.2）。
        val maxSends = prefs.state.value.companionMaxSends
        if (!WindowQuota.sendAllowed(tally.snapshot().sent, maxSends)) {
            flush("quota")
            degrade(request, REASON_QUOTA_REACHED)
            return
        }
        // 2) 夜间静默：复用 M2-b quiet 口径（跨午夜判定），静默内只出草稿。
        if (inQuietNow()) {
            flush("quiet")
            degrade(request, REASON_QUIET_HOURS)
            return
        }
        // 3) Gatekeeper 自动档硬审：确定性三查+四类词表 → 轻 LLM 审（双重）。
        //    重复判定用历史已发的「纯回复」而非落库全文，避免分析稿稀释命中率。
        val maxChars = (targetChars * 2).coerceAtLeast(MIN_GATE_CHARS)
        // T1-c：附带军师许可单做越许可检测（裁量层方案 §五）。许可单由
        // runAdvisorTurn 本回合写入 ScreenAdvisor.tacticRulings（90s 缓存共享，
        // 零重复 LLM）；无在途回合/未触发时为 null，走既有 auto 四类硬审兜底。
        val tactics = screenAdvisor.tacticRulingFor(window.contactId)
        // E-f 情绪红线（方案 §4.5 行 11）同口径：盛怒稿玩笑/推进命中也拦，
        // 自动档拒绝 = 降级起草档（tally.onBlocked 同款处置，绝不自动发）。
        val emotion = screenAdvisor.emotionRouteFor(window.contactId)
        suspend fun review(text: String): GateRejection? = gatekeeper.deterministicReview(
            text,
            priorReplies,
            maxChars,
            auto = true,
            tactics = tactics,
            emotion = emotion,
        ) ?: gatekeeper.llmReview(text, priorReplies)
        var rejection = trace.stageSuspend("gate") { review(draft) }
        var finalRequest = request
        // 3.1) 越许可拦截后的合规改写重试（2026-10-27 真机修复）：军师未批
        //      承诺维时主回合仍可能写「明天发您」，被词表拦下后旧实现只记台账、
        //      静默漏发（真机 02:48/02:50 两轮 auto_gate_blocked）。这里给一次
        //      低成本改写——只删/替换触发约束的表述，改写稿必须重新过闸；仍不
        //      合规才按原路径降级（草稿绝不因此丢弃）。
        if (rejection != null) {
            val reason = rejection.reason
            if (reason == GateReasons.TACTIC_PROMISING || reason == GateReasons.TACTIC_DISCLOSURE) {
                val rewritten = trace.stageSuspend("rewrite") {
                    complianceRewrite(draft, incoming, reason, targetChars)
                }
                if (rewritten != null) {
                    val retried = trace.stageSuspend("regate") { review(rewritten.text) }
                    if (retried == null) {
                        finalRequest = request.copy(
                            draft = rewritten.text,
                            draftSource = rewritten.source,
                        )
                        logGearEvent(window.contactId, CompanionLedgerEvents.GATE_REWRITE_PREFIX + reason)
                        rejection = null
                    }
                }
            }
        }
        if (rejection != null) {
            tally.onBlocked()
            runCatching { dispatcher.logGateBlocked(finalRequest, rejection.reason) }
                .onFailure { Log.d(TAG, "gate block log failed", it) }
            flush("gate_blocked")
            return
        }
        // 4) 拟人节奏调度：同一联系人同时只有一个在途延迟任务（方案 §8.2），
        //    在途即降级起草；窗口结束/取消时挂起任务随 cancelPendingJobs 撤销。
        val occupied = synchronized(this) {
            val busy = pendingDispatchJob?.isActive == true
            if (!busy) {
                val snapshot = window
                // 拟人延迟区间读设置（2026-10-27：8~90s → 可调档），派发时落执行段账单。
                val paceMin = prefs.state.value.autoPaceMinMs
                val paceMax = prefs.state.value.autoPaceMaxMs
                val paceMs = HumanPace.randomDelayMs(Random.Default, paceMin, paceMax)
                pendingDispatchJob = windowScope.launch {
                    // 执行段账单（拟人延迟 + 派发实耗 + 结局）：与治理段分行落台账，
                    // 回答「慢在等时机、慢在导航点击，还是慢在模型」。
                    val sendTrace = TimingTrace().add("pace", paceMs)
                    // 等待期把「已在拟人节奏里等 N 秒」写进状态签（方案 §5.2 S5）：
                    // 用户看到的是「正在等待发送」，而不是「卡住了」。
                    runState.setAutoPhase(
                        AdvisorRunState.AutoPhase.READY,
                        context.getString(R.string.float_auto_phase_pace, paceMs / 1000L),
                    )
                    delay(paceMs)
                    runState.setAutoPhase(AdvisorRunState.AutoPhase.READY)
                    // sleep 后复查：窗口仍是对同一联系人在途，且未滑入静默时段。
                    val still = _activeWindow.value ?: return@launch
                    if (still.contactId != snapshot.contactId) return@launch
                    if (inQuietNow()) {
                        degrade(finalRequest, REASON_QUIET_HOURS)
                        return@launch
                    }
                    runState.setAutoPhase(
                        AdvisorRunState.AutoPhase.READY,
                        context.getString(R.string.float_auto_phase_sending),
                    )
                    val outcome = sendTrace.stageSuspend("send") { dispatcher.dispatch(finalRequest) }
                    when (outcome) {
                        is AutoSendResult.Sent -> {
                            tally.onSent()
                            recordSentReply(finalRequest.draft)
                            sendTrace.add("out_sent", 0L)
                        }
                        is AutoSendResult.DegradedToDraft -> {
                            tally.onDegraded()
                            sendTrace.add("out_degraded", 0L)
                        }
                        is AutoSendResult.Rejected -> sendTrace.add("out_rejected", 0L)
                    }
                    val row = sendTrace.render()
                    Log.i(AutoTiming.TAG, "exec conv=$conversationId $row")
                    runCatching { dispatcher.logTiming(snapshot.contactId, conversationId, row) }
                    runState.setAutoPhase(AdvisorRunState.AutoPhase.READY)
                }
            }
            busy
        }
        if (occupied) {
            flush("pace_pending")
            degrade(finalRequest, REASON_PACE_PENDING)
        } else {
            flush("dispatch")
        }
    }

    /**
     * 本轮对方来信正文（代言者修复调用与纯回复提示词的输入）：取会话最近一条
     * USER 屏幕消息，剥掉【屏幕内容】前缀后交给 [IncomingCue] 取对方最后气泡；
     * 提取不到时回退剥离后的整屏文本（截断），绝不臆造来信内容。
     */
    private fun latestIncomingOf(messages: List<ChatMessage>): String? {
        val label = context.getString(R.string.float_ask_question_label)
        val screen = messages.lastOrNull { it.role == ChatRole.USER }?.content ?: return null
        val body = screen.removePrefix(label).trim()
        if (body.isEmpty()) return null
        return IncomingCue.latestIncoming(body) ?: body.take(INCOMING_FALLBACK_CHARS)
    }

    /** 记录一条确实上屏的回复（自回声判定素材；有界，新→旧）。 */
    private fun recordSentReply(draft: String) {
        if (draft.isBlank()) return
        synchronized(recentSentReplies) {
            recentSentReplies.addFirst(draft)
            while (recentSentReplies.size > ECHO_RECENT_CAP) recentSentReplies.removeLast()
        }
    }

    /**
     * 越许可拦截后的合规改写（2026-10-27 真机修复）：只对「承诺/隐私」两类
     * 拦截生效（红线/超长/重复/AI 腔没有可改写语义，重写无意义），约束文本
     * 与 [GateReasons] 一一对应、词表口径与军师许可单同源。改写稿由调用方
     * 重新过闸；本方法失败/超时返回 null，调用方走原降级路径。
     */
    private suspend fun complianceRewrite(
        draft: String,
        incoming: String?,
        reason: String,
        targetChars: Int,
    ): SendableDraft? {
        val constraints = when (reason) {
            GateReasons.TACTIC_PROMISING -> PROMISE_REWRITE_CONSTRAINTS
            GateReasons.TACTIC_DISCLOSURE -> PRIVACY_REWRITE_CONSTRAINTS
            else -> return null
        }
        return runCatching {
            replyResolver.rewriteForGate(
                draft = draft,
                incoming = incoming,
                constraints = constraints,
                answerTargetChars = targetChars,
            )
        }.onFailure { Log.d(TAG, "compliance rewrite failed", it) }.getOrNull()
    }

    /** 降级起草通知（有因）：草稿绝不丢弃，交人肉档一键发送。 */
    private suspend fun degrade(request: AutoSendRequest, reason: String) {
        tally.onDegraded()
        runCatching { dispatcher.degradeToDraft(request, reason) }
            .onFailure { Log.d(TAG, "degrade to draft failed", it) }
    }

    /**
     * 自动单会话的主动首轮（真机「选了自动却一直没动静」的补齐）：
     * 窗口就绪后等首帧稳定，按联系人画像/素材选题，走 [ScreenAdvisor.runAutoKickoff]
     * 生成一条目标驱动的开场草稿；成稿仍由 [turnCompletions] 汇入本类的
     * Gatekeeper/拟人节奏/派发链路，与来信回复完全同一条发送治理。
     *
     * 静默时段不主动发起（避免半夜骚扰），窗口已结束/联系人已切换则自动放弃；
     * 任何失败只日志，不影响窗口继续观测来信。
     */
    fun kickoffAuto(contact: ContactRef) {
        windowScope.launch {
            // 等待期在状态签上留痕（方案 §5.2 S5）：不是「没反应」，是在等首帧。
            runState.setAutoPhase(
                AdvisorRunState.AutoPhase.WAITING,
                context.getString(R.string.float_auto_phase_kickoff),
            )
            delay(KICKOFF_DELAY_MS)
            val window = _activeWindow.value ?: return@launch
            if (window.contactId != contact.contactId) return@launch
            if (!prefs.state.value.autoModeEnabled) return@launch
            if (inQuietNow()) {
                Log.i(TAG, "auto kickoff skipped: quiet_hours")
                // 静默时段不主动开场，但状态签要告诉用户「在监控，只是不抢发」。
                runState.setAutoPhase(
                    AdvisorRunState.AutoPhase.READY,
                    context.getString(R.string.float_auto_phase_quiet),
                )
                return@launch
            }
            // 已有来信回合进入治理 → 放弃主动开场（2026-10-27 真机 12:01 事故）：
            // 对方的问题还在屏上时抢发一条「主动开场」，对话就变成答非所问的
            // 自说自话（开场稿与来信内容毫无关系，用户直观感受就是「回复很傻」）。
            // 开场只服务「窗口里没人说话」的场景，来信回复永远优先。
            val handled = tally.snapshot().drafts
            val inFlight = screenAdvisor.transcript.value.status == ScreenAdvisor.Status.Thinking
            if (handled > 0 || inFlight) {
                Log.i(TAG, "auto kickoff skipped: drafts=$handled inFlight=$inFlight")
                runState.setAutoPhase(
                    AdvisorRunState.AutoPhase.READY,
                    context.getString(R.string.float_auto_phase_reply_priority),
                )
                return@launch
            }
            runCatching { screenAdvisor.runAutoKickoff(contact) }
                .onSuccess { result ->
                    Log.i(
                        TAG,
                        "auto kickoff done: contact=${contact.contactId} " +
                            "conversation=${result.conversationId} draftChars=${result.draft?.length ?: 0}",
                    )
                }
                .onFailure { Log.d(TAG, "auto kickoff failed", it) }
        }
    }

    /** 夜间静默判定：联系人 proactive_policies 的 quiet 口径，缺省 22:30~08:30。 */
    private fun inQuietNow(): Boolean {
        val policy = quietPolicy
        val start = policy?.quietStart ?: DEFAULT_QUIET_START
        val end = policy?.quietEnd ?: DEFAULT_QUIET_END
        return DirectorRoutes.inQuietWindow(System.currentTimeMillis(), start, end)
    }

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

        /**
         * 主动首轮延迟：等目标聊天页首帧稳定，避免把导航动画读成聊天内容。
         * 2026-10-27 由 2.5s 放宽到 12s：给差分引擎足够时间把屏上「对方未回复
         * 的来信」先变成回合，开场检查据此让位于来信回复（真机 12:01 开场与
         * 来信回复抢跑的教训）。
         *
         * 2026-10-27 二次修订（方案 §5.2 S5）：12s 在真机观感上就是「点了自动
         * 一分多钟没反应」的第一段，收敛到 3s——首帧稳定（差分 2 拍 ≈1.6s）已够，
         * 「有来信就让位」的判定不看等待时长、只看 `drafts/inFlight`，缩短不改变
         * 优先级语义，只是让开场更快。
         */
        const val KICKOFF_DELAY_MS = 3_000L

        /** 硬审免判下限：Gatekeeper 重复判定的最短稿长口径之外的兜底长度。 */
        const val MIN_GATE_CHARS = 160

        /**
         * 合规改写约束文本（与 [GateReasons.TACTIC_PROMISING] /
         * [GateReasons.TACTIC_DISCLOSURE] 一一对应）：词表口径与
         * [com.verlintas.baic2.floatball.experts.strategist.StrategistAssets]
         * 的 PROMISE_WORDS 对齐，改写稿须能通过同一个词表复审。
         */
        const val PROMISE_REWRITE_CONSTRAINTS =
            "不要替用户做任何承诺、保证或期限表态；正文不得出现今天/明天/后天/本周/下周/周末/月底/小时/分钟" +
                "等时间承诺词，也不得出现保证/承诺/一定/绝对/包/书面等承诺词。"
        const val PRIVACY_REWRITE_CONSTRAINTS =
            "不要透露用户本人隐私（住址、收入、家庭、联系方式、行程等）。"

        /** 成稿回读窗口（最新 ASSISTANT 一条 + 近几条做重复判定）。 */
        private const val DRAFT_LOOKBACK = 6
        private const val GATE_REPEAT_WINDOW = 3

        /** 自回声素材上限（近期确实上屏的回复条数）。 */
        private const val ECHO_RECENT_CAP = 3
        private const val PROCESSED_CAP = 64

        /** 来信正文兜底上限（[IncomingCue] 提取失败时用整屏前段，仅作提示词输入）。 */
        private const val INCOMING_FALLBACK_CHARS = 200

        /** 缺省静默时段（与 proactive_policies 表默认值同口径）。 */
        const val DEFAULT_QUIET_START = "22:30"
        const val DEFAULT_QUIET_END = "08:30"

        /** 降级原因码（REASON_* 口径，展示层映射文案）。 */
        const val REASON_QUOTA_REACHED = "window_quota_reached"
        const val REASON_QUIET_HOURS = "quiet_hours"
        const val REASON_PACE_PENDING = "pace_pending_exists"
    }
}
