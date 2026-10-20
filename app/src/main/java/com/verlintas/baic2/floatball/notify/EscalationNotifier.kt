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
import com.verlintas.baic2.core.data.repository.ContactRepository
import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.contact.ContactRef
import com.verlintas.baic2.floatball.FloatBallService
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.DirectorLogEntity
import com.verlintas.baic2.floatball.advisor.data.ROUTE_HANDOFF
import com.verlintas.baic2.floatball.advisor.data.ROUTE_NOOP
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_INCOMING
import com.verlintas.baic2.floatball.director.DirectorRoutes
import com.verlintas.baic2.floatball.director.HandoffCategory
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

    fun isAwaitingHuman(contactId: Long): Boolean = _awaitingHuman.value.contains(contactId)

    /** 用户接管完成（分屏对该联系人发出一条消息）即解除挂起。 */
    fun releaseAwaitingHuman(contactId: Long) {
        if (contactId <= 0L) return
        _awaitingHuman.value = _awaitingHuman.value - contactId
    }

    /**
     * H 接管通报：铃声通知 + await-human 挂起 + 通报台账行。
     * [incoming] 为来信摘录（即取即用）；[category] 决定通知副标题。
     * @return 是否真正发出（false = 节流命中或权限缺失）。
     */
    suspend fun notifyHandoff(
        contact: ContactRef,
        conversationId: Long,
        incoming: String,
        category: HandoffCategory,
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
        val body = context.getString(R.string.float_escalation_handoff_text, category.label, incoming)
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
     * 高优铃声通知：floatball_escalation 渠道（IMPORTANCE_HIGH + 默认通知声 +
     * 震动 + CATEGORY_ALARM），锁屏全屏横幅（USE_FULL_SCREEN_INTENT，普通权限）。
     * 点击经 service action 拉起分屏到该会话（**不带代拟条**——接管场景不把
     * AI 草稿挂一键发送，答案必须用户亲笔）。
     */
    private fun postEscalation(
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

        /** 通知 id 派生基座：与起草通道 2_000_000 不撞。 */
        private const val NOTIFICATION_ID_BASE = 2_100_000L
        private const val REQUEST_CODE_BASE = 2_100_000L

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
    }
}
