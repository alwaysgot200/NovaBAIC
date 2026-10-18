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

package com.verlintas.baic2.floatball.director

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.FloatBallService
import com.verlintas.baic2.floatball.ScreenAdvisor
import com.verlintas.baic2.floatball.advisor.EngagementRules
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.contact.ContactDirectory
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 起草档的感知源（M2-a，方案 §8.1 N1~N4）：聊天类通知 → 包名白名单 + 联系人
 * 别名匹配（[ContactDirectory.resolve]，allowStub=false 只认既有档案，未命中
 * **静默忽略**）→ [ScreenAdvisor.submitNotificationTurn] 后台回合 → 起草通知。
 *
 * 隐私红线（方案 §8.3）：通知文本即取即用——只在内存中传递给参谋管线（与
 * OCR 文本同径落本地会话），监听器自身不落盘、不写日志、不进任何持久化。
 *
 * 发送决策位=人肉一键发：起草通知只拉起分屏预览，绝不自动发出任何消息
 * （自动发送属 M3 自动档，须 Gatekeeper 硬审，另行单独评审）。
 */
@AndroidEntryPoint
class AdvisorNotificationListener : NotificationListenerService() {

    @Inject
    lateinit var screenAdvisor: ScreenAdvisor

    @Inject
    lateinit var contactDirectory: ContactDirectory

    @Inject
    lateinit var advisorPrefs: com.verlintas.baic2.floatball.AdvisorPrefs

    @Inject
    lateinit var advisorDb: AdvisorDatabase

    @Inject
    lateinit var gate: NotificationGate

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 单飞：同一时刻只跑一个通知回合（turnMutex 已串行，这里防排队堆积）。 */
    private val inFlight = AtomicBoolean(false)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        // 主开关默认关（隐私优先，须在设置页显式开启 + 授予通知读取权限）。
        if (!advisorPrefs.state.value.draftEnabled) return
        val pkg = notification.packageName ?: return
        if (pkg == packageName) return
        if (notification.isOngoing) return
        if (!gate.isListenedPackage(pkg)) return
        val extras = notification.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        // 大文本优先：许多 IM 的 EXTRA_TEXT 只放摘要，正文在 EXTRA_BIG_TEXT。
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        val parsed = gate.parse(pkg, title, body) ?: return
        // 节流在解析之后、建档查询之前：重复通知连 DB 都不打。
        if (!gate.shouldDispatch(Throttle.keyOf(parsed), System.currentTimeMillis())) return
        scope.launch { handleDraftTurn(parsed) }
    }

    private suspend fun handleDraftTurn(parsed: IncomingNotification) {
        if (!inFlight.compareAndSet(false, true)) return
        try {
            val contact = contactDirectory.resolve(
                imPackage = parsed.packageName,
                rawTitle = parsed.contactTitle,
                allowStub = false,
            ) ?: return // 未建档联系人：静默忽略，不创建会话不落库（M0 同款纪律）。
            // 维持度第一手开关（v10 方案 §2.6）：mute 档静默丢弃（不起草不出稿），
            // reactive/proactive 照常参谋；与白名单过滤同层前置，ProactivePolicy
            // 的 enabled 是第二层频控开关。
            if (!EngagementRules.incomingAllowed(contact.engagement)) return
            // 联系人级开关顺带检查（M2-b 只消费频控参数，这里只看 enabled）。
            advisorDb.proactivePolicyDao().forContact(contact.contactId)
                ?.takeIf { !it.enabled }
                ?.let { return }
            val result = screenAdvisor.submitNotificationTurn(parsed.body, contact)
            if (result.outcome != ScreenAdvisor.SubmitOutcome.RAN) return
            val draft = result.draft ?: return
            val conversationId = result.conversationId ?: return
            postDraftNotification(
                contactName = contact.displayName,
                imPackage = parsed.packageName,
                conversationId = conversationId,
                contactId = contact.contactId,
                draft = draft,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 只记原因不记内容：通知/草稿文本不允许进日志。
            Log.d(TAG, "draft turn failed", error)
        } finally {
            inFlight.set(false)
        }
    }

    /** 发起草通知（N4→N5）：点击经 service action 拉起分屏预览，可一键发送/改后发送。 */
    private fun postDraftNotification(
        contactName: String,
        imPackage: String,
        conversationId: Long,
        contactId: Long,
        draft: String,
    ) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.float_draft_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val contentIntent = PendingIntent.getForegroundService(
            this,
            REQUEST_CODE,
            Intent(this, FloatBallService::class.java)
                .setAction(ACTION_SHOW_DRAFT)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(EXTRA_CONTACT_ID, contactId)
                .putExtra(EXTRA_CONTACT_NAME, contactName)
                .putExtra(EXTRA_IM_PACKAGE, imPackage)
                .putExtra(EXTRA_DRAFT, draft),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.float_draft_notif_title, contactName))
            .setContentText(draft)
            .setStyle(NotificationCompat.BigTextStyle().bigText(draft))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.d(TAG, "draft notification failed", it) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private object Throttle {
        fun keyOf(parsed: IncomingNotification): String = "${parsed.packageName}|${parsed.contactTitle}"
    }

    companion object {
        private const val TAG = "AdvisorNotifListener"
        private const val CHANNEL_ID = "floatball_draft"
        private const val NOTIFICATION_ID = 2001
        private const val REQUEST_CODE = 2001

        /** 起草通知点击：经 service action 打开分屏并预填草稿（复用 record 的先例）。 */
        const val ACTION_SHOW_DRAFT = "com.verlintas.baic2.floatball.SHOW_DRAFT"
        const val EXTRA_CONVERSATION_ID = "draft_conversation_id"
        const val EXTRA_CONTACT_ID = "draft_contact_id"
        const val EXTRA_CONTACT_NAME = "draft_contact_name"
        const val EXTRA_IM_PACKAGE = "draft_im_package"
        const val EXTRA_DRAFT = "draft_text"
    }
}
