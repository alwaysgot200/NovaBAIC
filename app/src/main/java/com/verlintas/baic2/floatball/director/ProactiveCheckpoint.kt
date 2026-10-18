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

package com.verlintas.baic2.floatball.director

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.verlintas.baic2.MainActivity
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.ScreenAdvisor
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.DirectorLogEntity
import com.verlintas.baic2.floatball.advisor.data.ROUTE_SMALL_TALK
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_PROACTIVE
import com.verlintas.baic2.floatball.brief.BriefCompiler
import com.verlintas.baic2.floatball.contact.ContactRef
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 对账单格：触发源×路由组合及窗口内计数。 */
data class LedgerCell(val trigger: String, val route: String, val count: Int)

/**
 * 对账汇总（方案 §13.1「派发后监视器」落点）：director_logs 近窗口的
 * 触发源×路由分布。[triggersSeen] 即「四触发源齐全可回放」验收的依据。
 */
data class LedgerSummary(
    val cells: List<LedgerCell>,
    val triggersSeen: List<String>,
    val total: Int,
) {
    /** 一行可读文本（落 log / 随周报通知）。 */
    fun cellLine(): String = cells.joinToString(" ") { "${it.trigger}/${it.route}=${it.count}" }
}

/** director_logs 对账统计（纯函数，JVM 可单测）。 */
object DirectorLedgerStats {

    /** 默认对账窗口：近 7 天（方案 §13.1）。 */
    const val DEFAULT_WINDOW_MS = 7L * 24 * DirectorRoutes.HOUR_MS

    fun summarize(
        entries: List<DirectorLogEntity>,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): LedgerSummary {
        val from = nowMs - windowMs
        val counts = LinkedHashMap<Pair<String, String>, Int>()
        val triggers = LinkedHashSet<String>()
        entries.forEach { entry ->
            if (entry.createdAt < from || entry.createdAt > nowMs) return@forEach
            val key = entry.trigger to entry.route
            counts[key] = (counts[key] ?: 0) + 1
            triggers.add(entry.trigger)
        }
        val cells = counts.map { (key, count) -> LedgerCell(key.first, key.second, count) }
            .sortedByDescending { it.count }
        return LedgerSummary(cells, triggers.toList(), cells.sumOf { it.count })
    }
}

/**
 * 主动时机周期检点（M2-b，方案 §8.1「周期调度载体」+ §13.1 对账消化）。
 *
 * 载体选型：上游 `AutomationScheduler`（tools 模块）经评估不可直接挂本检点——
 * ① 它只支持 TIME 触发器（每日固定 HH:mm），「每 N 小时」需按 24/N 硬拆多条
 * recipe；② recipe 必须持久化进上游 automations 表并对 AutomationExecutor
 * 暴露（工具序列执行器，无法回调 ScreenAdvisor 管线），且该表在主界面
 * 自动化功能可见可删，用户误删即静默断链。故按方案矛盾#5 的处置精神
 * （精确闹钟 + 逐次重挂 + 串行协程）在 floatball 包内实现轻量载体：
 * AlarmManager 单班闹钟（Android 12+ 按 canScheduleExactAlarms 降级 inexact，
 * AGENTS §6.1），每次触发后自重挂；服务创建（含开机 KeepAlive 自启路径）
 * 时重挂，总闸关闭即撤销。检点循环全在协程，主线程零参与。
 *
 * 每班做三件事：
 * 1. 对账消化（§13.1）：统计 director_logs 近 7 天触发源×路由分布，落 log 并
 *    随周报轻通知透出（四触发源齐全可回放的验收依据）；
 * 2. SCHEDULED 检点：对全部在档联系人先落 scheduled/brief 决策行（§10 M2
 *    「四触发源齐全可回放」——周报按联系粒度执行，决策行同粒度落库，无联系人
 *    归属的场景按 M0 口径只决策不落库、不造 dummy id），再调
 *    [BriefCompiler.refreshWeeklyIfStale]（自带 7 天节流与无数据静默），有新
 *    周报则发轻通知；
 * 3. PROACTIVE 检点：对 enabled 联系人逐个取频控数据（当日 proactive+smalltalk
 *    计数 / 上次主动时间 / 对方最近来信时间，§7.3 incoming 优先），过
 *    [DirectorRoutes] 六分支；SMALL_TALK 命中走
 *    [ScreenAdvisor.runProactiveSmalltalk] 保温管线并代发起草通知（人肉一键档），
 *    触发消息 id 回填台账回合锚点（§6 三层回放链）。
 */
@Singleton
class ProactiveCheckpoint @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AdvisorDatabase,
    private val screenAdvisor: ScreenAdvisor,
    private val briefs: BriefCompiler,
    private val prefs: AdvisorPrefs,
    private val director: Director,
) {

    /** 总闸开→挂下一班闹钟；关→撤销既有闹钟。开关切换/服务创建时调用。 */
    fun ensureScheduled() {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!prefs.state.value.proactiveEnabled) {
            manager.cancel(pendingIntent())
            return
        }
        val intervalMs = prefs.state.value.proactiveIntervalHours * DirectorRoutes.HOUR_MS
        val triggerAt = System.currentTimeMillis() + intervalMs
        // Android 12+ 精确闹钟是运行时豁免（AGENTS §6.1）：未授予则降级 inexact。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent())
        } else {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent())
        }
    }

    fun cancel() {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(pendingIntent())
    }

    /**
     * 一班检点。任何单点失败只降级不外抛：对账/周报/保温互不影响，
     * 台账写入失败由 Director 内部兜底，通知失败静默。
     */
    suspend fun runOnce(reason: String) {
        if (!prefs.state.value.proactiveEnabled) return
        val now = System.currentTimeMillis()

        // 1) 对账消化（方案 §13.1）：近 7 天触发源×路由分布。
        val logs = runCatching {
            db.directorLogDao().recentSince(now - DirectorLedgerStats.DEFAULT_WINDOW_MS)
        }.getOrDefault(emptyList())
        val summary = DirectorLedgerStats.summarize(logs, now)
        Log.i(
            TAG,
            "checkpoint($reason) ledger7d: ${summary.cellLine()} " +
                "triggers=${summary.triggersSeen.size}/4 total=${summary.total}",
        )

        // 2) SCHEDULED：先落 scheduled/brief 决策行（四触发源可回放），再周报惰性
        //    刷新（自带 7 天节流 + 无数据不调 LLM；节流在执行面，决策面每检点
        //    必记——与 PROACTIVE 分支同口径，频控/对账消费的是决策行）。
        val weeklyContacts = mutableListOf<String>()
        runCatching { db.contactProfileDao().listAllAlive() }.getOrDefault(emptyList())
            .forEach { contact ->
                director.route(
                    DirectorInput(trigger = DirectorTrigger.SCHEDULED, nowMs = now),
                    contactId = contact.id,
                )
                val brief = runCatching { briefs.refreshWeeklyIfStale(contact.id) }.getOrNull()
                if (brief != null) weeklyContacts.add(contact.displayName)
            }
        if (weeklyContacts.isNotEmpty()) {
            postWeeklyNotification(weeklyContacts, summary.cellLine())
        }

        // 3) PROACTIVE：enabled 联系人逐个过五分支频控。
        val dayStart = startOfDayMs(now)
        val policies = runCatching { db.proactivePolicyDao().listEnabled() }.getOrDefault(emptyList())
        policies.forEach { policy ->
            val contact = runCatching { db.contactProfileDao().findById(policy.contactId) }
                .getOrNull()
                ?.takeIf { it.mergedInto == null && it.deletedAt == null }
                ?: return@forEach
            val countToday = runCatching {
                db.directorLogDao()
                    .countSince(policy.contactId, TRIGGER_PROACTIVE, ROUTE_SMALL_TALK, dayStart)
            }.getOrDefault(0)
            val lastAt = runCatching {
                db.directorLogDao().latestAt(policy.contactId, TRIGGER_PROACTIVE, ROUTE_SMALL_TALK)
            }.getOrNull()
            // §7.3 incoming 优先：对方最近来信时间（v6 lastIncomingAt 聚合读）。
            val lastIncomingAt = runCatching {
                db.sessionBindingDao().lastIncomingAtFor(policy.contactId)
            }.getOrNull()
            val input = DirectorInput(
                trigger = DirectorTrigger.PROACTIVE,
                proactivePolicy = ProactivePolicySnapshot(
                    enabled = policy.enabled,
                    dailyCap = policy.dailyCap,
                    minGapHours = policy.minGapHours,
                    quietStart = policy.quietStart,
                    quietEnd = policy.quietEnd,
                ),
                proactiveCountToday = countToday,
                lastProactiveAtMs = lastAt,
                lastIncomingAtMs = lastIncomingAt,
                nowMs = now,
            )
            val decision = director.route(input, policy.contactId)
            if (decision.route != DirectorRoute.SMALL_TALK) return@forEach
            val ref = runCatching {
                val aliases = db.contactAliasDao().listFor(policy.contactId).map { it.alias }
                ContactRef(contact.id, contact.displayName, aliases, contact.goal, contact.style)
            }.getOrNull() ?: return@forEach
            val result = runCatching { screenAdvisor.runProactiveSmalltalk(ref) }
                .onFailure { Log.d(TAG, "proactive turn failed: contact=${policy.contactId}", it) }
                .getOrNull() ?: return@forEach
            // §6 三层回放链：检点先落决策行（无消息 id），回合落库触发消息后回填锚点。
            result.turnMessageId?.let { director.attachProactiveTurnMessage(policy.contactId, it) }
            val draft = result.draft?.takeIf { it.isNotBlank() } ?: return@forEach
            postDraftNotification(
                contactName = contact.displayName,
                imPackage = contact.imPackage,
                conversationId = result.conversationId,
                contactId = contact.id,
                draft = draft,
            )
        }
    }

    /** 本机时区的当日零点（频控「当日计数」的窗口起点）。 */
    private fun startOfDayMs(nowMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ProactiveCheckpointReceiver::class.java).setAction(ACTION_PROACTIVE_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 确保起草通知渠道存在（与 AdvisorNotificationListener 同渠道，文案归一）。 */
    private fun ensureChannel(manager: NotificationManagerCompat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    DRAFT_CHANNEL_ID,
                    context.getString(R.string.float_draft_channel),
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    /**
     * PROACTIVE 保温草稿通知：复用 M2-a 起草档交互（点击经 service action 拉起
     * 分屏预览，一键发送/改后发送），绝不自动发送。通知 id 与监听器错开防互踩。
     */
    private fun postDraftNotification(
        contactName: String,
        imPackage: String,
        conversationId: Long?,
        contactId: Long,
        draft: String,
    ) {
        if (conversationId == null) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(manager)
        val contentIntent = PendingIntent.getForegroundService(
            context,
            REQUEST_CODE,
            Intent(context, com.verlintas.baic2.floatball.FloatBallService::class.java)
                .setAction(AdvisorNotificationListener.ACTION_SHOW_DRAFT)
                .putExtra(AdvisorNotificationListener.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(AdvisorNotificationListener.EXTRA_CONTACT_ID, contactId)
                .putExtra(AdvisorNotificationListener.EXTRA_CONTACT_NAME, contactName)
                .putExtra(AdvisorNotificationListener.EXTRA_IM_PACKAGE, imPackage)
                .putExtra(AdvisorNotificationListener.EXTRA_DRAFT, draft),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, DRAFT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.float_proactive_draft_notif_title, contactName))
            .setContentText(draft)
            .setStyle(NotificationCompat.BigTextStyle().bigText(draft))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(DRAFT_NOTIFICATION_ID, notification) }
            .onFailure { Log.d(TAG, "proactive draft notification failed", it) }
    }

    /** SCHEDULED 副产物：周报轻通知（正文带对账分布，验收「四触发源可回放」的透出口）。 */
    private fun postWeeklyNotification(contactNames: List<String>, ledgerLine: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(manager)
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(
            R.string.float_proactive_weekly_notif_text,
            contactNames.joinToString("、"),
            ledgerLine,
        )
        val notification = NotificationCompat.Builder(context, DRAFT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.float_proactive_weekly_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(WEEKLY_NOTIFICATION_ID, notification) }
            .onFailure { Log.d(TAG, "weekly notification failed", it) }
    }

    companion object {
        const val ACTION_PROACTIVE_TICK = "com.verlintas.baic2.floatball.action.PROACTIVE_TICK"

        private const val TAG = "ProactiveCheckpoint"

        /** 与 AdvisorNotificationListener 同渠道复用起草通知交互（该常量在其内私有）。 */
        private const val DRAFT_CHANNEL_ID = "floatball_draft"
        private const val REQUEST_CODE = 2002
        private const val DRAFT_NOTIFICATION_ID = 2002
        private const val WEEKLY_NOTIFICATION_ID = 2003
    }
}

/** 检点闹钟接收器：跑一班检点并重挂下一班（应用内事件，不导出）。 */
@AndroidEntryPoint
class ProactiveCheckpointReceiver : BroadcastReceiver() {

    @Inject
    lateinit var checkpoint: ProactiveCheckpoint

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ProactiveCheckpoint.ACTION_PROACTIVE_TICK) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 失败不静默：检点链路断在哪家（DB/闹钟/通知）必须可从 logcat 追因，
                // 只记异常不带正文（本地优先：通知文本等内容不入日志）。
                runCatching { checkpoint.runOnce("alarm") }
                    .onFailure { Log.w(TAG, "proactive checkpoint failed", it) }
                checkpoint.ensureScheduled()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ProactiveCheckpoint"
    }
}
