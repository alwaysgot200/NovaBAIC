/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.community

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.floatball.AdvisorPage
import com.verlintas.baic2.floatball.account.AccountApi
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * 好参谋社区（多产品方案 §4.8）：RPA 脚本市场 / 好参谋论坛。
 *
 * WebView 承载共享市场/论坛 SPA，固定带 `product=haocanmou&source=client`
 * （见 [CommunityUrls]）；游客可浏览，写操作在页面内登录完成（SSO 为后续阶段）。
 * 主题包装与 [com.verlintas.baic2.floatball.account.RechargeActivity] 同构。
 */
@AndroidEntryPoint
class CommunityActivity : ComponentActivity() {

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
        val isForum = intent.getStringExtra(EXTRA_PAGE) == PAGE_FORUM
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
                CommunityScreen(isForum = isForum, onBack = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_PAGE = "community_page"
        private const val PAGE_MARKET = "market"
        private const val PAGE_FORUM = "forum"

        /** RPA 脚本市场（本地 RPA 库入口）。 */
        fun marketIntent(context: Context): Intent =
            Intent(context, CommunityActivity::class.java).putExtra(EXTRA_PAGE, PAGE_MARKET)

        /** 好参谋论坛（本地 RPA 库入口）。 */
        fun forumIntent(context: Context): Intent =
            Intent(context, CommunityActivity::class.java).putExtra(EXTRA_PAGE, PAGE_FORUM)
    }
}

/** 社区 WebView 页：仅浏览共享 SPA，会话由页面自身管理。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CommunityScreen(isForum: Boolean, onBack: () -> Unit) {
    val url = if (isForum) {
        CommunityUrls.forum(AccountApi.SERVER_ORIGIN)
    } else {
        CommunityUrls.market(AccountApi.SERVER_ORIGIN)
    }
    AdvisorPage(
        title = stringResource(
            if (isForum) R.string.float_community_forum_title
            else R.string.float_community_market_title,
        ),
        onBack = onBack,
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                }
            },
        )
    }
}

private fun Context.withLocale(locale: Locale): Context {
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}
