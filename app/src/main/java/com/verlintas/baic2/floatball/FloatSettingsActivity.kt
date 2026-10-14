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
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
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
 * 老参谋设置页（扇形菜单「设置」子球入口）。只管老参谋专属项与权限
 * 直达；主题/模型等全局设置仍在应用主设置里。实时生效，无需重启。
 */
@AndroidEntryPoint
class FloatSettingsActivity : ComponentActivity() {

    @Inject
    lateinit var advisorPrefs: AdvisorPrefs

    @Inject
    lateinit var screenAdvisor: ScreenAdvisor

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
                AdvisorSettingsScreen(
                    prefs = advisorPrefs,
                    advisor = screenAdvisor,
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun AdvisorSettingsScreen(
    prefs: AdvisorPrefs,
    advisor: ScreenAdvisor,
    onClose: () -> Unit,
) {
    val settings by prefs.state.collectAsState()
    val a11yConnected by advisor.a11yConnected.collectAsState()
    var clearArmed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.float_settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onClose) {
                        Text(text = stringResource(R.string.float_ask_close))
                    }
                }

                SectionLabel(stringResource(R.string.float_settings_continuous))
                Text(
                    text = stringResource(R.string.float_settings_poll_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                ChoiceRow(
                    options = POLL_OPTIONS,
                    selected = settings.pollIntervalMs,
                    label = { res -> stringResource(res) },
                ) { prefs.setPollInterval(it) }

                Text(
                    text = stringResource(R.string.float_settings_session_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                ChoiceRow(
                    options = SESSION_OPTIONS,
                    selected = settings.maxSessionMs,
                    label = { res -> stringResource(res) },
                ) { prefs.setMaxSession(it) }

                SectionLabel(stringResource(R.string.float_settings_permissions))
                PermissionRow(
                    title = stringResource(R.string.float_settings_perm_a11y),
                    granted = a11yConnected,
                    actionLabel = stringResource(R.string.float_settings_go_enable),
                ) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
                PermissionRow(
                    title = stringResource(R.string.float_settings_perm_overlay),
                    granted = Settings.canDrawOverlays(context),
                    actionLabel = stringResource(R.string.float_settings_go_enable),
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.float_settings_perm_projection),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable {
                            context.startActivity(
                                Intent(
                                    context,
                                    com.verlintas.baic2.keepalive.KeepAliveSettingsActivity::class.java,
                                ),
                            )
                        },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.float_keepalive_entry),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.float_keepalive_entry_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                SectionLabel(stringResource(R.string.float_settings_session))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        context.startActivity(
                            Intent(context, com.verlintas.baic2.MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) {
                        Text(text = stringResource(R.string.float_settings_open_session))
                    }
                    OutlinedButton(onClick = { clearArmed = true }) {
                        Text(text = stringResource(R.string.float_settings_clear_session))
                    }
                }
                if (clearArmed) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.float_settings_clear_confirm),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.float_settings_clear_yes),
                            color = scheme.error,
                            modifier = Modifier
                                .clickable {
                                    clearArmed = false
                                    advisor.clearConversationAsync()
                                }
                                .padding(4.dp),
                        )
                        Text(
                            text = stringResource(R.string.float_settings_clear_no),
                            modifier = Modifier
                                .clickable { clearArmed = false }
                                .padding(4.dp),
                        )
                    }
                }

                SectionLabel(stringResource(R.string.float_about_title))
                Text(
                    text = stringResource(R.string.float_about_license) + "\n" +
                        stringResource(R.string.float_settings_keepalive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
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

private data class LabeledOption<T>(val value: T, val labelRes: Int)

private val POLL_OPTIONS = listOf(
    LabeledOption(2_000L, R.string.float_settings_poll_slow),
    LabeledOption(800L, R.string.float_settings_poll_normal),
    LabeledOption(500L, R.string.float_settings_poll_fast),
)

private val SESSION_OPTIONS = listOf(
    LabeledOption(15 * 60_000L, R.string.float_settings_session_15),
    LabeledOption(30 * 60_000L, R.string.float_settings_session_30),
    LabeledOption(60 * 60_000L, R.string.float_settings_session_60),
    LabeledOption(0L, R.string.float_settings_session_unlimited),
)

@Composable
private fun <T> ChoiceRow(
    options: List<LabeledOption<T>>,
    selected: T,
    label: @Composable (Int) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option.value == selected,
                onClick = { onSelect(option.value) },
                label = { Text(label(option.labelRes)) },
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (granted) {
                Text(
                    text = stringResource(R.string.float_settings_granted),
                    color = scheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = actionLabel,
                    color = scheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onAction),
                )
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
