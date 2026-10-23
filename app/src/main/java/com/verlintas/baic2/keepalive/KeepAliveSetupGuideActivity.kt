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

package com.verlintas.baic2.keepalive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * 首启保活引导卡片（2026-10-22）：初次安装后首个冷启动弹一次（旗标
 * [KeepAlivePrefs.firstRunGuideShown] 消费后不再打扰）。三项授权——自启动 /
 * 电池优化白名单 / 精确闹钟——就绪状态实时显示，「去开启」一键跳对应系统页
 * （从系统页返回即刷新状态），完成后点「完成」关闭。透明主题独立任务窗口
 * （taskAffinity 空 + excludeFromRecents，不设 noHistory——跳系统页后要能
 * 返回卡片继续引导），不牵动主任务栈、不进最近任务。
 */
@AndroidEntryPoint
class KeepAliveSetupGuideActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var utils: KeepAliveUtils

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
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            Baic2Theme(darkTheme = darkTheme, accent = accent) {
                GuideCard(
                    utils = utils,
                    onDone = { finish() },
                )
            }
        }
    }

    companion object {
        /**
         * 首次安装后首个启动弹一次：旗标弹出即消费，之后的启动不再打扰。
         * 调用方只需在拿到悬浮窗权限后调用一次。
         */
        fun maybeLaunch(activity: Activity, prefs: KeepAlivePrefs) {
            if (prefs.firstRunGuideShown) return
            prefs.firstRunGuideShown = true
            activity.startActivity(
                Intent(activity, KeepAliveSetupGuideActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun GuideCard(
    utils: KeepAliveUtils,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    // 从系统设置页返回时重算三项就绪状态。
    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun open(intent: Intent?): Boolean {
        if (intent == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = scheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.float_keepalive_guide_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.float_keepalive_guide_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )

                GuideRow(
                    title = stringResource(R.string.float_keepalive_auth_boot),
                    subtitle = stringResource(utils.autoStartHintRes()),
                    ready = remember(refreshTick) { utils.isBootReceiverEnabled() },
                    jumpFailed = stringResource(R.string.float_keepalive_guide_jump_failed),
                ) { open(utils.autoStartIntent()) }
                GuideRow(
                    title = stringResource(R.string.float_keepalive_auth_battery),
                    subtitle = stringResource(R.string.float_keepalive_auth_battery_hint),
                    ready = remember(refreshTick) { utils.isIgnoringBatteryOptimizations() },
                    jumpFailed = stringResource(R.string.float_keepalive_guide_jump_failed),
                ) {
                    // 部分 ROM 屏蔽直弹授权框：失败则降级到电池优化列表页。
                    if (utils.isIgnoringBatteryOptimizations()) {
                        open(utils.batteryOptimizationSettings())
                    } else {
                        open(utils.batteryOptimizationRequest()) ||
                            open(utils.batteryOptimizationSettings())
                    }
                }
                GuideRow(
                    title = stringResource(R.string.float_keepalive_auth_exact_alarm),
                    subtitle = stringResource(R.string.float_keepalive_auth_exact_alarm_hint),
                    ready = remember(refreshTick) { utils.canScheduleExactAlarms() },
                    jumpFailed = stringResource(R.string.float_keepalive_guide_jump_failed),
                ) { open(utils.exactAlarmSettings()) }

                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.float_keepalive_guide_done))
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    title: String,
    subtitle: String,
    ready: Boolean,
    jumpFailed: String,
    onJump: () -> Boolean,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (ready) {
                stringResource(R.string.float_keepalive_guide_ready)
            } else {
                stringResource(R.string.float_keepalive_guide_pending)
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (ready) scheme.primary else scheme.error,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        TextButton(
            onClick = {
                // 静默失效是大忌：跳不动要报因（用户偏好红线）。
                if (!onJump()) {
                    Toast.makeText(context, jumpFailed, Toast.LENGTH_SHORT).show()
                }
            },
        ) {
            Text(text = stringResource(R.string.float_keepalive_guide_jump))
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
