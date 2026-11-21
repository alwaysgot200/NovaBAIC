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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.verlintas.baic2.floatball.account.AccountManager
import com.verlintas.baic2.floatball.account.AccountPrefs
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * 好参谋社区（多产品方案 §4.8）：RPA 脚本市场 / 好参谋论坛。
 *
 * WebView 承载共享市场/论坛 SPA，固定带 `product=haocanmou&source=client`
 * （见 [CommunityUrls]）；已登录时用一次性 ticket 免登录进入，未登录可游客浏览。
 * 主题包装与 [com.verlintas.baic2.floatball.account.RechargeActivity] 同构。
 */
@AndroidEntryPoint
class CommunityActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var accountManager: AccountManager

    @Inject
    lateinit var accountApi: AccountApi

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
                val session by accountManager.session.collectAsStateWithLifecycle()
                CommunityScreen(
                    isForum = isForum,
                    session = session?.takeIf { it.expireAtMs > System.currentTimeMillis() },
                    accountApi = accountApi,
                    onBack = { finish() },
                )
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

/**
 * 社区 WebView 页。
 *
 * 已登录：先用原生会话申请一次性 ticket，打开 `/admin/sso#ticket=...` 免登录进入；
 * 未登录或取票失败：回退普通公开 URL，游客可浏览。ticket 只在 fragment 中，
 * Web 兑换一次后失效（多产品方案 §4.11）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CommunityScreen(
    isForum: Boolean,
    session: AccountPrefs.Session?,
    accountApi: AccountApi,
    onBack: () -> Unit,
) {
    val publicUrl = if (isForum) {
        CommunityUrls.forum(AccountApi.SERVER_ORIGIN)
    } else {
        CommunityUrls.market(AccountApi.SERVER_ORIGIN)
    }
    var url by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isForum, session?.accessToken) {
        val ticketUrl = session?.let { current ->
            runCatching {
                val ticket = accountApi.requestWebTicket(
                    current.accessToken,
                    CommunityUrls.target(isForum),
                )
                CommunityUrls.sso(AccountApi.SERVER_ORIGIN, ticket)
            }.getOrNull()
        }
        url = ticketUrl ?: publicUrl
    }

    AdvisorPage(
        title = stringResource(
            if (isForum) R.string.float_community_forum_title
            else R.string.float_community_market_title,
        ),
        onBack = onBack,
    ) { innerPadding ->
        val currentUrl = url
        if (currentUrl == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
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
                        loadUrl(currentUrl)
                    }
                },
                update = { webView ->
                    if (webView.url != currentUrl) webView.loadUrl(currentUrl)
                },
            )
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
