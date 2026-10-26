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

package com.verlintas.baic2.floatball.shizuku

import android.content.Intent
import android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.R
import com.verlintas.baic2.device.api.ShellState
import com.verlintas.baic2.designsystem.Baic2Spacing
import com.verlintas.baic2.floatball.AdvisorPage
import kotlinx.coroutines.launch

/**
 * 投影免弹窗授权引导页（docs/2026-10-21 方案 §5.2）：三态驱动——未装内置安装
 * （docs/2026-10-26-内置安装Shizuku方案.md）→ 未运行引导无线调试配对（含两条
 * 一次性前置）→ 运行中一键执行 `appops set PROJECT_MEDIA allow` → 绿色已生效。
 * 失败展示 shell 原文；从系统安装器返回时按 ON_RESUME 自动刷新安装态。
 */
@Composable
internal fun ShizukuGrantScreen(
    gate: ShizukuGate,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val shellState by gate.shellState.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(true) }
    var installedVersion by remember { mutableStateOf<ShizukuVersion?>(null) }
    var bundledVersion by remember { mutableStateOf<ShizukuVersion?>(null) }
    var installing by remember { mutableStateOf(false) }
    var installNote by remember { mutableStateOf<InstallNote?>(null) }
    var appopGranted by remember { mutableStateOf(false) }
    var executing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var justGranted by remember { mutableStateOf(false) }

    fun refreshInstalled() {
        installed = gate.isManagerInstalled()
        installedVersion = gate.installedVersion()
    }

    fun runInstall() {
        if (installing) return
        installing = true
        installNote = null
        scope.launch {
            installNote = when (val outcome = gate.installBundled()) {
                is ShizukuInstallOutcome.Started -> InstallNote(
                    R.string.float_shizuku_install_started,
                    arg = outcome.versionName,
                )
                is ShizukuInstallOutcome.PermissionRequired ->
                    InstallNote(R.string.float_shizuku_install_permission_hint)
                is ShizukuInstallOutcome.AlreadyInstalled -> InstallNote(
                    R.string.float_shizuku_install_already,
                    arg = bundledVersion?.name,
                )
                is ShizukuInstallOutcome.Failed -> InstallNote(
                    when (outcome.reason) {
                        ShizukuInstallOutcome.Failure.ASSET_CORRUPT ->
                            R.string.float_shizuku_install_corrupt
                        else -> R.string.float_shizuku_install_failed
                    },
                    isError = true,
                )
            }
            installing = false
            refreshInstalled()
        }
    }

    LaunchedEffect(Unit) {
        refreshInstalled()
        bundledVersion = gate.bundledVersion()
    }
    // 从系统安装器/设置页返回即重查：装成功后按钮自动切到「打开 Shizuku」。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshInstalled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // shell 状态变化（如用户批准权限弹窗）或执行结束后复查 appop 落地状态。
    LaunchedEffect(shellState, executing) {
        if (!executing) appopGranted = gate.projectionSilentGranted()
    }

    val upgradeRequired = installedVersion?.let { current ->
        bundledVersion?.let { bundled -> current.code < bundled.code }
    } == true

    AdvisorPage(
        title = stringResource(R.string.float_shizuku_title),
        onBack = onClose,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = Baic2Spacing.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Baic2Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.float_shizuku_intro),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            // ---- 状态卡：三态 + 主操作 ----
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (appopGranted) scheme.primaryContainer else scheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(Baic2Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
                ) {
                    Text(
                        text = stringResource(
                            if (appopGranted) R.string.float_shizuku_state_granted
                            else R.string.float_shizuku_state_pending,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (appopGranted) scheme.onPrimaryContainer else scheme.onSurface,
                    )
                    if (appopGranted) {
                        Text(
                            text = stringResource(R.string.float_shizuku_state_granted_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onPrimaryContainer,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (executing || installing) {
                            Text(
                                text = stringResource(
                                    if (installing) {
                                        R.string.float_shizuku_install_preparing
                                    } else {
                                        R.string.float_shizuku_executing
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        } else {
                            MainAction(
                                installed = installed,
                                upgradeRequired = upgradeRequired,
                                shellState = shellState,
                                appopGranted = appopGranted,
                                onInstall = { runInstall() },
                                onLaunch = { gate.launchManager() },
                                onPermission = { gate.requestShellPermission() },
                                onGrant = {
                                    executing = true
                                    justGranted = false
                                    scope.launch {
                                        when (val r = gate.grantProjectionSilent()) {
                                            is ShizukuGrantResult.Granted -> {
                                                error = null
                                                justGranted = true
                                            }
                                            is ShizukuGrantResult.Failed -> {
                                                justGranted = false
                                                error = r.reason
                                            }
                                        }
                                        executing = false
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (justGranted) {
                Text(
                    text = stringResource(R.string.float_shizuku_grant_ok),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.primary,
                )
            }

            error?.let { reason ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(Baic2Spacing.md)) {
                        Text(
                            text = stringResource(R.string.float_shizuku_error_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(Baic2Spacing.xs))
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onErrorContainer,
                        )
                    }
                }
            }

            // ---- 第一步：安装 Shizuku（内置安装包；未装/可升级时就地给出按钮）----
            SectionCard(stringResource(R.string.float_shizuku_install_title)) {
                StepItem(stringResource(R.string.float_shizuku_install_hint))
                if (!installed || upgradeRequired) {
                    TextButton(onClick = { runInstall() }, enabled = !installing) {
                        Text(
                            stringResource(
                                if (installed) {
                                    R.string.float_shizuku_action_upgrade
                                } else {
                                    R.string.float_shizuku_action_install
                                },
                            ),
                        )
                    }
                }
                installedVersion?.let { version ->
                    Text(
                        text = stringResource(
                            R.string.float_shizuku_install_installed,
                            version.name ?: version.code.toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                bundledVersion?.let { version ->
                    Text(
                        text = stringResource(
                            R.string.float_shizuku_install_bundled,
                            version.name ?: version.code.toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                installNote?.let { note ->
                    Text(
                        text = note.arg?.let { stringResource(note.res, it) } ?: stringResource(note.res),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (note.isError) scheme.error else scheme.primary,
                    )
                }
            }

            // ---- 前置（一次性，决定成败）----
            SectionCard(stringResource(R.string.float_shizuku_prereq_title)) {
                StepItem(stringResource(R.string.float_shizuku_prereq_dev)) {
                    runCatching {
                        context.startActivity(Intent(ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }
                }
                StepItem(stringResource(R.string.float_shizuku_prereq_notif)) {
                    openNotificationStyleSettings(context)
                }
            }

            // ---- 配对三步 ----
            SectionCard(stringResource(R.string.float_shizuku_pair_title)) {
                StepItem(stringResource(R.string.float_shizuku_pair_1)) {
                    gate.launchManager()
                }
                StepItem(stringResource(R.string.float_shizuku_pair_2))
                StepItem(stringResource(R.string.float_shizuku_pair_3))
            }

            Spacer(Modifier.height(Baic2Spacing.lg))
        }
    }
}

@Composable
private fun MainAction(
    installed: Boolean,
    upgradeRequired: Boolean,
    shellState: ShellState,
    appopGranted: Boolean,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onPermission: () -> Unit,
    onGrant: () -> Unit,
) {
    when {
        !installed -> Button(onClick = onInstall) {
            Text(stringResource(R.string.float_shizuku_action_install))
        }
        upgradeRequired -> OutlinedButton(onClick = onInstall) {
            Text(stringResource(R.string.float_shizuku_action_upgrade))
        }
        shellState is ShellState.Unavailable -> Button(onClick = onLaunch) {
            Text(stringResource(R.string.float_shizuku_action_launch))
        }
        shellState is ShellState.PermissionRequired -> Button(onClick = onPermission) {
            Text(stringResource(R.string.float_shizuku_action_permission))
        }
        appopGranted -> OutlinedButton(onClick = onGrant) {
            Text(stringResource(R.string.float_shizuku_action_regrant))
        }
        else -> Button(onClick = onGrant) {
            Text(stringResource(R.string.float_shizuku_action_grant))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Baic2Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
            )
            content()
        }
    }
}

/** 步骤行：数字圆点 + 文案 + 可选动作按钮（[onAction] 为 null 时隐藏）。 */
@Composable
private fun StepItem(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel ?: stringResource(R.string.float_shizuku_go_settings))
            }
        }
    }
}

/** 安装结果提示：[res] 为文案资源，[arg] 为可选的版本名，[isError] 决定展示色。 */
private data class InstallNote(
    val res: Int,
    val arg: String? = null,
    val isError: Boolean = false,
)

/**
 * 逐级降级打开通知样式设置（配对弹窗需要系统原生通知通道才显示 6 位配对码）。
 * 按厂商排优先级：小米/HyperOS 直达「通知样式设置」，其余 ROM 的样式开关
 * 都收在自家通知主页里，落到标准「通知」页即是可用的最深入口。
 * action 不存在时 startActivity 抛 ActivityNotFoundException，逐级捕获。
 */
private fun openNotificationStyleSettings(context: android.content.Context) {
    val chain = when (android.os.Build.MANUFACTURER?.lowercase()) {
        "xiaomi", "redmi" -> listOf(                                        // HyperOS/MIUI
            "miui.settings.NOTIFICATION_DISPLAY_SETTINGS",                 // 通知样式设置（真机实测）
            "android.settings.NOTIFICATION_SETTINGS",                      // 通知页兜底
        )
        "honor", "huawei" -> listOf(                                        // MagicOS/EMUI 通知和状态栏
            "android.settings.NOTIFICATION_AND_STATUS_BAR_SETTINGS",
            "android.settings.NOTIFICATION_SETTINGS",
        )
        "oppo", "realme", "oneplus" -> listOf(                              // ColorOS 通知与状态栏
            "android.settings.NOTIFICATION_AND_STATUS_BAR_SETTINGS",
            "com.coloros.settings.notification.NOTIFICATION_SETTINGS",
            "android.settings.NOTIFICATION_SETTINGS",
        )
        "vivo", "iqoo" -> listOf(                                           // OriginOS 通知与状态栏
            "android.settings.NOTIFICATION_AND_STATUS_BAR_SETTINGS",
            "com.vivo.settings.notification.SETTINGS",
            "android.settings.NOTIFICATION_SETTINGS",
        )
        "samsung" -> listOf(                                                // OneUI 通知（样式在各 App 通知设置内）
            "android.settings.NOTIFICATION_SETTINGS",
        )
        else -> listOf(
            "android.settings.NOTIFICATION_STYLE_SETTINGS",                  // 未知 ROM 先试样式页
            "android.settings.NOTIFICATION_AND_STATUS_BAR_SETTINGS",
            "android.settings.NOTIFICATION_SETTINGS",
        )
    }
    for (action in chain) {
        val ok = runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return
    }
}
