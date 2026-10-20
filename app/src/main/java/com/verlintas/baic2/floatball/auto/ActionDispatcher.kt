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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.verlintas.baic2.R
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.TextNode
import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.FloatBallService
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.DirectorLogEntity
import com.verlintas.baic2.floatball.advisor.data.ROUTE_REPLY
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_AUTO
import com.verlintas.baic2.floatball.director.AdvisorNotificationListener
import com.verlintas.baic2.floatball.notify.NotifyGate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 一次自动发送请求（调用方 = M3-b 节奏调度：拟人延迟 8~90s 后触发，本类不做延迟）。 */
data class AutoSendRequest(
    val contactId: Long,
    val contactName: String,
    val conversationId: Long,
    val imPackage: String,
    val draft: String,
    val turnMessageId: Long? = null,
)

/** 派发结果：Sent=三步全部成功；DegradedToDraft=执行失败已降级起草通知；Rejected=总闸/白名单拒绝。 */
sealed interface AutoSendResult {
    data class Sent(val fingerprint: String) : AutoSendResult

    /** [reason] 为 REASON_* 码，用户文案由展示层映射。 */
    data class DegradedToDraft(val reason: String, val fingerprint: String) : AutoSendResult

    /** 总闸关闭 / 联系人不在白名单：不触碰 IM、不发通知，调用方自行决定提示。 */
    data class Rejected(val reason: String) : AutoSendResult
}

/** 发送前文本指纹（SHA-256 前 16 hex，纯函数 JVM 可测）：错发率对账的唯一凭据。 */
object SendFingerprint {

    fun of(text: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        digest.take(8).joinToString("") { "%02x".format(it) }
    }.getOrDefault(text.length.toString())
}

/** 自动档前置闸（纯函数 JVM 可测）：总闸 + 白名单双检，任一不过即拒绝派发。 */
object DispatchGate {

    const val REASON_AUTO_DISABLED = "auto_mode_disabled"
    const val REASON_NOT_WHITELISTED = "contact_not_whitelisted"

    /** 返回 null = 放行；否则为拒绝原因码。 */
    fun vetoReason(autoEnabled: Boolean, whitelist: Set<Long>, contactId: Long): String? = when {
        !autoEnabled -> REASON_AUTO_DISABLED
        contactId !in whitelist -> REASON_NOT_WHITELISTED
        else -> null
    }
}

/** 一条发送记录（指纹 + 结局原因码 + 时间）。 */
data class SendRecord(val fingerprint: String, val reason: String, val at: Long)

/**
 * 发送指纹内存对账表（方案 §8.3「发送前文本指纹记录」，纯 JVM 可测）：
 * 每联系人一个有界队列（新→旧），支撑「错发率 0」回放验证；进程内存、重启即清，
 * 不落盘草稿内容（本地优先红线）。
 */
internal class AutoSendLedger(private val cap: Int = LEDGER_DEFAULT_CAP) {

    private val byContact = HashMap<Long, ArrayDeque<SendRecord>>()

    fun record(contactId: Long, fingerprint: String, reason: String, at: Long) {
        synchronized(byContact) {
            val deque = byContact.getOrPut(contactId) { ArrayDeque() }
            deque.addFirst(SendRecord(fingerprint, reason, at))
            while (deque.size > cap) deque.removeLast()
        }
    }

    /** 指定联系人的最近发送记录（新→旧）。 */
    fun recent(contactId: Long): List<SendRecord> = synchronized(byContact) {
        byContact[contactId]?.toList().orEmpty()
    }

    private companion object {
        const val LEDGER_DEFAULT_CAP = 32
    }
}

/**
 * 发送三步状态机（方案 §8.2：定位输入 → 注入文本 → 点击发送，每步存在性校验
 * 与一次重试）。**绝不盲点坐标**——一切触点都来自 [AccessibilityBridge.findText]
 * 的实际节点树定位或框架自身的焦点/可编辑节点定位；任何一步失败即放弃发送
 * （由调用方降级起草档），不做第二类尝试。纯编排、零 Android 依赖，
 * JVM 用 fake bridge 即可单测。
 */
internal object SendSequence {

    const val REASON_A11Y_DISCONNECTED = "a11y_not_connected"
    const val REASON_WRONG_FOREGROUND = "wrong_foreground"
    const val REASON_INPUT_FAILED = "input_inject_failed"
    const val REASON_SEND_NOT_FOUND = "send_button_not_found"
    const val REASON_SEND_TAP_FAILED = "send_tap_failed"

    /**
     * 发送按钮候选查询词（findText 为 text/contentDescription 包含匹配、大小写
     * 不敏感）：中文 IM 主用「发送」，拉丁 UI 兜底 "send"（亦命中 "发送(S)" 等）。
     */
    private val SEND_QUERIES = listOf("发送", "send")

    /** 执行完整序列：返回 null = 发送成功，否则为失败原因码。 */
    suspend fun run(bridge: AccessibilityBridge, imPackage: String, draft: String): String? {
        if (!bridge.connected.value) return REASON_A11Y_DISCONNECTED
        // 前台包名校验：不在目标 IM 的会话界面绝不注入（防错发到别的 App）。
        if (bridge.foregroundPackage() != imPackage) return REASON_WRONG_FOREGROUND
        // 第 1 步：注入文本。typeText 由 a11y 实现定位输入焦点（缺省首个可编辑
        // 节点）执行 ACTION_SET_TEXT；失败重试一次（界面可能尚未就绪）。
        if (!injectWithRetry(bridge, draft)) return REASON_INPUT_FAILED
        // 第 2 步：定位发送按钮（节点树实际存在才继续）。
        val button = findSendWithRetry(bridge) ?: return REASON_SEND_NOT_FOUND
        // 第 3 步：点击发送；点击未生效则重定位一次再点，仍失败降级。
        if (!tap(bridge, button)) {
            val relocated = findSend(bridge) ?: return REASON_SEND_NOT_FOUND
            if (!tap(bridge, relocated)) {
                // best effort 清空输入框残留，避免降级后人肉档面对半成品。
                runCatching { bridge.typeText("") }
                return REASON_SEND_TAP_FAILED
            }
        }
        return null
    }

    private suspend fun injectWithRetry(bridge: AccessibilityBridge, draft: String): Boolean {
        if (bridge.typeText(draft).isSuccess) return true
        delay(INJECT_RETRY_DELAY_MS)
        return bridge.typeText(draft).isSuccess
    }

    private suspend fun findSendWithRetry(bridge: AccessibilityBridge): TextNode? {
        findSend(bridge)?.let { return it }
        delay(FIND_RETRY_DELAY_MS)
        return findSend(bridge)
    }

    /**
     * 从节点树定位发送按钮：候选过滤掉高度/宽度异常的容器节点，取**最靠下、
     * 其次最靠右**的一个（IM 发送按钮恒在底部输入栏右端）。
     */
    private fun findSend(bridge: AccessibilityBridge): TextNode? =
        SEND_QUERIES.asSequence()
            .flatMap { bridge.findText(it).asSequence() }
            .filter { it.bottom - it.top <= MAX_SEND_NODE_HEIGHT }
            .filter { it.right - it.left <= MAX_SEND_NODE_WIDTH }
            .maxWithOrNull(compareBy({ it.bottom }, { it.right }))

    private suspend fun tap(bridge: AccessibilityBridge, node: TextNode): Boolean {
        delay(TAP_DELAY_MS)
        return bridge.tap(node.centerX, node.centerY).isSuccess
    }

    private const val INJECT_RETRY_DELAY_MS = 600L
    private const val FIND_RETRY_DELAY_MS = 900L
    private const val TAP_DELAY_MS = 400L
    private const val MAX_SEND_NODE_HEIGHT = 200
    private const val MAX_SEND_NODE_WIDTH = 400
}

/**
 * 自动发送执行器（方案 §8.2 W5，M3-a）：编排 AccessibilityBridge 三步发送，
 * 失败即降级 M2 起草通知（ACTION_SHOW_DRAFT 路径），绝不盲点坐标、绝不丢弃草稿。
 *
 * 职责边界：Gatekeeper 硬审在调用方（审过才调 [dispatch]）；拟人延迟/窗口上限/
 * 夜间静默属 M3-b 节奏调度——本类设计为「延迟后由调用方触发」的即时执行形态。
 * 每次执行（成功/失败/拒绝）全部落 director_logs（trigger=auto），发送前文本
 * 指纹记录进内存对账表，支撑「错发率 0」回放验证。
 */
@Singleton
class ActionDispatcher @Inject constructor(
    private val bridge: AccessibilityBridge,
    private val prefs: AdvisorPrefs,
    private val db: AdvisorDatabase,
    @ApplicationContext private val context: Context,
) {

    private val ledger = AutoSendLedger()

    /**
     * 执行一次自动发送：总闸+白名单前置闸 → 三步状态机 → 指纹入账 →
     * director_logs 落库 → 失败降级起草通知。全程不抛异常（降级矩阵纪律）。
     */
    suspend fun dispatch(request: AutoSendRequest): AutoSendResult {
        val settings = prefs.state.value
        val veto = DispatchGate.vetoReason(
            autoEnabled = settings.autoModeEnabled,
            whitelist = settings.autoWhitelistIds,
            contactId = request.contactId,
        )
        if (veto != null) return AutoSendResult.Rejected(veto)

        val fingerprint = SendFingerprint.of(request.draft)
        val failure = withContext(Dispatchers.Default) {
            runCatching { SendSequence.run(bridge, request.imPackage, request.draft) }
                .getOrElse { REASON_DISPATCH_ERROR }
        }
        val reason = if (failure == null) "auto_sent:$fingerprint" else "$REASON_DISPATCH_FAILED:$failure:$fingerprint"
        logSend(request, reason)
        ledger.record(request.contactId, fingerprint, reason, System.currentTimeMillis())
        return if (failure == null) {
            AutoSendResult.Sent(fingerprint)
        } else {
            postDraftFallback(request)
            AutoSendResult.DegradedToDraft(failure, fingerprint)
        }
    }

    /** Gatekeeper 硬审拒绝的台账记录（调用方审稿后调用；只落库绝不发送）。 */
    suspend fun logGateBlocked(request: AutoSendRequest, gateReason: String) {
        val fingerprint = SendFingerprint.of(request.draft)
        logSend(request, "$REASON_GATE_BLOCKED:$gateReason:$fingerprint")
        ledger.record(request.contactId, fingerprint, REASON_GATE_BLOCKED, System.currentTimeMillis())
    }

    /**
     * 直接降级起草通知（M3-b 节奏调度用：窗口上限/夜间静默/派发互斥不满足时
     * 草稿不丢弃、不尝试发送）。与 [dispatch] 的失败降级同一条通知路径，
     * 差异仅在台账原因码由调用方给定（REASON_* 口径）。
     */
    suspend fun degradeToDraft(request: AutoSendRequest, reason: String): AutoSendResult.DegradedToDraft {
        val fingerprint = SendFingerprint.of(request.draft)
        logSend(request, "$REASON_DEGRADED:$reason:$fingerprint")
        ledger.record(request.contactId, fingerprint, reason, System.currentTimeMillis())
        postDraftFallback(request)
        return AutoSendResult.DegradedToDraft(reason, fingerprint)
    }

    /** 发送事件落 director_logs（旁路，任何持久化失败只 Log 不上抛）。 */
    private suspend fun logSend(request: AutoSendRequest, reason: String) {
        runCatching {
            db.directorLogDao().insert(
                DirectorLogEntity(
                    contactId = request.contactId,
                    conversationId = request.conversationId,
                    trigger = TRIGGER_AUTO,
                    route = ROUTE_REPLY,
                    reason = reason,
                    turnMessageId = request.turnMessageId,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            db.directorLogDao().trimFor(request.contactId)
        }.onFailure { Log.d(TAG, "auto send log failed", it) }
    }

    /**
     * 降级起草档（方案 §8.1 W5→N5）：复刻 M2 起草通知形态，经 service action
     * 拉起分屏预览，一键发送/改后发送仍由人完成。通知文本即取即用，本类
     * 不落盘草稿内容（本地优先红线）。
     */
    private fun postDraftFallback(request: AutoSendRequest) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.float_draft_channel),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val contentIntent = PendingIntent.getForegroundService(
                context,
                derivedIdFor(request.contactId),
                Intent(context, FloatBallService::class.java)
                    .setAction(AdvisorNotificationListener.ACTION_SHOW_DRAFT)
                    .putExtra(AdvisorNotificationListener.EXTRA_CONVERSATION_ID, request.conversationId)
                    .putExtra(AdvisorNotificationListener.EXTRA_CONTACT_ID, request.contactId)
                    .putExtra(AdvisorNotificationListener.EXTRA_CONTACT_NAME, request.contactName)
                    .putExtra(AdvisorNotificationListener.EXTRA_IM_PACKAGE, request.imPackage)
                    .putExtra(AdvisorNotificationListener.EXTRA_DRAFT, request.draft),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.float_draft_notif_title, request.contactName))
                .setContentText(request.draft)
                .setStyle(NotificationCompat.BigTextStyle().bigText(request.draft))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            NotifyGate.notifyIfPermitted(context, derivedIdFor(request.contactId), notification)
        }.onFailure { Log.d(TAG, "auto draft fallback failed", it) }
    }

    /**
     * 按 contactId 派生稳定唯一通知/PendingIntent id（W2）：多联系人并发降级
     * 通知互不覆盖（同联系人后稿覆盖前稿，只保最新草稿）；requestCode 同源
     * 派生，防 FLAG_UPDATE_CURRENT 下同码 PI 互改 contentIntent 造成错路由。
     * 渠道不变（floatball_draft，M2 起草通知同渠道）。
     */
    private fun derivedIdFor(contactId: Long): Int = (NOTIFICATION_ID_BASE + contactId).toInt()

    private companion object {
        private const val TAG = "ActionDispatcher"

        /** 与 M2 起草通知同渠道（floatball_draft）分 id 发，避免互相覆盖。 */
        private const val CHANNEL_ID = "floatball_draft"
        private const val NOTIFICATION_ID_BASE = 2_000_000

        private const val REASON_DISPATCH_ERROR = "dispatch_error"
        private const val REASON_DISPATCH_FAILED = "auto_dispatch_failed"
        private const val REASON_GATE_BLOCKED = "auto_gate_blocked"
        private const val REASON_DEGRADED = "auto_degraded"
    }
}
