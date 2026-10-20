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
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
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
 * 使用帮助页（扇形菜单「帮助」子球入口）：完整使用说明书——软件定位、
 * 三步上手、悬浮球手势、单次/连续/自动三种参谋模式、画像与策略、参数
 * 速查、常见场景与隐私承诺。纯展示页，无任何业务状态；全屏骨架走
 * [AdvisorPage]，与主框架布局/风格保持一致。
 */
@AndroidEntryPoint
class AdvisorHelpActivity : ComponentActivity() {

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
                AdvisorHelpScreen(onClose = { finish() })
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
private fun AdvisorHelpScreen(onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        AdvisorPage(
            title = stringResource(R.string.float_help_title),
            onBack = onClose,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                ) {
                    // ---- 定位 ------------------------------------------------
                    HelpSection(stringResource(R.string.float_help_intro_section))
                    HelpBody(stringResource(R.string.float_help_intro_body))
                    HelpBullet(stringResource(R.string.float_help_intro_point_local))
                    HelpBullet(stringResource(R.string.float_help_intro_point_advice))

                    // ---- 三步上手 --------------------------------------------
                    HelpSection(stringResource(R.string.float_help_quick_section))
                    HelpBody(stringResource(R.string.float_help_quick_body))
                    HelpStep(1, stringResource(R.string.float_help_quick_step1))
                    HelpStep(2, stringResource(R.string.float_help_quick_step2))
                    HelpStep(3, stringResource(R.string.float_help_quick_step3))
                    HelpFlow(
                        steps = listOf(
                            stringResource(R.string.float_help_quick_step1).substringBefore("，"),
                            stringResource(R.string.float_help_quick_step2).substringBefore("，"),
                            stringResource(R.string.float_help_quick_step3).substringBefore("，"),
                        ),
                    )

                    // ---- 悬浮球手势 ------------------------------------------
                    HelpSection(stringResource(R.string.float_help_ball_section))
                    HelpBody(stringResource(R.string.float_help_ball_body))
                    HelpTermValue(
                        term = stringResource(R.string.float_help_gesture_tap)
                            .substringBefore("："),
                        value = stringResource(R.string.float_help_gesture_tap)
                            .substringAfter("："),
                    )
                    HelpTermValue(
                        term = stringResource(R.string.float_help_gesture_long)
                            .substringBefore("："),
                        value = stringResource(R.string.float_help_gesture_long)
                            .substringAfter("："),
                    )
                    HelpTermValue(
                        term = stringResource(R.string.float_help_gesture_double)
                            .substringBefore("："),
                        value = stringResource(R.string.float_help_gesture_double)
                            .substringAfter("："),
                    )
                    HelpTermValue(
                        term = stringResource(R.string.float_help_gesture_drag)
                            .substringBefore("："),
                        value = stringResource(R.string.float_help_gesture_drag)
                            .substringAfter("："),
                    )

                    // ---- 单次参谋 --------------------------------------------
                    HelpSection(stringResource(R.string.float_help_oneshot_section))
                    HelpBody(stringResource(R.string.float_help_oneshot_body))
                    HelpFlow(
                        steps = listOf(
                            "截屏",
                            "识别文字",
                            "召回记忆",
                            "AI 分析",
                            "分屏出答案",
                        ),
                    )
                    HelpBullet(stringResource(R.string.float_help_oneshot_split))
                    HelpBullet(stringResource(R.string.float_help_oneshot_input))

                    // ---- 连续参谋 --------------------------------------------
                    HelpSection(stringResource(R.string.float_help_continuous_section))
                    HelpBody(stringResource(R.string.float_help_continuous_body))
                    HelpBody(stringResource(R.string.float_help_continuous_end))

                    // ---- 自动参谋 --------------------------------------------
                    HelpSection(stringResource(R.string.float_help_auto_section))
                    HelpBody(stringResource(R.string.float_help_auto_body))
                    HelpBullet(stringResource(R.string.float_help_auto_safe))
                    HelpBullet(stringResource(R.string.float_help_auto_whitelist))

                    // ---- 画像与策略 ------------------------------------------
                    HelpSection(stringResource(R.string.float_help_profile_section))
                    HelpBody(stringResource(R.string.float_help_profile_body))

                    // ---- 参数速查 --------------------------------------------
                    HelpSection(stringResource(R.string.float_help_params_section))
                    HelpBody(stringResource(R.string.float_help_params_body))
                    HelpTermValue(
                        term = stringResource(R.string.float_help_param_poll).substringBefore("："),
                        value = stringResource(R.string.float_help_param_poll).substringAfter("："),
                    )
                    HelpTermValue(
                        term = stringResource(R.string.float_help_param_backoff)
                            .substringBefore("："),
                        value = stringResource(R.string.float_help_param_backoff)
                            .substringAfter("："),
                    )
                    HelpTermValue(
                        term = stringResource(R.string.float_help_param_session)
                            .substringBefore("："),
                        value = stringResource(R.string.float_help_param_session)
                            .substringAfter("："),
                    )
                    HelpTermValue(
                        term = stringResource(R.string.float_help_param_budget)
                            .substringBefore("："),
                        value = stringResource(R.string.float_help_param_budget)
                            .substringAfter("："),
                    )
                    HelpTermValue(
                        term = stringResource(R.string.float_help_param_keepalive)
                            .substringBefore("："),
                        value = stringResource(R.string.float_help_param_keepalive)
                            .substringAfter("："),
                    )
                    HelpTermValue(
                        term = stringResource(R.string.float_help_param_auto_limit)
                            .substringBefore("："),
                        value = stringResource(R.string.float_help_param_auto_limit)
                            .substringAfter("："),
                    )

                    // ---- 场景举例 --------------------------------------------
                    HelpSection(stringResource(R.string.float_help_scene_section))
                    HelpSceneCard(
                        title = stringResource(R.string.float_help_scene1_title),
                        body = stringResource(R.string.float_help_scene1_body),
                    )
                    HelpSceneCard(
                        title = stringResource(R.string.float_help_scene2_title),
                        body = stringResource(R.string.float_help_scene2_body),
                    )
                    HelpSceneCard(
                        title = stringResource(R.string.float_help_scene3_title),
                        body = stringResource(R.string.float_help_scene3_body),
                    )

                    // ---- 权限 ------------------------------------------------
                    HelpSection(stringResource(R.string.float_help_perm_section))
                    HelpBullet(stringResource(R.string.float_help_perm_overlay))
                    HelpBullet(stringResource(R.string.float_help_perm_a11y))
                    HelpBullet(stringResource(R.string.float_help_perm_notification))
                    HelpBullet(stringResource(R.string.float_help_perm_battery))

                    // ---- 隐私 ------------------------------------------------
                    HelpSection(stringResource(R.string.float_help_privacy_section))
                    HelpBody(stringResource(R.string.float_help_privacy_body))
                }
            }
        }
    }
}

@Composable
private fun HelpSection(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun HelpBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** 圆点要点行。 */
@Composable
private fun HelpBullet(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 9.dp, end = 10.dp)
                .size(5.dp)
                .background(scheme.primary, CircleShape),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
        )
    }
}

/** 术语行：加粗术语 + 解释正文（同一 Text 内自然换行）。 */
@Composable
private fun HelpTermValue(term: String, value: String) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(term) }
            append(value)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** 编号步骤行（上手流程用）。 */
@Composable
private fun HelpStep(number: Int, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(22.dp)
                .background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onPrimaryContainer,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 流程图：步骤 chip + 箭头，自动换行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelpFlow(steps: List<String>) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEachIndexed { index, step ->
            if (index > 0) {
                Text(
                    text = "→",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
            Text(
                text = step,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSecondaryContainer,
                modifier = Modifier
                    .background(scheme.secondaryContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** 场景举例卡。 */
@Composable
private fun HelpSceneCard(title: String, body: String) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(scheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
