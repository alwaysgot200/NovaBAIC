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

package com.verlintas.baic2.floatball.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.contact.ContactRef
import com.verlintas.baic2.floatball.FloatBallService
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.DirectorLogEntity
import com.verlintas.baic2.floatball.advisor.data.ROUTE_HANDOFF
import com.verlintas.baic2.floatball.advisor.data.ROUTE_NOOP
import com.verlintas.baic2.floatball.advisor.data.ROUTE_REPLY
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_INCOMING
import com.verlintas.baic2.floatball.director.DirectorRoutes
import com.verlintas.baic2.floatball.director.HandoffCategory
import com.verlintas.baic2.floatball.experts.privacy.PrivacyDomain
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 接管/达成通报执行面（2026-10-20 通报方案 §4）：H 接管型与 G 达成型命中后
 * 的「通知主人」编排——高优铃声通知（必发必达底线）+ 台账回放 + 自动发送挂起。
 * 邮件通道（SMTP）E3 期接入，本类只预留调用位。
 *
 * 纪律：
 * - 通报即取即用不落盘（本地优先红线，同 M2 起草通知）；
 * - H 不受静默时段约束（深夜来的「几点回家」更要报），G 遵守静默（§十.4）；
 * - 频控：同联系人 [THROTTLE_MS] 内不重复（进程内节流，重启至多多报一条，
 *   换取零表结构改动）；
 * - 挂起位：H 命中即把联系人拉进 await-human 集合，陪伴窗口治理面据此跳过
 *   自动派发；用户在分屏发出一条消息即解除（[releaseAwaitingHuman]）；
 * - 任何失败只 Log.d 不上抛——通报是旁路，绝不阻断参谋回合。
 */
@Singleton
class EscalationNotifier @Inject constructor(
    private val contactRepository: ContactRepository,
    private val prefs: AdvisorPrefs,
    private val db: AdvisorDatabase,
    @ApplicationContext private val context: Context,
) {

    /** await-human 集合（联系人 id）：H 命中进入，用户分屏发出消息即解除。 */
    private val _awaitingHuman = MutableStateFlow<Set<Long>>(emptySet())
    val awaitingHuman: StateFlow<Set<Long>> = _awaitingHuman.asStateFlow()

    /** 进程内节流：contactId → 最近一次通报时间。 */
    private val lastHandoffAt = HashMap<Long, Long>()
    private val lastGoalHitAt = HashMap<Long, Long>()
    private val lastPrivacyAt = HashMap<Long, Long>()
    private val lastTacticUseAt = HashMap<Long, Long>()
    private val lastTacticHumanAt = HashMap<Long, Long>()

    fun isAwaitingHuman(contactId: Long): Boolean = _awaitingHuman.value.contains(contactId)

    /** 用户接管完成（分屏对该联系人发出一条消息）即解除挂起。 */
    fun releaseAwaitingHuman(contactId: Long) {
        if (contactId <= 0L) return
        _awaitingHuman.value = _awaitingHuman.value - contactId
    }

    /**
     * H 接管通报：铃声通知 + await-human 挂起 + 通报台账行。
     * [incoming] 为来信摘录（即取即用）；[category] 为 null 时（严格档 uncertain
     * 命中）通知不带类别副标题。
     * @return 是否真正发出（false = 节流命中或权限缺失）。
     */
    suspend fun notifyHandoff(
        contact: ContactRef,
        conversationId: Long,
        incoming: String,
        category: HandoffCategory?,
    ): Boolean = runCatching {
        val now = System.currentTimeMillis()
        synchronized(lastHandoffAt) {
            lastHandoffAt[contact.contactId]?.let { last ->
                if (now - last < THROTTLE_MS) return@runCatching false
            }
            lastHandoffAt[contact.contactId] = now
        }
        _awaitingHuman.value = _awaitingHuman.value + contact.contactId
        logLedger(contact.contactId, conversationId, ROUTE_HANDOFF, "escalation_notified")
        val title = context.getString(R.string.float_escalation_handoff_title, contact.displayName)
        val body = if (category == null) {
            context.getString(R.string.float_escalation_handoff_text_plain, incoming)
        } else {
            context.getString(R.string.float_escalation_handoff_text, category.label, incoming)
        }
        postEscalation(contact, conversationId, title, body)
    }.getOrDefault(false)

    /**
     * G 达成通报（快通道）：对方明示同意/敲定。遵守静默时段（§十.4：深夜不
     * 吵醒，早上看台账/邮件），静默内只落台账不响铃。
     */
    suspend fun notifyGoalHit(
        contact: ContactRef,
        conversationId: Long,
        incoming: String,
    ): Boolean = runCatching {
        val now = System.currentTimeMillis()
        if (DirectorRoutes.inQuietWindow(now, DEFAULT_QUIET_START, DEFAULT_QUIET_END)) {
            logLedger(contact.contactId, conversationId, ROUTE_NOOP, "goal_hit_quiet_skip")
            return@runCatching false
        }
        synchronized(lastGoalHitAt) {
            lastGoalHitAt[contact.contactId]?.let { last ->
                if (now - last < THROTTLE_MS) return@runCatching false
            }
            lastGoalHitAt[contact.contactId] = now
        }
        logLedger(contact.contactId, conversationId, ROUTE_NOOP, "goal_hit_fast")
        val title = context.getString(R.string.float_escalation_goal_title, contact.displayName)
        val body = context.getString(R.string.float_escalation_goal_text, incoming)
        postEscalation(contact, conversationId, title, body)
    }.getOrDefault(false)

    /**
     * F1 隐私绕行轻通报（隐私闸门方案 §3.4 bypass 档）：对方问到隐私、联系人
     * 策略为 bypass——LLM 继续聊但回避该域，本方法只「汇报主人」：静音通知
     * （IMPORTANCE_DEFAULT 渠道、无铃声、无全屏意图），主人在忙不被打断，
     * 回头看通知即可；不挂 await-human（AI 正常回答中）。点击同样拉起分屏
     * 定位到该会话，方便主人查看绕行回复与台账。
     *
     * 渠道说明：方案写「同渠道」，但通知渠道的重要性在创建后不可降级——
     * 复用铃声渠道必响铃。故建独立静音渠道 floatball_privacy（同级入口、
     * 同 service action、同节流口径），语义等价且不惊扰。
     */
    suspend fun notifyPrivacyBypass(
        contact: ContactRef,
        conversationId: Long,
        incoming: String,
        domain: PrivacyDomain,
    ): Boolean = runCatching {
        val now = System.currentTimeMillis()
        synchronized(lastPrivacyAt) {
            lastPrivacyAt[contact.contactId]?.let { last ->
                if (now - last < THROTTLE_MS) return@runCatching false
            }
            lastPrivacyAt[contact.contactId] = now
        }
        // 台账：route=reply + reason=privacy_bypass_<域码>（周报对账 startsWith 聚合）。
        logLedger(
            contact.contactId, conversationId, ROUTE_REPLY,
            REASON_PRIVACY_BYPASS + "_" + domain.code,
        )
        val title = context.getString(R.string.float_privacy_bypass_title, contact.displayName)
        val body = context.getString(R.string.float_privacy_bypass_text, domain.label, incoming)
        postLight(contact, conversationId, title, body)
    }.getOrDefault(false)

    /**
     * T1-d 策略动用轻通报（裁量层方案 §六）：军师批准、assess 档敏感维实际
     * 生效后知会主人（静音渠道同 F1 bypass——「汇报」不打断）。free 档动用
     * 不通报（用户已明确放开）。30min 节流同口径；台账 tactic_used_<维>
     * （周报 startsWith 聚合动用分布）。
     */
    suspend fun notifyTacticUse(
        contact: ContactRef,
        conversationId: Long,
        dim: String,
        why: String,
    ): Boolean = runCatching {
        val now = System.currentTimeMillis()
        synchronized(lastTacticUseAt) {
            lastTacticUseAt[contact.contactId]?.let { last ->
                if (now - last < THROTTLE_MS) return@runCatching false
            }
            lastTacticUseAt[contact.contactId] = now
        }
        logLedger(contact.contactId, conversationId, ROUTE_REPLY, "tactic_used_$dim")
        val reason = why.trim().ifEmpty { context.getString(R.string.float_tactic_reason_none) }
        val title = context.getString(R.string.float_tactic_use_title, contact.displayName)
        val body = context.getString(
            R.string.float_tactic_use_text,
            tacticDimLabel(dim),
            reason.take(CLAIM_CLIP_CHARS),
        )
        postLight(contact, conversationId, title, body)
    }.getOrDefault(false)

    /**
     * T1-d 问主人（needsHuman）：许愿超出授权承受范围（within=false 或硬承诺
     * 词命中）→ E1 通报链同款处置：铃声通报 + await-human 挂起 + 台账
     * tactic_human_needed；主人在分屏发出一条消息即解除。该维草稿本回合另由
     * Gatekeeper 越许可检测拦截（双保险），绝不自动发送。
     */
    suspend fun notifyTacticHumanNeeded(
        contact: ContactRef,
        conversationId: Long,
        dim: String,
        claim: String,
    ): Boolean = runCatching {
        // 已挂起时静默跳过（同联系人连续多回合超授权只报一次）。
        if (isAwaitingHuman(contact.contactId)) return@runCatching false
        val now = System.currentTimeMillis()
        synchronized(lastTacticHumanAt) {
            lastTacticHumanAt[contact.contactId]?.let { last ->
                if (now - last < THROTTLE_MS) return@runCatching false
            }
            lastTacticHumanAt[contact.contactId] = now
        }
        _awaitingHuman.value = _awaitingHuman.value + contact.contactId
        logLedger(contact.contactId, conversationId, ROUTE_NOOP, REASON_TACTIC_HUMAN_NEEDED)
        val claimOrWhy = claim.trim().ifEmpty { context.getString(R.string.float_tactic_reason_none) }
        val title = context.getString(R.string.float_tactic_human_title, contact.displayName)
        val body = context.getString(
            R.string.float_tactic_human_text,
            tacticDimLabel(dim),
            claimOrWhy.take(CLAIM_CLIP_CHARS),
        )
        postEscalation(contact, conversationId, title, body)
    }.getOrDefault(false)

    /**
     * T1-d 许可单台账（裁量层方案 §六）：每回合军师通道形态——
     * tactic_permit_ok_<approach>（新产许可单，附方针）/ tactic_permit_cached
     * （90s 缓存复用）/ tactic_permit_skip（已触发但无产物，禁令段兜底）。
     * 未触发（零成本通道）不落，避免常态噪音。回放据此对账军师触发率。
     */
    suspend fun logTacticPermit(contactId: Long, conversationId: Long, reason: String) {
        logLedger(contactId, conversationId, ROUTE_REPLY, reason)
    }

    /**
     * 高优铃声通知：floatball_escalation 渠道（IMPORTANCE_HIGH + 默认通知声 +
     * 震动 + CATEGORY_ALARM），锁屏全屏横幅（USE_FULL_SCREEN_INTENT，普通权限）。
     * 点击经 service action 拉起分屏到该会话（**不带代拟条**——接管场景不把
     * AI 草稿挂一键发送，答案必须用户亲笔）。
     */
    private suspend fun postEscalation(
        contact: ContactRef,
        conversationId: Long,
        title: String,
        body: String,
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.float_escalation_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    enableVibration(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        null,
                    )
                },
            )
        }
        val imPackage = contactRepository.profile(contact.contactId)?.imPackage.orEmpty()
        val contentIntent = PendingIntent.getForegroundService(
            context,
            requestCodeFor(contact.contactId),
            Intent(context, FloatBallService::class.java)
                .setAction(ACTION_SHOW_ESCALATION)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(EXTRA_CONTACT_ID, contact.contactId)
                .putExtra(EXTRA_CONTACT_NAME, contact.displayName)
                .putExtra(EXTRA_IM_PACKAGE, imPackage),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 全屏意图需用户授权；未授权时系统自动降级横幅，无需分支。
            if (context.checkSelfPermission(android.Manifest.permission.USE_FULL_SCREEN_INTENT) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                builder.setFullScreenIntent(contentIntent, true)
            }
        } else {
            builder.setFullScreenIntent(contentIntent, true)
        }
        return NotifyGate.notifyIfPermitted(context, notificationIdFor(contact.contactId), builder.build())
    }

    /**
     * F1 静音轻通报：floatball_privacy 渠道（IMPORTANCE_DEFAULT、无声无震动）、
     * 无全屏意图、无 await-human——「汇报」语义只是知会，不打断主人。点击
     * 同铃声通道：service action 拉起分屏定位到该会话。
     */
    private suspend fun postLight(
        contact: ContactRef,
        conversationId: Long,
        title: String,
        body: String,
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    PRIVACY_CHANNEL_ID,
                    context.getString(R.string.float_privacy_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        val imPackage = contactRepository.profile(contact.contactId)?.imPackage.orEmpty()
        val contentIntent = PendingIntent.getForegroundService(
            context,
            requestCodeFor(contact.contactId),
            Intent(context, FloatBallService::class.java)
                .setAction(ACTION_SHOW_ESCALATION)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(EXTRA_CONTACT_ID, contact.contactId)
                .putExtra(EXTRA_CONTACT_NAME, contact.displayName)
                .putExtra(EXTRA_IM_PACKAGE, imPackage),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, PRIVACY_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
        return NotifyGate.notifyIfPermitted(context, privacyNotificationIdFor(contact.contactId), builder.build())
    }

    /** 通报事件台账行（旁路：route=handoff/noop + reason 事件键，回放可见）。 */
    private suspend fun logLedger(contactId: Long, conversationId: Long, route: String, reason: String) {
        runCatching {
            db.directorLogDao().insert(
                DirectorLogEntity(
                    contactId = contactId,
                    conversationId = conversationId,
                    trigger = TRIGGER_INCOMING,
                    route = route,
                    reason = reason,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            db.directorLogDao().trimFor(contactId)
        }.onFailure { Log.d(TAG, "escalation ledger failed", it) }
    }

    private fun requestCodeFor(contactId: Long): Int = (REQUEST_CODE_BASE + contactId).toInt()

    companion object {
        private const val TAG = "EscalationNotifier"

        /** 通报渠道：与起草渠道（floatball_draft）分开，铃声/震动独立可调。 */
        const val CHANNEL_ID = "floatball_escalation"

        /** F1 隐私绕行轻通报渠道：静音（IMPORTANCE_DEFAULT），与铃声渠道分开。 */
        const val PRIVACY_CHANNEL_ID = "floatball_privacy"

        /** F1 隐私绕行台账 reason 键前缀（周报对账 startsWith 聚合域分布）。 */
        const val REASON_PRIVACY_BYPASS = "privacy_bypass"

        /** T1-d 问主人台账 reason 键（needsHuman 挂起行）。 */
        const val REASON_TACTIC_HUMAN_NEEDED = "tactic_human_needed"

        /** T1-d 通知/台账里敏感维 → 中文标签（exemplifying 非通报对象）。 */
        private val TACTIC_DIM_LABELS = mapOf(
            "disclosure" to "隐私披露",
            "lying" to "不实陈述",
            "promising" to "许诺",
        )

        fun tacticDimLabel(dim: String): String = TACTIC_DIM_LABELS[dim] ?: dim

        /** 通知正文里 why/claim 的截断长度（即取即用，不整段搬运）。 */
        private const val CLAIM_CLIP_CHARS = 60

        /** 通知 id 派生基座：与起草通道 2_000_000 不撞。 */
        private const val NOTIFICATION_ID_BASE = 2_100_000L
        private const val REQUEST_CODE_BASE = 2_100_000L

        /** F1 轻通报通知 id 基座：与铃声通道 2_100_000 不撞。 */
        private const val PRIVACY_NOTIFICATION_ID_BASE = 2_200_000L

        /** 同联系人通报节流窗。 */
        private const val THROTTLE_MS = 30 * 60_000L

        /** G 类静默缺省（与 ProactivePolicySnapshot 缺省同口径）。 */
        private const val DEFAULT_QUIET_START = "22:30"
        private const val DEFAULT_QUIET_END = "08:30"

        /** service action：接管/达成通报点击入口（分屏定位，无代拟条）。 */
        const val ACTION_SHOW_ESCALATION = "com.verlintas.baic2.floatball.SHOW_ESCALATION"
        const val EXTRA_CONVERSATION_ID = "escalation_conversation_id"
        const val EXTRA_CONTACT_ID = "escalation_contact_id"
        const val EXTRA_CONTACT_NAME = "escalation_contact_name"
        const val EXTRA_IM_PACKAGE = "escalation_im_package"

        fun notificationIdFor(contactId: Long): Int = (NOTIFICATION_ID_BASE + contactId).toInt()

        /** F1 轻通报通知 id（同联系人可与铃声通报并存，互不覆盖）。 */
        fun privacyNotificationIdFor(contactId: Long): Int =
            (PRIVACY_NOTIFICATION_ID_BASE + contactId).toInt()
    }
}
