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

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.verlintas.baic2.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保活授权引导与「体检」：电池优化、精确闹钟、通知、悬浮窗、开机组件状态，
 * 以及各厂商自启页跳转表（搬自 SmsForwarder SettingsFragment）。
 * 所有状态实时查系统，不落盘——真相在系统。
 */
@Singleton
class KeepAliveUtils @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** 一项体检：稳定 key、是否就绪、点击后跳转的 Intent（null = 纯开关类，在 UI 内处理）。 */
    data class HealthCheck(
        val key: String,
        val ready: Boolean,
        val intent: Intent?,
    )

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    /** 申请电池优化白名单的系统对话框；厂商 ROM 可能拦截，失败由调用方兜底列表页。 */
    fun batteryOptimizationRequest(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryOptimizationSettings(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** API 31+ 精确闹钟调度资格；低版本恒为 true（安装即授予）。 */
    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false

    fun exactAlarmSettings(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull()
    }

    /**
     * 保活体检项（分母随档位变化，见方案 §7.2）：
     * 低耗电 = 通知/悬浮窗/开机组件；智能 = + 电池白名单/心跳；
     * 永久 = + 精确闹钟。
     *
     * @param heartbeatScheduled 当前档位下心跳 job 是否在排（由 Manager 实时查询）
     */
    fun healthChecks(mode: KeepAlivePrefs.Mode, heartbeatScheduled: Boolean): List<HealthCheck> {
        val checks = mutableListOf(
            HealthCheck(
                key = KEY_NOTIFICATION,
                ready = notificationsEnabled(),
                intent = null, // 运行时权限由设置页内联申请
            ),
            HealthCheck(
                key = KEY_OVERLAY,
                ready = Settings.canDrawOverlays(context),
                intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ),
            HealthCheck(
                key = KEY_BOOT,
                ready = isBootReceiverEnabled(),
                intent = autoStartIntent(),
            ),
        )
        if (mode != KeepAlivePrefs.Mode.LOW) {
            checks += HealthCheck(
                key = KEY_BATTERY,
                ready = isIgnoringBatteryOptimizations(),
                intent = batteryOptimizationRequest(),
            )
            checks += HealthCheck(
                key = KEY_HEARTBEAT,
                ready = heartbeatScheduled,
                intent = null,
            )
        }
        if (mode == KeepAlivePrefs.Mode.PERMANENT) {
            checks += HealthCheck(
                key = KEY_EXACT_ALARM,
                ready = canScheduleExactAlarms(),
                intent = exactAlarmSettings(),
            )
        }
        return checks
    }

    fun isBootReceiverEnabled(): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(
            ComponentName(context, KeepAliveBootReceiver::class.java),
        )
        // COMPONENT_ENABLED_STATE_DEFAULT 视为清单默认（enabled）。
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    }

    /**
     * 厂商自启页跳转（含 `/` 的按组件名直达，否则拉对应安全管家的启动入口；
     * 全失败回应用详情页）。
     */
    fun autoStartIntent(): Intent {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val candidates = AUTO_START_TABLE[manufacturer]
            ?: AUTO_START_TABLE[manufacturerTokens(manufacturer)]
        if (candidates != null) {
            for (candidate in candidates) {
                val intent = runCatching {
                    if (candidate.contains("/")) {
                        Intent().apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            component = ComponentName.unflattenFromString(candidate)
                        }
                    } else {
                        context.packageManager.getLaunchIntentForPackage(candidate)
                    }
                }.getOrNull()
                if (intent != null && intent.resolveActivity(context.packageManager) != null) {
                    return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 当前品牌的自启引导文案资源（找不到给通用兜底）。 */
    fun autoStartHintRes(): Int = when (Build.MANUFACTURER.lowercase()) {
        "vivo", "iqoo" -> R.string.float_keepalive_auto_start_vivo
        "huawei" -> R.string.float_keepalive_auto_start_huawei
        "honor" -> R.string.float_keepalive_auto_start_honor
        "xiaomi" -> R.string.float_keepalive_auto_start_xiaomi
        "redmi" -> R.string.float_keepalive_auto_start_redmi
        "oppo" -> R.string.float_keepalive_auto_start_oppo
        "oneplus" -> R.string.float_keepalive_auto_start_oneplus
        "meizu" -> R.string.float_keepalive_auto_start_meizu
        "samsung" -> R.string.float_keepalive_auto_start_samsung
        "letv" -> R.string.float_keepalive_auto_start_letv
        "zte" -> R.string.float_keepalive_auto_start_zte
        else -> R.string.float_keepalive_auto_start_unknown
    }

    /** 部分厂商 MANUFACTURER 与表中 key 写法不完全一致时的归一化。 */
    private fun manufacturerTokens(manufacturer: String): String? = when {
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> "vivo"
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> "xiaomi"
        manufacturer.contains("huawei") -> "huawei"
        manufacturer.contains("honor") -> "honor"
        manufacturer.contains("oppo") || manufacturer.contains("realme") -> "oppo"
        manufacturer.contains("oneplus") -> "oneplus"
        manufacturer.contains("meizu") -> "meizu"
        manufacturer.contains("samsung") -> "samsung"
        else -> null
    }

    companion object {
        const val KEY_NOTIFICATION = "notification"
        const val KEY_OVERLAY = "overlay"
        const val KEY_BOOT = "boot"
        const val KEY_BATTERY = "battery"
        const val KEY_HEARTBEAT = "heartbeat"
        const val KEY_EXACT_ALARM = "exact_alarm"

        /**
         * 厂商 → 候选组件/包名（搬自 SmsForwarder，GPL-3.0 兼容）。
         * 逐项尝试，系统版本变化导致单个组件失效时降级到安全管家首页。
         */
        private val AUTO_START_TABLE: Map<String, List<String>> = mapOf(
            "xiaomi" to listOf(
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter",
            ),
            "redmi" to listOf(
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter",
            ),
            "samsung" to listOf(
                "com.samsung.android.sm_cn/com.samsung.android.sm.ui.ram.AutoRunActivity",
                "com.samsung.android.sm_cn/.app.dashboard.SmartManagerDashBoardActivity",
                "com.samsung.android.sm/com.samsung.android.sm.ui.ram.AutoRunActivity",
                "com.samsung.android.sm/.app.dashboard.SmartManagerDashBoardActivity",
                "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
                "com.samsung.android.sm_cn",
                "com.samsung.android.sm",
            ),
            "huawei" to listOf(
                "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
                "com.huawei.systemmanager/.optimize.process.ProtectActivity",
                "com.huawei.systemmanager/.optimize.bootstart.BootStartActivity",
                "com.huawei.systemmanager",
            ),
            "honor" to listOf(
                "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager/.optimize.bootstart.BootStartActivity",
                "com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager",
                "com.hihonor.systemmanager",
            ),
            "vivo" to listOf(
                "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
                "com.iqoo.secure/.safeguard.PurviewTabActivity",
                "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
                "com.iqoo.secure",
                "com.vivo.permissionmanager",
            ),
            "meizu" to listOf(
                "com.meizu.safe/.permission.SmartBGActivity",
                "com.meizu.safe/.permission.PermissionMainActivity",
                "com.meizu.safe",
            ),
            "oppo" to listOf(
                "com.coloros.safecenter/.startupapp.StartupAppListActivity",
                "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
                "com.oppo.safe/.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter",
                "com.oppo.safe",
            ),
            "oneplus" to listOf(
                "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
                "com.oneplus.security",
            ),
            "letv" to listOf(
                "com.letv.android.letvsafe/.AutobootManageActivity",
                "com.letv.android.letvsafe/.BackgroundAppManageActivity",
                "com.letv.android.letvsafe",
            ),
            "zte" to listOf(
                "com.zte.heartyservice/.autorun.AppAutoRunManager",
                "com.zte.heartyservice",
            ),
            "lenovo" to listOf(
                "com.lenovo.security/.purebackground.PureBackgroundActivity",
                "com.lenovo.security",
            ),
        )
    }
}
