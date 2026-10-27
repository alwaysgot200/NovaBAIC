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

package com.verlintas.baic2.floatball.auto

import android.content.Context
import android.util.Log
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.ScreenAdvisor
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.contact.ContactHistoryGateway
import com.verlintas.baic2.floatball.contact.ContactRef
import com.verlintas.baic2.floatball.director.Gatekeeper
import com.verlintas.baic2.floatball.home.AdvisorRunState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 多会话托管串行调度器（方案 §5.4）：RPA 同一时刻只能占一个前台与一个输入焦点，
 * 因此多会话不是并发 N 个引擎，而是「一个 worker + 队列轮转」。
 *
 * 入队来自 [com.verlintas.baic2.floatball.director.AdvisorNotificationListener]
 * 命中的托管目标通知；每轮固定走既有管线（参谋回合 → 导航 → 自动发送，
 * 失败降级起草通知），不新写 OCR/LLM/注入。
 */
@Singleton
class AutoSessionScheduler @Inject constructor(
    private val advisor: ScreenAdvisor,
    private val contacts: ContactRepository,
    private val navigator: ImChatNavigator,
    private val dispatcher: ActionDispatcher,
    private val gatekeeper: Gatekeeper,
    private val replyResolver: ReplyResolver,
    private val historyGateway: ContactHistoryGateway,
    private val prefs: AdvisorPrefs,
    private val runState: AdvisorRunState,
    @ApplicationContext private val context: Context,
) {

    enum class Status { QUEUED, ANALYZING, NAVIGATING, SENDING, COOLDOWN, FAILED, DONE, PAUSED }

    data class TargetState(
        val key: String,
        val contactId: Long,
        val imPackage: String?,
        val displayName: String,
        val status: Status = Status.QUEUED,
        val error: String? = null,
    )

    data class Snapshot(
        val running: Boolean = false,
        val targets: List<TargetState> = emptyList(),
        val queued: Int = 0,
    )

    private data class Pending(
        val key: String,
        val contact: ContactRef,
        val imPackage: String,
        val channelName: String,
        val body: String,
        val enqueuedAt: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val queue = ArrayDeque<Pending>()
    private val paused = mutableSetOf<String>()
    private val lastEnqueuedAt = mutableMapOf<String, Long>()
    private val consecutiveFailures = mutableMapOf<String, Int>()
    private val sendHistory = mutableMapOf<String, MutableList<Long>>()
    private val globalSendHistory = mutableListOf<Long>()
    private val appLabels = mutableMapOf<String, String>()

    private var worker: Job? = null

    @Volatile
    private var running = false

    private var targets: List<TargetState> = emptyList()

    fun isRunning(): Boolean = running

    /** 该通知是否属于当前托管组（包名匹配目标；联系人级目标接受任意 APP）。 */
    fun matches(imPackage: String, contactId: Long): Boolean =
        running && targets.any { target ->
            target.contactId == contactId &&
                (target.imPackage == null || target.imPackage == imPackage)
        }

    fun start(selection: List<AutoSelectionStore.Target>) {
        if (selection.isEmpty()) return
        stop()
        targets = selection.map { target ->
            val key = keyOf(target.contactId, target.imPackage)
            TargetState(
                key = key,
                contactId = target.contactId,
                imPackage = target.imPackage,
                displayName = target.displayName,
            )
        }
        running = true
        launchWorker()
        publish()
    }

    fun stop() {
        running = false
        worker?.cancel()
        worker = null
        synchronized(queue) { queue.clear() }
        paused.clear()
        targets = emptyList()
        publish()
    }

    /** 暂停 / 恢复单个托管目标（托管面板用）。 */
    fun setPaused(key: String, pausedNow: Boolean) {
        if (pausedNow) paused.add(key) else paused.remove(key)
        updateStatus(key, if (pausedNow) Status.PAUSED else Status.QUEUED)
        publish()
    }

    /**
     * 通知入队：3s 去抖（同一目标重复通知合并）、同目标在队只更新正文、队列上限 20。
     * 不在托管组或未运行则直接忽略（非托管通知仍走原起草逻辑）。
     */
    fun enqueue(imPackage: String, contact: ContactRef, channelName: String, body: String) {
        if (!running) return
        val key = keyOf(contact.contactId, imPackage)
        val target = targets.firstOrNull { it.key == key }
            ?: targets.firstOrNull {
                it.contactId == contact.contactId && (it.imPackage == null || it.imPackage == imPackage)
            }
            ?: return
        if (target.key in paused) return
        val now = System.currentTimeMillis()
        val last = lastEnqueuedAt[target.key] ?: 0L
        if (now - last < DEBOUNCE_MS) return
        lastEnqueuedAt[target.key] = now
        val pending = Pending(
            key = target.key,
            contact = contact,
            imPackage = imPackage,
            channelName = channelName,
            body = body,
            enqueuedAt = now,
        )
        synchronized(queue) {
            val existing = queue.indexOfFirst { it.key == target.key }
            if (existing >= 0) {
                queue[existing] = pending
            } else {
                if (queue.size >= MAX_QUEUE) queue.removeFirst()
                queue.addLast(pending)
            }
        }
        updateStatus(target.key, Status.QUEUED)
        publish()
    }

    /** 服务销毁等场景：取消全部并静默（不发任何用户提示）。 */
    fun cancelAllSilently() {
        stop()
    }

    // ------------------------------------------------------------ worker

    private fun launchWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (isActive && running) {
                val next = mutex.withLock { synchronized(queue) { queue.removeFirstOrNull() } } ?: break
                runCatching { process(next) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        Log.d(TAG, "auto turn failed", error)
                    }
                publish()
                delay(COOLDOWN_MS)
            }
        }
    }

    private suspend fun process(pending: Pending) {
        val key = pending.key
        val contact = pending.contact
        if (!running || key in paused) return
        updateStatus(key, Status.ANALYZING)
        publish()

        val result = advisor.submitNotificationTurn(pending.body, contact)
        if (result.outcome != ScreenAdvisor.SubmitOutcome.RAN || result.handoff) {
            updateStatus(key, Status.DONE)
            return
        }
        val conversationId = result.conversationId
        if (conversationId == null || conversationId <= 0L) {
            updateStatus(key, Status.DONE)
            return
        }
        if (!quotaAvailable(key)) {
            updateStatus(key, Status.COOLDOWN, "rate_limited")
            return
        }

        // v8：自动档唯一回复出口。落库全文只是分析素材；发送正文必须由
        // ReplyResolver 产出（标记抽取 → 代言者修复调用 → 失败跳过），
        // 绝不再把 ASSISTANT 全文直接塞进发送通道。
        val targetChars = prefs.state.value.answerTargetChars
        val recentMessages = runCatching {
            historyGateway.recentMessages(conversationId, DRAFT_LOOKBACK)
        }.getOrNull().orEmpty()
        val priorReplies = recentMessages
            .filter { it.role == ChatRole.ASSISTANT && it.content != result.draft }
            .takeLast(GATE_REPEAT_WINDOW)
            .mapNotNull { SendableDraftExtractor.extract(it.content, targetChars)?.text }
        val sendable = replyResolver.resolve(
            full = result.draft,
            answerTargetChars = targetChars,
            incoming = pending.body,
            recentSent = priorReplies,
        )
        if (sendable == null) {
            dispatcher.logNoSendable(contact.contactId, conversationId, REASON_NO_REPLY)
            updateStatus(key, Status.DONE, REASON_NO_REPLY)
            return
        }
        val request = AutoSendRequest(
            contactId = contact.contactId,
            contactName = contact.displayName,
            conversationId = conversationId,
            imPackage = pending.imPackage,
            draft = sendable.text,
            draftSource = sendable.source,
        )
        // 审查官必须在线：多会话调度此前绕过了 Gatekeeper，这是 v8 修复的
        // 第二个「代理没真正扮演角色」缺口。硬审拒绝只跳过/降级，绝不发送。
        val maxChars = (targetChars * 2).coerceAtLeast(MIN_GATE_CHARS)
        val rejection = gatekeeper.deterministicReview(
            sendable.text,
            priorReplies,
            maxChars,
            auto = true,
            tactics = advisor.tacticRulingFor(contact.contactId),
            emotion = advisor.emotionRouteFor(contact.contactId),
        ) ?: gatekeeper.llmReview(sendable.text, priorReplies)
        if (rejection != null) {
            dispatcher.logGateBlocked(request, rejection.reason)
            updateStatus(key, Status.DONE, "gate_blocked:${rejection.reason}")
            return
        }

        updateStatus(key, Status.NAVIGATING)
        publish()
        val nav = navigator.openChat(pending.imPackage, pending.channelName)
        if (nav is ImChatNavigator.NavResult.Fail) {
            dispatcher.degradeToDraft(request, nav.reason)
            recordFailure(key)
            updateStatus(key, Status.FAILED, nav.reason)
            return
        }

        updateStatus(key, Status.SENDING)
        publish()
        when (val sent = dispatcher.dispatch(request)) {
            is AutoSendResult.Sent -> {
                recordSend(key)
                consecutiveFailures.remove(key)
                updateStatus(key, Status.DONE)
            }
            is AutoSendResult.DegradedToDraft -> {
                recordFailure(key)
                updateStatus(key, Status.FAILED, sent.reason)
            }
            is AutoSendResult.Rejected -> {
                recordFailure(key)
                updateStatus(key, Status.FAILED, sent.reason)
            }
        }
    }

    // ------------------------------------------------------------ state

    private fun updateStatus(key: String, status: Status, error: String? = null) {
        targets = targets.map { target ->
            if (target.key == key) target.copy(status = status, error = error) else target
        }
    }

    private fun publish() {
        val active = targets.firstOrNull {
            it.status == Status.ANALYZING ||
                it.status == Status.NAVIGATING ||
                it.status == Status.SENDING
        }
        _snapshot.value = Snapshot(
            running = running,
            targets = targets,
            queued = synchronized(queue) { queue.size },
        )
        runState.setAuto(
            targetCount = targets.size,
            activeContact = active?.displayName,
            activeApp = active?.imPackage?.let(::appLabel),
            queued = synchronized(queue) { queue.size },
            paused = paused.size,
            // 有目标在跑=READY；纯等通知=WAITING（状态签据此显示「等待来信」，
            // 2026-10-27 方案 §5.2 S1）。
            phase = if (active == null) {
                AdvisorRunState.AutoPhase.WAITING
            } else {
                AdvisorRunState.AutoPhase.READY
            },
        )
        if (!running) runState.setIdle()
    }

    private fun recordFailure(key: String) {
        val count = (consecutiveFailures[key] ?: 0) + 1
        consecutiveFailures[key] = count
        if (count >= MAX_CONSECUTIVE_FAILURES) {
            paused.add(key)
            updateStatus(key, Status.PAUSED, "too_many_failures")
        }
    }

    private fun recordSend(key: String) {
        val now = System.currentTimeMillis()
        synchronized(sendHistory) {
            sendHistory.getOrPut(key) { mutableListOf() }.add(now)
        }
        synchronized(globalSendHistory) { globalSendHistory.add(now) }
    }

    /** 单目标 ≤20/h、全局 ≤60/h（方案 §5.4.3）。 */
    private fun quotaAvailable(key: String): Boolean {
        val since = System.currentTimeMillis() - HOUR_MS
        val perTarget = synchronized(sendHistory) {
            sendHistory.getOrPut(key) { mutableListOf() }
                .also { it.removeAll { time -> time < since } }
                .size
        }
        val global = synchronized(globalSendHistory) {
            globalSendHistory.removeAll { it < since }
            globalSendHistory.size
        }
        return perTarget < MAX_SENDS_PER_TARGET_HOUR && global < MAX_SENDS_GLOBAL_HOUR
    }

    private fun keyOf(contactId: Long, imPackage: String?): String =
        "$contactId|${imPackage.orEmpty()}"

    private fun appLabel(imPackage: String): String = appLabels.getOrPut(imPackage) {
        runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(imPackage, 0),
            ).toString()
        }.getOrDefault(imPackage)
    }

    internal companion object {
        const val TAG = "AutoSessionScheduler"

        /** 同一目标通知去抖（方案 §5.4.3）。 */
        const val DEBOUNCE_MS = 3_000L

        /** 队列上限：超出丢弃最旧（积压保护）。 */
        const val MAX_QUEUE = 20

        /** 轮转冷却：处理完一个会话至少间隔（避免抢屏/刷屏）。 */
        const val COOLDOWN_MS = 4_000L

        const val MAX_CONSECUTIVE_FAILURES = 3
        const val MAX_SENDS_PER_TARGET_HOUR = 20
        const val MAX_SENDS_GLOBAL_HOUR = 60
        const val HOUR_MS = 60 * 60 * 1000L

        /** 回复抽取/防复读的回读窗口（最新一条 + 近几条）。 */
        private const val DRAFT_LOOKBACK = 6

        /** 送审时附带的近期纯回复条数。 */
        private const val GATE_REPEAT_WINDOW = 3

        /** 硬审超长判定下限（与陪伴窗口同口径）。 */
        private const val MIN_GATE_CHARS = 240

        /** v8：抽取与修复调用都拿不到可发送回复时的台账原因码。 */
        const val REASON_NO_REPLY = "no_sendable_reply"
    }
}
