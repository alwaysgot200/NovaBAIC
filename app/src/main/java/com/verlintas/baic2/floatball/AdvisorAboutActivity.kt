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

package com.verlintas.baic2.floatball

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.BuildConfig
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.feature.settings.AboutPage
import com.verlintas.baic2.feature.settings.BuildInfo
import com.verlintas.baic2.feature.settings.LicensesPage
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * 关于页（「更多」弹窗末项入口）：直接复用主框架设置的 [AboutPage]/[LicensesPage]，
 * 与主界面「设置 → 关于」同一内容与观感（品牌卡/版本更新/数据统计/分享/版权）。
 * 纯展示页，无业务状态；开源许可在本页内二级切换，返回先回关于页再退页。
 */
@AndroidEntryPoint
class AdvisorAboutActivity : ComponentActivity() {

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
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            Baic2Theme(darkTheme = darkTheme, accent = accent) {
                AdvisorAboutScreen(onClose = { finish() })
            }
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

@Composable
private fun AdvisorAboutScreen(onClose: () -> Unit) {
    var showLicenses by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (showLicenses) {
            LicensesPage(onBack = { showLicenses = false })
        } else {
            AboutPage(
                buildInfo = BuildInfo(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    applicationId = BuildConfig.APPLICATION_ID,
                    buildType = BuildConfig.BUILD_TYPE,
                ),
                onBack = onClose,
                onOpenLicenses = { showLicenses = true },
            )
        }
    }
}
