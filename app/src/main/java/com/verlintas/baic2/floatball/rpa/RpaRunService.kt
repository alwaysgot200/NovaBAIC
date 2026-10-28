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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.verlintas.baic2.R
import com.verlintas.rpa.engine.RpaRunner
import com.verlintas.rpa.model.RpaRunEvent
import com.verlintas.rpa.store.RpaScriptStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RPA 后台运行服务：脚本执行不再依赖编辑器页面存活。
 *
 * - 列表 / 编辑器都通过 [ACTION_START] 启动，脚本从 [RpaScriptStore] 读已保存版本；
 * - 运行期间 [RpaChromeGate] 占位 `run`，悬浮球隐藏（避免进入视觉抽帧 / a11y 树 /
 *   误触打断流程），结束、失败、被系统销毁都在 `onDestroy` 释放；
 * - 通知栏常驻「运行中 · 脚本名」+「停止」；结束额外发一条可点开脚本库的结果通知；
 * - 同一时间只允许一个运行；`START_NOT_STICKY`，进程被杀后不自动重跑。
 */
@AndroidEntryPoint
class RpaRunService : Service() {

    @Inject
    lateinit var device: RpaAndroidDevice

    @Inject
    lateinit var store: RpaScriptStore

    @Inject
    lateinit var controller: RpaRunController

    @Inject
    lateinit var chromeGate: RpaChromeGate

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var scriptName: String = ""

    /** 结果只收口一次：停止命令与 finally 不会互相覆盖真实失败原因。 */
    private var outcomeRecorded = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        chromeGate.hide(RpaChromeGate.REASON_RUN)
        // startForegroundService 有 5s 时限：先挂「准备运行」，脚本加载在命令里做。
        startForegroundCompat(buildNotification(getString(R.string.float_rpa_run_preparing)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRun(intent.getStringExtra(EXTRA_SCRIPT_ID))
            ACTION_STOP -> stopRun("stopped")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRun(scriptId: String?) {
        if (job?.isActive == true) {
            // 同一时间只允许一个运行；第二命令不能静默消失，留痕便于排查。
            controller.record(RpaRunEvent.Log("start_ignored: already_running"))
            return
        }
        outcomeRecorded = false
        job = scope.launch {
            try {
                // 脚本加载（JSON 解析 + 文件 IO）不进主线程。
                val script = withContext(Dispatchers.IO) { scriptId?.let { store.load(it) } }
                if (script == null) {
                    // 此时 controller 仍是 Idle，finish() 不会收口，必须直接发结果通知。
                    notifyMissing(scriptId)
                    return@launch
                }
                scriptName = script.name
                controller.start(script)
                refreshNotification()
                try {
                    val runner = RpaRunner(
                        device = device,
                        templateExists = { sid, tid -> store.templateExists(sid, tid) },
                    )
                    val result = runner.run(script) { event ->
                        controller.record(event)
                        if (event is RpaRunEvent.StepStarted ||
                            event is RpaRunEvent.LineFallback
                        ) {
                            refreshNotification()
                        }
                    }
                    recordFinish(result.ok, result.detail)
                } catch (cancelled: CancellationException) {
                    recordFinish(false, "stopped")
                } catch (failure: Throwable) {
                    recordFinish(
                        false,
                        failure.message ?: failure::class.java.simpleName,
                    )
                } finally {
                    notifyFinished()
                }
            } finally {
                releaseAndStop()
            }
        }
    }

    private fun stopRun(detail: String) {
        val active = job?.isActive == true
        recordFinish(false, detail)
        if (active) {
            job?.cancel()
        } else {
            releaseAndStop()
        }
    }

    private fun recordFinish(ok: Boolean, detail: String) {
        if (outcomeRecorded) return
        outcomeRecorded = true
        controller.finish(ok, detail)
    }

    private fun releaseAndStop() {
        job = null
        chromeGate.show(RpaChromeGate.REASON_RUN)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        chromeGate.show(RpaChromeGate.REASON_RUN)
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------ notifications

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.float_rpa_run_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun refreshNotification() {
        val state = controller.state.value as? RpaRunController.State.Running ?: return
        startForegroundCompat(buildNotification(stepText(state)))
    }

    private fun stepText(state: RpaRunController.State.Running): String {
        val label = state.stepLabel.ifBlank {
            state.stepAction?.let { getString(rpaActionLabelRes(it)) }.orEmpty()
        }
        if (state.stepIndex < 0) {
            return getString(R.string.float_rpa_run_preparing)
        }
        return if (label.isBlank()) {
            getString(R.string.float_rpa_run_step_plain, state.displayIndex, state.stepCount)
        } else {
            getString(
                R.string.float_rpa_run_step,
                state.displayIndex,
                state.stepCount,
                label,
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val title = getString(
            R.string.float_rpa_run_notification_title,
            scriptName.ifBlank { getString(R.string.float_rpa_title) },
        )
        val state = controller.state.value as? RpaRunController.State.Running
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openLibraryPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.float_rpa_stop),
                stopPendingIntent(),
            )
        if (state != null && state.stepCount > 0 && state.stepIndex >= 0) {
            builder.setProgress(state.stepCount, state.displayIndex, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    /** 结果通知：服务已停，仍让用户知道成功/失败并一键回到脚本库。 */
    private fun notifyFinished() {
        val state = controller.state.value as? RpaRunController.State.Finished ?: return
        val title = getString(
            if (state.ok) {
                R.string.float_rpa_run_finished_ok
            } else {
                R.string.float_rpa_run_finished_failed
            },
        )
        val text = buildString {
            append(state.scriptName.ifBlank { getString(R.string.float_rpa_title) })
            if (state.detail.isNotBlank()) {
                append(" · ")
                append(state.detail)
            }
        }
        postResultNotification(title, text)
    }

    /** 脚本文件缺失 / 损坏：controller 尚未进入 Running，单独给用户一个结果通知。 */
    private fun notifyMissing(scriptId: String?) {
        val text = buildString {
            append(getString(R.string.float_rpa_script_missing))
            if (!scriptId.isNullOrBlank()) {
                append(" · ")
                append(scriptId)
            }
        }
        postResultNotification(getString(R.string.float_rpa_run_finished_failed), text)
    }

    private fun postResultNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openLibraryPendingIntent())
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(FINISHED_NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun openLibraryPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_LIBRARY,
        RpaActivity.intent(this),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, RpaRunService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_START = "com.verlintas.baic2.floatball.rpa.RUN_START"
        const val ACTION_STOP = "com.verlintas.baic2.floatball.rpa.RUN_STOP"
        const val EXTRA_SCRIPT_ID = "rpa_script_id"

        private const val CHANNEL_ID = "rpa_run"
        private const val NOTIFICATION_ID = 2301
        private const val FINISHED_NOTIFICATION_ID = 2302
        private const val REQUEST_OPEN_LIBRARY = 2303
        private const val REQUEST_STOP = 2304
    }
}
