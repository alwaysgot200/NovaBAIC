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
import com.verlintas.baic2.floatball.auto.AutoSessionScheduler
import com.verlintas.baic2.floatball.auto.ImNavigator
import com.verlintas.baic2.floatball.contact.ContactDirectory
import com.verlintas.baic2.floatball.notify.NotifyGate
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
 * 转**陌生来信通知**——不拟稿不落库，只提示主人，方案 §3.4）→
 * [ScreenAdvisor.submitNotificationTurn] 后台回合 → 起草通知。
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

    @Inject
    lateinit var imNavigator: ImNavigator

    /** 多会话自动托管串行调度器（方案 §5.4）：命中托管目标的通知入队而非起草。 */
    @Inject
    lateinit var scheduler: AutoSessionScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 单飞：同一时刻只跑一个通知回合（turnMutex 已串行，这里防排队堆积）。 */
    private val inFlight = AtomicBoolean(false)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        // 主开关默认关（隐私优先，须在设置页显式开启 + 授予通知读取权限）；
        // 自动托管（多会话）运行期间即使起草档关闭也要放行感知（方案 §5.4.1）。
        val draftEnabled = advisorPrefs.state.value.draftEnabled
        if (!draftEnabled && !scheduler.isRunning()) return
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
        // contentIntent 捕获（托管方案 §四.A.2）：点通知直达会话的 PendingIntent
        // 是 ImNavigator 拉起会话的钥匙，即取即用只进内存缓存（隐私红线：不落盘
        // 不序列化不进日志）。联系人解析成功后在 handleDraftTurn 补挂 contactId 键；
        // 陌生来信通知也复用它做点击直达（同样只内存传递）。
        val contentIntent = notification.notification?.contentIntent
        imNavigator.remember(pkg, parsed.contactTitle, contentIntent)
        // 节流在解析之后、建档查询之前：重复通知连 DB 都不打。
        if (!gate.shouldDispatch(Throttle.keyOf(parsed), System.currentTimeMillis())) return
        scope.launch { handleDraftTurn(parsed, contentIntent, draftEnabled) }
    }

    private suspend fun handleDraftTurn(
        parsed: IncomingNotification,
        contentIntent: PendingIntent?,
        draftEnabled: Boolean,
    ) {
        if (!inFlight.compareAndSet(false, true)) return
        try {
            val contact = contactDirectory.resolve(
                imPackage = parsed.packageName,
                rawTitle = parsed.contactTitle,
                allowStub = false,
            ) ?: run {
                // 未建档联系人：陌生来信通知（方案 §3.4）——不创建会话不落库
                //（M0 同款纪律），不拟稿不自动回复，只通知主人；点击通知经原
                // contentIntent 直开该聊天（不消耗 LLM）。
                if (draftEnabled) postStrangerNotification(parsed, contentIntent)
                return
            }
            // 导航缓存补挂 contactId 键（§四.A.2）：回合成败与否，键先就位。
            imNavigator.bindContact(parsed.packageName, contact.contactId, parsed.contactTitle)
            // 维持度第一手开关（v10 方案 §2.6）：mute 档静默丢弃（不起草不出稿），
            // reactive/proactive 照常参谋；与白名单过滤同层前置，ProactivePolicy
            // 的 enabled 是第二层频控开关。
            if (!EngagementRules.incomingAllowed(contact.engagement)) return
            // 联系人级开关顺带检查（M2-b 只消费频控参数，这里只看 enabled）。
            advisorDb.proactivePolicyDao().forContact(contact.contactId)
                ?.takeIf { !it.enabled }
                ?.let { return }
            // 命中自动托管组：入串行调度器队列（去抖/去重/配额由调度器负责），
            // 不再走「起草待确认」路径（方案 §5.4.2）。
            if (scheduler.matches(parsed.packageName, contact.contactId)) {
                scheduler.enqueue(parsed.packageName, contact, parsed.contactTitle, parsed.body)
                return
            }
            if (!draftEnabled) return
            val result = screenAdvisor.submitNotificationTurn(parsed.body, contact)
            if (result.outcome != ScreenAdvisor.SubmitOutcome.RAN) return
            // 接管通报命中（2026-10-20 通报方案 §4.5）：铃声通报是唯一出口，
            // 抑制 AI 草稿通知——接管场景答案必须用户亲笔，不挂一键发送。
            if (result.handoff) return
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
        if (!NotifyGate.notifyIfPermitted(this, NOTIFICATION_ID, notification)) {
            Log.d(TAG, "draft notification skipped: no permission")
        }
    }

    /**
     * 陌生来信通知（方案 §3.4，「全部联系人」托管的感知面）：未建档联系人的
     * 来信不拟稿、不自动回复、不创建会话不落库，只提示主人；点击经原
     * contentIntent 直开该聊天。通知 id 按（包名|标题）散列，不同陌生来源
     * 互不覆盖。文本即取即用（同隐私红线，不落盘不进日志）。
     */
    private fun postStrangerNotification(parsed: IncomingNotification, contentIntent: PendingIntent?) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    STRANGER_CHANNEL_ID,
                    getString(R.string.float_stranger_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(parsed.packageName, 0),
            ).toString()
        }.getOrNull() ?: parsed.packageName
        val preview = parsed.body.replace('\n', ' ').let {
            if (it.length > STRANGER_PREVIEW_MAX) it.take(STRANGER_PREVIEW_MAX) + "…" else it
        }
        val text = getString(R.string.float_stranger_notif_body, preview, appLabel)
        val notification = NotificationCompat.Builder(this, STRANGER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.float_stranger_notif_title, parsed.contactTitle))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
        NotifyGate.notifyIfPermitted(this, strangerNotificationId(parsed), notification)
    }

    /** 陌生来信通知 id：（包名|标题）散列 + 基址，远离既有固定 id（2001 等）。 */
    private fun strangerNotificationId(parsed: IncomingNotification): Int =
        STRANGER_ID_BASE + (parsed.packageName + '|' + parsed.contactTitle)
            .hashCode().and(0x1FFFFFFF)

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

        /** 陌生来信通知（方案 §3.4）：独立通道 + 散列 id 基址（避开固定 id）。 */
        private const val STRANGER_CHANNEL_ID = "floatball_stranger"
        private const val STRANGER_ID_BASE = 0x20000000

        /** 陌生来信通知的正文预览上限（字符，通知栏摘要，不展示全文）。 */
        private const val STRANGER_PREVIEW_MAX = 40

        /** 起草通知点击：经 service action 打开分屏并预填草稿（复用 record 的先例）。 */
        const val ACTION_SHOW_DRAFT = "com.verlintas.baic2.floatball.SHOW_DRAFT"
        const val EXTRA_CONVERSATION_ID = "draft_conversation_id"
        const val EXTRA_CONTACT_ID = "draft_contact_id"
        const val EXTRA_CONTACT_NAME = "draft_contact_name"
        const val EXTRA_IM_PACKAGE = "draft_im_package"
        const val EXTRA_DRAFT = "draft_text"
    }
}
