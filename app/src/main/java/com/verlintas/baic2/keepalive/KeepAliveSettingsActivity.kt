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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.floatball.FloatBallService
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.floatball.AdvisorPage
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * 保活设置页：保活体检卡 + 强度三档（低耗电/智能/永久）+ 授权引导。
 * 入口在老参谋设置页（FloatSettingsActivity），不进扇形菜单。
 * LLM 请求重试参数已于 2026-10-22 迁至老参谋设置页（语义归位）。
 * 全部设置即时落盘、无保存按钮；系统状态（权限/调度）一律实时查询、
 * 从系统设置返回时 onResume 重算。
 */
@AndroidEntryPoint
class KeepAliveSettingsActivity : ComponentActivity() {

    @Inject
    lateinit var prefs: KeepAlivePrefs

    @Inject
    lateinit var manager: KeepAliveManager

    @Inject
    lateinit var utils: KeepAliveUtils

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun attachBaseContext(newBase: Context) {
        val language = AppLocaleStore.readFrom(newBase)
        val tag = language.languageTag
        super.attachBaseContext(
            if (tag == null) newBase else newBase.withLocale(Locale.forLanguageTag(tag)),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val accent by settingsRepository.accentColor
                .collectAsStateWithLifecycle(initialValue = AccentColor.BLUE)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            androidx.compose.runtime.SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            Baic2Theme(darkTheme = darkTheme, accent = accent) {
                KeepAliveScreen(
                    prefs = prefs,
                    manager = manager,
                    utils = utils,
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun KeepAliveScreen(
    prefs: KeepAlivePrefs,
    manager: KeepAliveManager,
    utils: KeepAliveUtils,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val config by prefs.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    // onResume 重算所有系统状态（用户从系统设置/授权页回来立即刷新）。
    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val checks = remember(config.mode, refreshTick) {
        utils.healthChecks(config.mode, manager.isHeartbeatScheduled())
    }
    val batteryWhitelisted = remember(refreshTick) { utils.isIgnoringBatteryOptimizations() }

    var confirmPermanent by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && FloatBallService.isRunning) {
            // 方案 §6.7：授予后让运行中的服务重挂前台通知，常驻提示立即可见。
            runCatching {
                context.startService(
                    Intent(context, FloatBallService::class.java)
                        .setAction(FloatBallService.ACTION_REPOST_NOTIFICATION),
                )
            }
        }
        refreshTick++
    }

    /** @return 是否成功打开目标页；失败时调用方可降级到兜底页。 */
    fun safeStart(intent: Intent?): Boolean {
        intent ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        AdvisorPage(
            title = stringResource(R.string.float_keepalive_title),
            onBack = onClose,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.float_keepalive_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )

                    // ---- 体检卡 ----------------------------------------------------------
                    HealthCard(
                        checks = checks,
                        onFixAll = {
                            // 逐项处理：通知走内联授权，其余跳对应系统页。
                            val pending = checks.firstOrNull { !it.ready }
                            if (pending != null) {
                                if (pending.key == KeepAliveUtils.KEY_NOTIFICATION &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                ) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    safeStart(pending.intent)
                                }
                            }
                        },
                    )
                    val notificationReady = checks.firstOrNull {
                        it.key == KeepAliveUtils.KEY_NOTIFICATION
                    }?.ready ?: true
                    if (!notificationReady) {
                        Text(
                            text = stringResource(R.string.float_keepalive_notification_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }

                    // ---- 强度三档 --------------------------------------------------------
                    SectionLabel(stringResource(R.string.float_keepalive_section_strength))
                    ModeCard(
                        selected = config.mode == KeepAlivePrefs.Mode.LOW,
                        title = stringResource(R.string.float_keepalive_mode_low),
                        desc = stringResource(R.string.float_keepalive_mode_low_desc),
                        battery = stringResource(R.string.float_keepalive_battery_low),
                    ) {
                        prefs.setMode(KeepAlivePrefs.Mode.LOW)
                        prefs.setSilenceMusic(false)
                        manager.applyMode(fromForeground = true)
                    }
                    ModeCard(
                        selected = config.mode == KeepAlivePrefs.Mode.SMART,
                        title = stringResource(R.string.float_keepalive_mode_smart),
                        desc = stringResource(R.string.float_keepalive_mode_smart_desc),
                        battery = stringResource(R.string.float_keepalive_battery_mid),
                    ) {
                        prefs.setMode(KeepAlivePrefs.Mode.SMART)
                        prefs.setSilenceMusic(false)
                        manager.applyMode(fromForeground = true)
                    }
                    ModeCard(
                        selected = config.mode == KeepAlivePrefs.Mode.PERMANENT,
                        title = stringResource(R.string.float_keepalive_mode_permanent),
                        desc = stringResource(R.string.float_keepalive_mode_permanent_desc),
                        battery = stringResource(R.string.float_keepalive_battery_high),
                    ) {
                        confirmPermanent = true
                    }
                    if (!batteryWhitelisted &&
                        (config.mode == KeepAlivePrefs.Mode.SMART ||
                            config.mode == KeepAlivePrefs.Mode.PERMANENT)
                    ) {
                        Text(
                            text = stringResource(R.string.float_keepalive_whitelist_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                    if (config.mode == KeepAlivePrefs.Mode.PERMANENT) {
                        SwitchRow(
                            title = stringResource(R.string.float_keepalive_music_toggle),
                            subtitle = stringResource(R.string.float_keepalive_music_media_note),
                            checked = config.silenceMusic,
                        ) { enabled ->
                            prefs.setSilenceMusic(enabled)
                            manager.applyMode(fromForeground = true)
                        }
                    }

                    // ---- 授权引导 --------------------------------------------------------
                    SectionLabel(stringResource(R.string.float_keepalive_section_auth))
                    val bootReady = checks.firstOrNull { it.key == KeepAliveUtils.KEY_BOOT }?.ready ?: true
                    SwitchRow(
                        title = stringResource(R.string.float_keepalive_auth_boot),
                        subtitle = stringResource(utils.autoStartHintRes()),
                        checked = config.bootEnabled && bootReady,
                    ) { enabled ->
                        prefs.setBootEnabled(enabled)
                        manager.applyMode(fromForeground = true)
                        if (enabled) safeStart(utils.autoStartIntent())
                    }
                    AuthRow(
                        title = stringResource(R.string.float_keepalive_auth_battery),
                        subtitle = stringResource(R.string.float_keepalive_auth_battery_hint),
                        ready = batteryWhitelisted,
                    ) {
                        // 部分 ROM 屏蔽直弹授权框：失败则降级到电池优化列表页。
                        val opened = if (batteryWhitelisted) {
                            safeStart(utils.batteryOptimizationSettings())
                        } else {
                            safeStart(utils.batteryOptimizationRequest()) ||
                                safeStart(utils.batteryOptimizationSettings())
                        }
                        if (!opened) refreshTick++
                    }
                    if (config.mode == KeepAlivePrefs.Mode.PERMANENT) {
                        val exactReady = remember(refreshTick) { utils.canScheduleExactAlarms() }
                        AuthRow(
                            title = stringResource(R.string.float_keepalive_auth_exact_alarm),
                            subtitle = stringResource(R.string.float_keepalive_auth_exact_alarm_hint),
                            ready = exactReady,
                        ) {
                            safeStart(utils.exactAlarmSettings())
                        }
                    }
                    SwitchRow(
                        title = stringResource(R.string.float_keepalive_auth_recents),
                        subtitle = stringResource(R.string.float_keepalive_auth_recents_hint),
                        checked = config.excludeRecents,
                    ) { enabled ->
                        prefs.setExcludeRecents(enabled)
                        manager.applyRecentsExclusion(enabled)
                    }

                    // ---- 底部诚实说明 -----------------------------------------------------
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.float_keepalive_footer_force_stop),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.float_keepalive_footer_vendor),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.float_settings_keepalive_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.float_keepalive_footer_notification),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (confirmPermanent) {
        AlertDialog(
            onDismissRequest = { confirmPermanent = false },
            title = { Text(stringResource(R.string.float_keepalive_confirm_permanent_title)) },
            text = { Text(stringResource(R.string.float_keepalive_confirm_permanent_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPermanent = false
                    prefs.setMode(KeepAlivePrefs.Mode.PERMANENT)
                    manager.applyMode(fromForeground = true)
                }) { Text(stringResource(R.string.float_keepalive_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanent = false }) {
                    Text(stringResource(R.string.float_keepalive_confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun HealthCard(checks: List<KeepAliveUtils.HealthCheck>, onFixAll: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val readyCount = checks.count { it.ready }
    val total = checks.size
    val pendingNames = checks.filter { !it.ready }.map { checkName(it.key) }
    val levelText = when {
        readyCount == total -> stringResource(R.string.float_keepalive_health_ok, readyCount, total)
        readyCount >= total - 2 -> stringResource(R.string.float_keepalive_health_fair, readyCount, total)
        else -> stringResource(R.string.float_keepalive_health_weak, readyCount, total)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = levelText, style = MaterialTheme.typography.titleSmall)
            if (pendingNames.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.float_keepalive_health_pending,
                        pendingNames.joinToString(" · "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (pendingNames.isNotEmpty()) {
                TextButton(onClick = onFixAll, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.float_keepalive_health_fix_all))
                }
            }
        }
    }
}

@Composable
private fun checkName(key: String): String = when (key) {
    KeepAliveUtils.KEY_NOTIFICATION -> stringResource(R.string.float_keepalive_check_notification)
    KeepAliveUtils.KEY_OVERLAY -> stringResource(R.string.float_keepalive_check_overlay)
    KeepAliveUtils.KEY_BOOT -> stringResource(R.string.float_keepalive_check_boot)
    KeepAliveUtils.KEY_BATTERY -> stringResource(R.string.float_keepalive_check_battery)
    KeepAliveUtils.KEY_HEARTBEAT -> stringResource(R.string.float_keepalive_check_heartbeat)
    KeepAliveUtils.KEY_EXACT_ALARM -> stringResource(R.string.float_keepalive_check_exact_alarm)
    else -> key
}

@Composable
private fun ModeCard(
    selected: Boolean,
    title: String,
    desc: String,
    battery: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) scheme.primary else scheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
        ),
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Text(
                text = battery,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun AuthRow(
    title: String,
    subtitle: String?,
    ready: Boolean,
    onAction: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        // 已就绪仅展示状态；待办时整卡可点直达系统页。
        onClick = onAction.takeUnless { ready } ?: ({}),
        enabled = !ready,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = if (ready) "✓" else "○",
                color = if (ready) scheme.primary else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 10.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(
                    if (ready) R.string.float_settings_granted
                    else R.string.float_settings_go_enable,
                ),
                color = if (ready) scheme.primary else scheme.tertiary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun Context.withLocale(locale: Locale): Context {
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}
