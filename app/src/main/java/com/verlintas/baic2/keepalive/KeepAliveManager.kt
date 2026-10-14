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

package com.verlintas.baic2.keepalive

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.view.OneShotPreDrawListener
import com.verlintas.baic2.floatball.FloatBallService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保活门面：统一应用/撤销全部保活机制（开机组件、Job 心跳、精确闹钟链、
 * 无声音乐、最近任务隐藏），并提供「按豁免优先级拉活服务」的唯一入口。
 *
 * targetSdk 36 下拉活豁免顺序（详见 docs/keepalive-design.md §6.4）：
 * 1. BOOT 等豁免广播接收期；2. 用户已关闭电池优化（任意时刻）；
 * 3. 精确闹钟触发；4. API 35+ 需「此刻存在可见 overlay」——
 *    用占位悬浮球先挂窗再启 FGS 降级。
 */
@Singleton
class KeepAliveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: KeepAlivePrefs,
    private val utils: KeepAliveUtils,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 降级路径的占位悬浮球；服务 onCreate 后立即摘除。 */
    @Volatile
    private var placeholder: View? = null

    /** 占位窗→启服务动作的一次性闸门（首帧回调与 400ms 兜底只允许触发一次）。 */
    private val placeholderProceedFired = AtomicBoolean(false)

    // region 档位应用 -----------------------------------------------------------------------

    /**
     * 按当前档位应用全部机制。
     *
     * @param fromForeground 是否从设置页等前台入口调用——只有前台路径才允许
     * 新启动无声音乐（mediaPlayback 在 A15 BOOT 受限类型清单内）。
     */
    fun applyMode(fromForeground: Boolean = false) {
        val config = prefs.state.value
        setBootReceiverEnabled(config.bootEnabled)
        applyRecentsExclusion(config.excludeRecents)
        applySchedules(config)
        val musicWanted = config.mode == KeepAlivePrefs.Mode.PERMANENT && config.silenceMusic
        if (musicWanted) {
            // 已在跑不动它；前台切换时补启（系统刚杀过音乐的场景）。
            if (fromForeground && !KeepAliveMusicService.isRunning) startMusic()
        } else {
            stopMusic()
        }
    }

    /**
     * 按档位排布心跳/闹钟，全应用唯一的调度决策点：
     * LOW = 全部撤销；SMART = 仅 Job 心跳；PERMANENT = 心跳 + 精确闹钟链。
     */
    private fun applySchedules(config: KeepAlivePrefs.Config) {
        when (config.mode) {
            KeepAlivePrefs.Mode.LOW -> {
                cancelHeartbeat()
                cancelNextAlarm()
            }
            KeepAlivePrefs.Mode.SMART -> {
                scheduleHeartbeat()
                cancelNextAlarm()
            }
            KeepAlivePrefs.Mode.PERMANENT -> {
                scheduleHeartbeat()
                scheduleNextAlarm()
            }
        }
    }

    // endregion

    // region 服务生命周期挂钩 ----------------------------------------------------------------

    /** FloatBallService.onCreate：服务存活锚点 + 按档位补齐调度。 */
    fun onServiceCreated() {
        prefs.serviceDesired = true
        removePlaceholderOverlay()
        val config = prefs.state.value
        applyRecentsExclusion(config.excludeRecents)
        applySchedules(config)
    }

    /** 用户从扇形菜单显式退出：心跳/闹钟此后不再复活。 */
    fun onUserExit() {
        prefs.serviceDesired = false
        cancelNextAlarm()
        stopMusic()
    }

    /** 最近任务被划掉（永久档加固）：确保调度仍在。 */
    fun onTaskRemoved() {
        if (prefs.state.value.mode == KeepAlivePrefs.Mode.PERMANENT) {
            scheduleHeartbeat()
            scheduleNextAlarm()
        }
    }

    /** 最近任务隐藏开关当前值（供服务层启动主界面时补 FLAG）。 */
    fun isRecentsExclusionEnabled(): Boolean = prefs.state.value.excludeRecents

    // endregion

    // region 拉活 ---------------------------------------------------------------------------

    /** 开机/更新广播：拉服务 + 全量重排（闹钟不跨重启存活）。 */
    fun onBootCompleted() {
        val config = prefs.state.value
        if (!config.bootEnabled || !prefs.serviceDesired) return
        if (Settings.canDrawOverlays(context)) {
            // BOOT_COMPLETED / MY_PACKAGE_REPLACED 属豁免广播；QUICKBOOT_POWERON
            // 是厂商广播、可能不享豁免——统一尝试，失败时不崩溃、仅靠调度延后。
            val started = startFloatService()
            if (!started && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                Log.w(TAG, "boot: direct FGS start rejected, relying on heartbeat/alarm")
            }
        }
        applySchedules(config)
    }

    /** 心跳 Job 触发：服务不在则按豁免优先级拉活；顺带复活无声音乐。 */
    fun onHeartbeat() {
        reviveServiceIfNeeded(fromExactAlarm = false)
        // 音乐只允许「FGS 互启」：悬浮球 FGS 已在前台时启动 mediaPlayback 合法；
        // 重启后最早一拍心跳也已在 BOOT 豁免窗口之外，不踩 A15 受限类型清单。
        if (FloatBallService.isRunning) ensureMusicWanted()
    }

    /** 精确闹钟触发（A15+ 最硬豁免）：重排 + 拉活。 */
    fun onAlarmFired() {
        val config = prefs.state.value
        if (config.mode != KeepAlivePrefs.Mode.PERMANENT) return
        scheduleHeartbeat()
        scheduleNextAlarm()
        reviveServiceIfNeeded(fromExactAlarm = true)
        if (FloatBallService.isRunning) ensureMusicWanted()
    }

    /**
     * 检查「期望运行 + 未运行 + 有悬浮窗权限」后拉活 FloatBallService。
     * 必须在主线程调用（涉及 WindowManager）。
     */
    private fun reviveServiceIfNeeded(fromExactAlarm: Boolean) {
        if (!prefs.serviceDesired || FloatBallService.isRunning) return
        if (!Settings.canDrawOverlays(context)) return

        val directAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM ||
            utils.isIgnoringBatteryOptimizations() ||
            fromExactAlarm
        if (directAllowed) {
            if (startFloatService()) return
            // 直启被拒且非 API35+ 时没有降级手段，等下一拍。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        }
        // API 35+ 无直接豁免（或直启被拒）：先挂「可见 overlay」占位窗再启动。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            showPlaceholderThenStart()
        }
    }

    private fun startFloatService(): Boolean = runCatching {
        ContextCompat.startForegroundService(
            context,
            Intent(context, FloatBallService::class.java),
        )
        true
    }.getOrElse {
        Log.w(TAG, "startForegroundService(FloatBallService) rejected", it)
        false
    }

    /** 永久档且音乐开关开：补启无声音乐（调用前须确认悬浮球 FGS 已在运行）。 */
    private fun ensureMusicWanted() {
        val config = prefs.state.value
        if (prefs.serviceDesired &&
            config.mode == KeepAlivePrefs.Mode.PERMANENT &&
            config.silenceMusic &&
            !KeepAliveMusicService.isRunning
        ) {
            startMusic()
        }
    }

    /**
     * 占位 overlay 降级（仅 API 35+ 且无更优豁免时）：挂一个约 8dp、
     * alpha 0.15 的光点（必须非零尺寸/非全透明才满足 visible window 判定），
     * 一帧绘制后启 FGS；FloatBallService.onCreate 会立即摘除，
     * 另设 10 秒兜底摘除防残留。
     */
    private fun showPlaceholderThenStart() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showPlaceholderThenStart() }
            return
        }
        if (placeholder != null || FloatBallService.isRunning) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density
        val size = (8 * density).toInt().coerceAtLeast(4)
        val view = View(context).apply { setBackgroundColor(0x265B86E5) }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = size
            y = context.resources.displayMetrics.heightPixels / 2
        }
        if (runCatching { wm.addView(view, params) }.isFailure) {
            Log.w(TAG, "placeholder overlay addView rejected")
            return
        }
        placeholder = view
        placeholderProceedFired.set(false)
        val proceed = Runnable {
            // 首帧监听与 400ms 兜底都可能触发：AtomicBoolean 保证只启一次。
            if (placeholderProceedFired.compareAndSet(false, true) &&
                !FloatBallService.isRunning &&
                startFloatService()
            ) {
                // 等悬浮球 FGS 完成 onCreate 再补音乐（FGS 互启才合法）。
                mainHandler.postDelayed(
                    { if (FloatBallService.isRunning) ensureMusicWanted() },
                    MUSIC_AFTER_REVIVE_DELAY_MS,
                )
            }
        }
        OneShotPreDrawListener.add(view) { mainHandler.post(proceed) }
        view.postDelayed(proceed, PLACEHOLDER_PROCEED_FALLBACK_MS)
        mainHandler.postDelayed({ removePlaceholderOverlay() }, PLACEHOLDER_TIMEOUT_MS)
    }

    /** 摘除占位悬浮球（FloatBallService.onCreate 调用，或超时兜底）。 */
    fun removePlaceholderOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { removePlaceholderOverlay() }
            return
        }
        val view = placeholder ?: return
        placeholder = null
        // 残留的 proceed/超时 Runnable 都带空判与 CAS，成为无害空操作，无需摘除。
        runCatching {
            context.getSystemService(WindowManager::class.java)?.removeView(view)
        }.onFailure { Log.w(TAG, "remove placeholder overlay failed", it) }
    }

    // endregion

    // region Job 心跳 ------------------------------------------------------------------------

    private fun scheduleHeartbeat() {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val info = JobInfo.Builder(JOB_ID_HEARTBEAT, ComponentName(context, KeepAliveHeartbeatJob::class.java))
            .setPeriodic(HEARTBEAT_INTERVAL_MS)
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .build()
        runCatching { scheduler.schedule(info) }
            .onFailure { Log.w(TAG, "schedule heartbeat job failed", it) }
    }

    private fun cancelHeartbeat() {
        runCatching {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID_HEARTBEAT)
        }.onFailure { Log.w(TAG, "cancel heartbeat job failed", it) }
    }

    fun isHeartbeatScheduled(): Boolean = runCatching {
        context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID_HEARTBEAT) != null
    }.getOrDefault(false)

    // endregion

    // region 精确闹钟链 ----------------------------------------------------------------------

    private fun scheduleNextAlarm() {
        if (!prefs.serviceDesired) return
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS
        runCatching {
            if (utils.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    alarmPendingIntent(),
                )
            } else {
                // 未获精确闹钟权限：尽力降级（不享 FGS 启动豁免，仅维持节拍）。
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    ALARM_WINDOW_MS,
                    alarmPendingIntent(),
                )
            }
        }.onFailure { Log.w(TAG, "schedule next alarm failed", it) }
    }

    private fun cancelNextAlarm() {
        runCatching {
            context.getSystemService(AlarmManager::class.java)?.cancel(alarmPendingIntent())
        }.onFailure { Log.w(TAG, "cancel alarm failed", it) }
    }

    private fun alarmPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_ALARM,
            Intent(ACTION_HEARTBEAT_ALARM).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // endregion

    // region 其他机制 ------------------------------------------------------------------------

    fun applyRecentsExclusion(enabled: Boolean) {
        runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(ActivityManager::class.java)
                ?.appTasks
                ?.forEach { task -> runCatching { task.setExcludeFromRecents(enabled) } }
        }.onFailure { Log.w(TAG, "apply recents exclusion failed", it) }
    }

    private fun setBootReceiverEnabled(enabled: Boolean) {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, KeepAliveBootReceiver::class.java),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { Log.w(TAG, "set boot receiver enabled=$enabled failed", it) }
    }

    fun startMusic() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KeepAliveMusicService::class.java),
            )
        }.onFailure { Log.w(TAG, "start music service rejected", it) }
    }

    fun stopMusic() {
        runCatching { context.stopService(Intent(context, KeepAliveMusicService::class.java)) }
            .onFailure { Log.w(TAG, "stop music service failed", it) }
    }

    // endregion

    companion object {
        private const val TAG = "KeepAliveManager"

        /** 心跳/闹钟节拍：15 分钟（JobScheduler 周期下限；Doze exact 限流 9 分钟之上）。 */
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L
        private const val ALARM_WINDOW_MS = 2 * 60_000L
        private const val JOB_ID_HEARTBEAT = 0x4B41
        private const val REQUEST_ALARM = 0x4B41

        /** 占位窗首帧未回调时的兜底触发时间。 */
        private const val PLACEHOLDER_PROCEED_FALLBACK_MS = 400L
        private const val PLACEHOLDER_TIMEOUT_MS = 10_000L

        /** 拉活成功后补启音乐前，给悬浮球 FGS 的 onCreate 预留的时间。 */
        private const val MUSIC_AFTER_REVIVE_DELAY_MS = 300L

        const val ACTION_HEARTBEAT_ALARM =
            "com.verlintas.baic2.keepalive.action.HEARTBEAT_ALARM"
    }
}
