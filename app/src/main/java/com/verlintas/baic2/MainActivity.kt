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

package com.verlintas.baic2

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.AppVisibility
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.floatball.FloatBallService
import com.verlintas.baic2.floatball.OverlayPermissionScreen
import com.verlintas.baic2.floatball.account.AccountManager
import com.verlintas.baic2.floatball.account.LoginScreen
import com.verlintas.baic2.keepalive.KeepAlivePrefs
import com.verlintas.baic2.keepalive.KeepAliveSetupGuideActivity
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/** FloatBallService「退出应用」时发给主界面：到达即整体退出。 */
const val EXTRA_EXIT = "com.verlintas.baic2.extra.EXIT"

/** FloatBallService「显示主界面」时带上：表示这次拉起来自悬浮球，不触发自动隐藏。 */
const val EXTRA_FROM_FLOAT = "com.verlintas.baic2.extra.FROM_FLOAT"

/** FloatBallService「设置」子球：打开主框架指定分区（目前仅设置页）。 */
const val EXTRA_OPEN_TAB = "com.verlintas.baic2.extra.OPEN_TAB"

/** [EXTRA_OPEN_TAB] 的取值：主框架「设置」分区。 */
const val TAB_SETTINGS = "settings"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** 首启保活引导旗标（弹一次即消费）。 */
    @Inject
    lateinit var keepAlivePrefs: KeepAlivePrefs

    /** 好参谋账号门（商业化 P0）：未登录只出登录页，登录后才进主框架。 */
    @Inject
    lateinit var accountManager: AccountManager

    override fun attachBaseContext(newBase: Context) {
        val language = AppLocaleStore.readFrom(newBase)
        val tag = language.languageTag
        super.attachBaseContext(
            if (tag == null) newBase else newBase.withLocale(Locale.forLanguageTag(tag)),
        )
    }

    private val pendingRunId = androidx.compose.runtime.mutableStateOf<Long?>(null)
    private val pendingConversationId = androidx.compose.runtime.mutableStateOf<Long?>(null)
    private val pendingTab = androidx.compose.runtime.mutableStateOf<String?>(null)

    // 冷启动时默认只留悬浮球：拿到悬浮窗权限后立刻退到后台。
    private var autoHideWhenReady = false

    // 装包登录门（用户口径「装完打开先登录」）：安装/升级后首启无条件先出登录页，
    // 登录成功消费后才走主界面/自动隐藏流程；旧会话不得跳过登录。
    private var installGatePending by mutableStateOf(false)
    private var packageUpdateTime = 0L

    private var overlayGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_EXIT, false) == true) {
            autoHideWhenReady = false
            finishAffinity()
            return
        }
        // 深链（open_run_id/open_conversation_id/open_tab）明确要打开界面，
        // 同样豁免自动隐藏——否则深链被拉起后立即压回后台，形同"点了没反应"。
        val deepLink = (intent?.getLongExtra(EXTRA_OPEN_RUN_ID, -1L) ?: -1L) >= 0L ||
            (intent?.getLongExtra(EXTRA_OPEN_CONVERSATION_ID, -1L) ?: -1L) >= 0L ||
            !intent?.getStringExtra(EXTRA_OPEN_TAB).isNullOrBlank()
        // 装包登录门：与包 lastUpdateTime 比对，装过/升级过后的首启强制先登录。
        packageUpdateTime = runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        // [upstream-touch] 测试免登录（自动化测试）：debug 测试构建允许
        // `am start --es debugToken <token>` 直接注入会话跳过登录框；
        // 只在全新启动消费（旋转恢复同一 intent 不重复生效），生产构建恒不放行。
        val debugLoggedIn = savedInstanceState == null &&
            accountManager.debugInjectFromIntent(this, intent)
        installGatePending = accountManager.installGatePending(packageUpdateTime)
        // 只有"正常启动"（图标冷启动）且已登录才自动隐藏；从悬浮球拉起的不隐藏，
        // 登录页可见期间（未登录或装包门待消费）绝不自动隐藏。
        autoHideWhenReady = intent?.getBooleanExtra(EXTRA_FROM_FLOAT, false) != true && !deepLink &&
            accountManager.isLoggedIn && !installGatePending && !debugLoggedIn
        // Rotation restores the same intent: only consume the deep link on a
        // fresh launch (or via onNewIntent), never twice.
        pendingRunId.value = if (savedInstanceState == null) {
            intent?.getLongExtra(EXTRA_OPEN_RUN_ID, -1L)?.takeIf { it >= 0L }
        } else {
            null
        }
        overlayGranted = Settings.canDrawOverlays(this)
        pendingConversationId.value = if (savedInstanceState == null) {
            intent?.getLongExtra(EXTRA_OPEN_CONVERSATION_ID, -1L)?.takeIf { it >= 0L }
        } else {
            null
        }
        pendingTab.value = if (savedInstanceState == null) {
            intent?.getStringExtra(EXTRA_OPEN_TAB)
        } else {
            null
        }
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
                val session by accountManager.session.collectAsStateWithLifecycle()
                when {
                    // 登录门（商业化 P0 + 装包登录门）：未登录、或装包/升级后首启未消费
                    // 装包门时只出登录页（旧会话不得跳过登录）；登录成功后停留主界面
                    // （autoHideWhenReady 已置 false，不压回后台）。
                    session == null || installGatePending -> LoginScreen(
                        accountManager = accountManager,
                        onLoggedIn = {
                            autoHideWhenReady = false
                            accountManager.consumeInstallGate(packageUpdateTime)
                            installGatePending = false
                            if (Settings.canDrawOverlays(this@MainActivity)) {
                                ContextCompat.startForegroundService(
                                    this@MainActivity,
                                    Intent(this@MainActivity, FloatBallService::class.java),
                                )
                                // 首启保活引导挪到登录之后：登录页之前不弹任何旁路界面。
                                KeepAliveSetupGuideActivity.maybeLaunch(
                                    this@MainActivity,
                                    keepAlivePrefs,
                                )
                            }
                        },
                        onExit = { moveTaskToBack(true) },
                    )
                    overlayGranted -> Baic2App(
                        initialRunId = pendingRunId.value,
                        onRunDeepLinkConsumed = { pendingRunId.value = null },
                        initialConversationId = pendingConversationId.value,
                        onConversationDeepLinkConsumed = { pendingConversationId.value = null },
                        initialTab = pendingTab.value,
                        onTabConsumed = { pendingTab.value = null },
                        accountName = session?.username?.takeIf { it.isNotBlank() },
                    )
                    else -> OverlayPermissionScreen(onGrant = { requestOverlayPermission() })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = Settings.canDrawOverlays(this)
        overlayGranted = granted
        // 登录门守卫：登录页（未登录或装包门待消费）可见期间不得自动隐藏、
        // 不弹旁路引导——首屏永远是登录页；登录成功后停留主界面。
        val loginRequired = !accountManager.isLoggedIn || installGatePending
        if (loginRequired) {
            autoHideWhenReady = false
        }
        if (granted && autoHideWhenReady && !loginRequired) {
            autoHideWhenReady = false
            ContextCompat.startForegroundService(this, Intent(this, FloatBallService::class.java))
            // 启动后只留悬浮球，主窗口退到后台。
            moveTaskToBack(true)
        }
        // 首次安装首次启动：弹一次保活授权引导（三项逐项去开启，旗标消费后不再弹）。
        // 登录门未过不弹——引导只出现在登录之后。
        if (granted && !loginRequired) {
            KeepAliveSetupGuideActivity.maybeLaunch(this, keepAlivePrefs)
        }
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(backgroundRunnable)
        AppVisibility.foreground = true
    }

    override fun onStop() {
        mainHandler.removeCallbacks(backgroundRunnable)
        // Rotations, the speech recognizer dialog and the screen-capture
        // consent screen all call onStop without the user leaving the app:
        // flip to "background" only after a short grace period.
        mainHandler.postDelayed(backgroundRunnable, BACKGROUND_GRACE_MS)
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(backgroundRunnable)
        // The grace period must not outlive the activity unhandled: a process
        // kept alive by a service would otherwise stay "foreground" forever.
        AppVisibility.foreground = false
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EXIT, false)) {
            finishAffinity()
            return
        }
        pendingRunId.value = intent.getLongExtra(EXTRA_OPEN_RUN_ID, -1L).takeIf { it >= 0L }
        pendingConversationId.value = intent.getLongExtra(EXTRA_OPEN_CONVERSATION_ID, -1L)
            .takeIf { it >= 0L }
        pendingTab.value = intent.getStringExtra(EXTRA_OPEN_TAB)
    }

    private fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val backgroundRunnable = Runnable { AppVisibility.foreground = false }

    private companion object {
        const val BACKGROUND_GRACE_MS = 4_000L
    }
}

private fun Context.withLocale(locale: Locale): Context {
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}

/** Mirrors RunService.EXTRA_OPEN_RUN_ID (device:impl cannot see app classes). */
private const val EXTRA_OPEN_RUN_ID = "open_run_id"

/** Mirrors RunService.EXTRA_OPEN_CONVERSATION_ID. */
private const val EXTRA_OPEN_CONVERSATION_ID = "open_conversation_id"
