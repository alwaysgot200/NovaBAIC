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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.EXTRA_FROM_FLOAT
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.advisor.StrategyCardRepository
import com.verlintas.baic2.floatball.advisor.data.CARD_CONFIRMED
import com.verlintas.baic2.floatball.advisor.data.CARD_DRAFT
import com.verlintas.baic2.floatball.advisor.data.ContactProfileEntity
import com.verlintas.baic2.floatball.advisor.strategy.StrategyRuntime
import com.verlintas.baic2.floatball.auto.InjectorSelector
import com.verlintas.baic2.floatball.auto.ReadinessGate
import com.verlintas.baic2.floatball.auto.ReadinessReport
import com.verlintas.baic2.floatball.auto.ScreenCapture
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/** 「在主界面打开」用的会话深链键（mirror MainActivity/RunService 的私有常量）。 */
private const val EXTRA_OPEN_CONVERSATION_ID = "open_conversation_id"

/**
 * 老参谋设置页。入口在主框架设置页的「老参谋」分区（设置入口全软件唯一）。
 * 只管老参谋专属项与权限直达；主题/模型等全局设置仍在应用主设置里。
 * 实时生效，无需重启。
 */
@AndroidEntryPoint
class FloatSettingsActivity : ComponentActivity() {

    @Inject
    lateinit var advisorPrefs: AdvisorPrefs

    @Inject
    lateinit var screenAdvisor: ScreenAdvisor

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var balanceRepository: DeepSeekBalanceRepository

    @Inject
    lateinit var strategyRuntime: StrategyRuntime

    @Inject
    lateinit var strategyCards: StrategyCardRepository

    @Inject
    lateinit var proactiveCheckpoint: com.verlintas.baic2.floatball.director.ProactiveCheckpoint

    /** M3-b 自动档白名单管理：联系人档案数据源（仅读，写入走 AdvisorPrefs）。 */
    @Inject
    lateinit var contactRepository: ContactRepository

    /** R1 模式 B 就绪校验门（§20.1 八项探针，执行通道选择时触发）。 */
    @Inject
    lateinit var readinessGate: com.verlintas.baic2.floatball.auto.ReadinessGate

    /** 投影免弹窗授权（Shizuku + appops，docs/2026-10-21 方案）。 */
    @Inject
    lateinit var shizukuGate: com.verlintas.baic2.floatball.shizuku.ShizukuGate

    /** 投影授权编排（方案 2026-10-22 模块 A：a11y 自动点/Shizuku 静默/手动兜底）。 */
    @Inject
    lateinit var screenCapture: ScreenCapture

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
                    balanceRepository = balanceRepository,
                    runtime = strategyRuntime,
                    strategyCards = strategyCards,
                    proactiveCheckpoint = proactiveCheckpoint,
                    contactRepository = contactRepository,
                    readinessGate = readinessGate,
                    shizukuGate = shizukuGate,
                    screenCapture = screenCapture,
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
    balanceRepository: DeepSeekBalanceRepository,
    runtime: StrategyRuntime,
    strategyCards: StrategyCardRepository,
    proactiveCheckpoint: com.verlintas.baic2.floatball.director.ProactiveCheckpoint,
    contactRepository: ContactRepository,
    readinessGate: ReadinessGate,
    shizukuGate: com.verlintas.baic2.floatball.shizuku.ShizukuGate,
    screenCapture: ScreenCapture,
    onClose: () -> Unit,
) {
    val settings by prefs.state.collectAsState()
    var clearArmed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        AdvisorPage(
            title = stringResource(R.string.float_settings_title),
            onBack = onClose,
        ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
            ) {
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
                    text = stringResource(R.string.float_settings_backoff_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                ChoiceRow(
                    options = BACKOFF_OPTIONS,
                    selected = settings.idleBackoffMultiplier,
                    label = { res -> stringResource(res) },
                ) { prefs.setIdleBackoffMultiplier(it) }

                Text(
                    text = stringResource(R.string.float_settings_session_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                ChoiceRow(
                    options = SESSION_OPTIONS,
                    selected = settings.maxSessionMs,
                    label = { res -> stringResource(res) },
                ) { prefs.setMaxSession(it) }

                SectionLabel(stringResource(R.string.float_settings_background))
                ToggleRow(
                    title = stringResource(R.string.float_settings_digest),
                    hint = stringResource(R.string.float_settings_digest_hint),
                    checked = settings.digestEnabled,
                ) { prefs.setDigestEnabled(it) }
                FrequencyRow(
                    values = DIGEST_FREQUENCY,
                    selected = settings.digestEveryTurns,
                    enabled = settings.digestEnabled,
                ) { prefs.setDigestEveryTurns(it) }
                ToggleRow(
                    title = stringResource(R.string.float_settings_extract),
                    hint = stringResource(R.string.float_settings_extract_hint),
                    checked = settings.extractEnabled,
                ) { prefs.setExtractEnabled(it) }
                FrequencyRow(
                    values = EXTRACT_FREQUENCY,
                    selected = settings.extractEveryTurns,
                    enabled = settings.extractEnabled,
                ) { prefs.setExtractEveryTurns(it) }

                SectionLabel(stringResource(R.string.float_settings_tokens))
                Text(
                    text = stringResource(R.string.float_settings_memory_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                ChoiceRow(
                    options = MEMORY_MODE_OPTIONS,
                    selected = settings.memoryMode,
                    label = { res -> stringResource(res) },
                ) { prefs.setMemoryMode(it) }

                Text(
                    text = stringResource(R.string.float_settings_answer_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                ChoiceRow(
                    options = ANSWER_LENGTH_OPTIONS,
                    selected = settings.answerLength,
                    label = { res -> stringResource(res) },
                ) { prefs.setAnswerLength(it) }

                Text(
                    text = stringResource(R.string.float_settings_dedup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                ChoiceRow(
                    options = DEDUP_LEVEL_OPTIONS,
                    selected = settings.dedupLevel,
                    label = { res -> stringResource(res) },
                ) { prefs.setDedupLevel(it) }

                Text(
                    text = stringResource(R.string.float_settings_budget_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                ChoiceRow(
                    options = TOKEN_BUDGET_OPTIONS,
                    selected = settings.sessionTokenBudget,
                    label = { res -> stringResource(res) },
                ) { prefs.setSessionTokenBudget(it) }

                TokenUsageCard(prefs = prefs, balanceRepository = balanceRepository)

                // 权限/授权类条目已统一收敛到主框架「设置 → 权限」页（方案见
                // docs/2026-10-22-设置权限统一合并方案.md）：无障碍/悬浮窗/投影
                // 免弹窗/通知监听/保活均在主权限页设置，此处不再重复。

                // M2-a 起草档：总开关 + 白名单说明。
                SectionLabel(stringResource(R.string.float_settings_draft_section))
                ToggleRow(
                    title = stringResource(R.string.float_settings_draft),
                    hint = stringResource(R.string.float_settings_draft_hint),
                    checked = settings.draftEnabled,
                    onChange = { prefs.setDraftEnabled(it) },
                )
                Text(
                    text = stringResource(R.string.float_settings_draft_packages_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                // M2-b 主动保温：总闸 + 检点间隔（联系人级参数在画像页目标 Tab）。
                SectionLabel(stringResource(R.string.float_settings_proactive_section))
                ToggleRow(
                    title = stringResource(R.string.float_settings_proactive),
                    hint = stringResource(R.string.float_settings_proactive_hint),
                    checked = settings.proactiveEnabled,
                    onChange = { on ->
                        prefs.setProactiveEnabled(on)
                        if (on) {
                            proactiveCheckpoint.ensureScheduled()
                        } else {
                            proactiveCheckpoint.cancel()
                        }
                    },
                )
                var intervalDialog by remember { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { intervalDialog = true },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.float_settings_proactive_interval),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(
                                R.string.float_proactive_gap_value,
                                settings.proactiveIntervalHours,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.primary,
                        )
                    }
                }
                if (intervalDialog) {
                    val intervalOptions = AdvisorPrefs.PROACTIVE_INTERVAL_OPTIONS.sorted()
                    AlertDialog(
                        onDismissRequest = { intervalDialog = false },
                        title = {
                            Text(stringResource(R.string.float_settings_proactive_interval))
                        },
                        text = {
                            Column {
                                intervalOptions.forEach { hours ->
                                    Text(
                                        text = stringResource(
                                            R.string.float_proactive_gap_value,
                                            hours,
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (hours == settings.proactiveIntervalHours) {
                                            scheme.primary
                                        } else {
                                            scheme.onSurface
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                prefs.setProactiveIntervalHours(hours)
                                                intervalDialog = false
                                            }
                                            .padding(vertical = 10.dp),
                                    )
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { intervalDialog = false }) {
                                Text(stringResource(R.string.float_profile_cancel))
                            }
                        },
                    )
                }
                // M3-b 自动参谋：总闸（风险告知+二次确认）+ 白名单管理 + 单窗口上限。
                AutoAdvisorSection(
                    settings = settings,
                    prefs = prefs,
                    contactRepository = contactRepository,
                )
                // R1 §17.2/§20：三模执行通道选择 + 模式 B 八项就绪门红绿表。
                AutoExecutorSection(
                    settings = settings,
                    prefs = prefs,
                    readinessGate = readinessGate,
                    screenCapture = screenCapture,
                )
                // 2026-10-20 通报方案：H 接管/G 达成铃声通报 + SMTP 邮件配置。
                EscalationSection(
                    settings = settings,
                    prefs = prefs,
                )
                // 保活入口卡已迁主框架「设置 → 权限」页（统一一处）。

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable {
                            context.startActivity(
                                Intent(context, StyleCardActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.float_settings_style),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.float_settings_style_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                StrategySettingsSection(
                    mode = settings.strategyMode,
                    onModeChange = { prefs.setStrategyMode(it) },
                    runtime = runtime,
                    strategyCards = strategyCards,
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable {
                            context.startActivity(
                                Intent(context, AdvisorProfileActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.float_settings_profiles),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.float_settings_profiles_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable {
                            context.startActivity(
                                Intent(context, AdvisorPlaybookActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.float_settings_playbooks),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.float_settings_playbooks_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                SectionLabel(stringResource(R.string.float_settings_session))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val intent = Intent(context, com.verlintas.baic2.MainActivity::class.java)
                            // REORDER_TO_FRONT 把 task 中的主界面实例移到栈顶（设置页
                            // 压在其下，back 可返回）；否则 NEW_TASK 只置前 task，
                            // 栈顶仍是本页，视觉上「点了没反应」。
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                            )
                        // 悬浮球拉起语义：绕过 MainActivity 的冷启动自动隐藏。
                        intent.putExtra(EXTRA_FROM_FLOAT, true)
                        val convId = prefs.conversationId()
                        if (convId > 0L) {
                            intent.putExtra(EXTRA_OPEN_CONVERSATION_ID, convId)
                        }
                        context.startActivity(intent)
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
            }
        }
        }
    }
}
/** P2「我的聊天策略」：模式三档（增强档置灰）+ 默认策略卡入口与手动编译。 */
@Composable
private fun StrategySettingsSection(
    mode: Int,
    onModeChange: (Int) -> Unit,
    runtime: StrategyRuntime,
    strategyCards: StrategyCardRepository,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val globalCard by strategyCards.observeLatest(StrategyCardRepository.GLOBAL_CONTACT_ID)
        .collectAsStateWithLifecycle(initialValue = null)

    SectionLabel(stringResource(R.string.float_settings_strategy_section))
    Text(
        text = stringResource(R.string.float_strategy_mode_hint),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        FilterChip(
            selected = mode == AdvisorPrefs.STRATEGY_OFF,
            onClick = { onModeChange(AdvisorPrefs.STRATEGY_OFF) },
            label = { Text(stringResource(R.string.float_strategy_mode_off)) },
        )
        FilterChip(
            selected = mode == AdvisorPrefs.STRATEGY_SMART,
            onClick = { onModeChange(AdvisorPrefs.STRATEGY_SMART) },
            label = { Text(stringResource(R.string.float_strategy_mode_smart)) },
        )
        FilterChip(
            selected = mode == AdvisorPrefs.STRATEGY_ENHANCED,
            onClick = { onModeChange(AdvisorPrefs.STRATEGY_ENHANCED) },
            label = { Text(stringResource(R.string.float_strategy_mode_enhanced)) },
        )
    }
    // P3 §七：增强档已解锁，明示额外成本与效果。
    if (mode == AdvisorPrefs.STRATEGY_ENHANCED) {
        Text(
            text = stringResource(R.string.float_strategy_mode_enhanced_hint),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    val card = globalCard
    val cardStatus = when (card?.status) {
        CARD_DRAFT -> stringResource(R.string.float_goal_card_draft, card.version)
        CARD_CONFIRMED -> stringResource(R.string.float_goal_card_confirmed, card.version)
        else -> stringResource(R.string.float_goal_card_none)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable {
                context.startActivity(
                    Intent(context, StrategyCardActivity::class.java)
                        .putExtra(
                            StrategyCardActivity.EXTRA_CONTACT_ID,
                            StrategyCardActivity.GLOBAL_CONTACT_ID,
                        )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.float_strategy_default_card),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.float_strategy_default_card_hint) + " · " + cardStatus,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    runtime.requestCardCompile(StrategyCardRepository.GLOBAL_CONTACT_ID)
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.float_strategy_compiling),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.float_strategy_compile_now))
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

/**
 * M3-b 自动参谋设置面（方案 §8.3 风险表）：总闸开启前弹显著风险告知并要求
 * 二次确认；白名单 ≤5 人增删（数据源=联系人档案）；单窗口自动发送上限。
 * 文案强调「默认关闭、随时可关、全部本地执行」。
 */
@Composable
private fun AutoAdvisorSection(
    settings: AdvisorPrefs.Settings,
    prefs: AdvisorPrefs,
    contactRepository: ContactRepository,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    SectionLabel(stringResource(R.string.float_settings_auto_section))
    var riskArmed by remember { mutableStateOf(false) }
    ToggleRow(
        title = stringResource(R.string.float_settings_auto),
        hint = stringResource(R.string.float_settings_auto_hint),
        checked = settings.autoModeEnabled,
    ) { on ->
        if (on) riskArmed = true else prefs.setAutoModeEnabled(false)
    }
    if (riskArmed) {
        AlertDialog(
            onDismissRequest = { riskArmed = false },
            title = { Text(stringResource(R.string.float_companion_risk_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.float_companion_risk_text,
                        settings.companionMaxSends,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        riskArmed = false
                        prefs.setAutoModeEnabled(true)
                    },
                ) { Text(stringResource(R.string.float_companion_risk_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { riskArmed = false }) {
                    Text(stringResource(R.string.float_companion_risk_cancel))
                }
            },
        )
    }

    Text(
        text = stringResource(R.string.float_settings_auto_max_sends),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AdvisorPrefs.COMPANION_SEND_OPTIONS.sorted().forEach { value ->
            FilterChip(
                selected = value == settings.companionMaxSends,
                onClick = { prefs.setCompanionMaxSends(value) },
                label = {
                    Text(
                        if (value == 0) {
                            stringResource(R.string.float_settings_max_sends_unlimited)
                        } else {
                            stringResource(R.string.float_settings_max_sends_value, value)
                        },
                    )
                },
            )
        }
    }

    var whitelistDialog by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<ContactProfileEntity>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(whitelistDialog) {
        if (whitelistDialog) contacts = contactRepository.listAlive()
    }
    val selectedIds = settings.autoWhitelistIds
    val selectedNames = selectedIds.mapNotNull { id ->
        contacts.firstOrNull { it.id == id }?.displayName
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { whitelistDialog = true },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(R.string.float_settings_auto_whitelist),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (selectedNames.isEmpty()) {
                    stringResource(R.string.float_settings_auto_whitelist_empty)
                } else {
                    stringResource(
                        R.string.float_settings_auto_whitelist_value,
                        selectedIds.size,
                        selectedNames.joinToString("、"),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
    if (whitelistDialog) {
        AlertDialog(
            onDismissRequest = { whitelistDialog = false },
            title = { Text(stringResource(R.string.float_settings_auto_whitelist)) },
            text = {
                if (contacts.isEmpty()) {
                    Text(stringResource(R.string.float_settings_auto_whitelist_none))
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        contacts.forEach { contact ->
                            val inList = contact.id in selectedIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = contact.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(
                                        if (inList) {
                                            R.string.float_settings_auto_whitelist_remove
                                        } else {
                                            R.string.float_settings_auto_whitelist_add
                                        },
                                    ),
                                    color = if (inList) scheme.error else scheme.primary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .clickable {
                                            if (inList) {
                                                prefs.removeAutoWhitelistContact(contact.id)
                                            } else if (!prefs.addAutoWhitelistContact(contact.id)) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    R.string.float_settings_auto_whitelist_full,
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                        .padding(6.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { whitelistDialog = false }) {
                    Text(stringResource(R.string.float_ask_close))
                }
            },
            dismissButton = {},
        )
    }
}

/**
 * R1 三模执行通道选择（§17.2：配置 > A > B > C）+ 模式 B 八项就绪门红绿表
 * （§20.1，全绿才放行）。选 Shizuku 时先跑就绪探针：全绿才切通道，有红项则
 * 保持原通道并逐一展示缺项与下一步操作（红行可点击的项给直达入口）。
 */
@Composable
private fun AutoExecutorSection(
    settings: AdvisorPrefs.Settings,
    prefs: AdvisorPrefs,
    readinessGate: ReadinessGate,
    screenCapture: ScreenCapture,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    SectionLabel(stringResource(R.string.float_settings_executor_section))
    Text(
        text = stringResource(R.string.float_settings_executor_hint),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ReadinessReport?>(null) }
    fun runCheck() {
        checking = true
        scope.launch {
            report = readinessGate.check(settings.autoWhitelistIds)
            checking = false
            if (report?.allGreen == true) {
                prefs.setAutoExecutorChannel(InjectorSelector.CONFIG_SHIZUKU)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = settings.autoExecutorChannel == InjectorSelector.CONFIG_AUTO,
            onClick = { prefs.setAutoExecutorChannel(InjectorSelector.CONFIG_AUTO) },
            label = { Text(stringResource(R.string.float_settings_executor_auto)) },
        )
        FilterChip(
            selected = settings.autoExecutorChannel == InjectorSelector.CONFIG_A11Y,
            onClick = { prefs.setAutoExecutorChannel(InjectorSelector.CONFIG_A11Y) },
            label = { Text(stringResource(R.string.float_settings_executor_a11y)) },
        )
        FilterChip(
            selected = settings.autoExecutorChannel == InjectorSelector.CONFIG_SHIZUKU,
            onClick = { runCheck() },
            label = { Text(stringResource(R.string.float_settings_executor_shizuku)) },
        )
    }

    if (checking) {
        Text(
            text = stringResource(R.string.float_settings_readiness_checking),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    report?.let { r ->
        if (!r.allGreen) {
            Text(
                text = stringResource(R.string.float_settings_readiness_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = scheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                r.items.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                    ) {
                        Text(
                            text = if (item.ok) "✓" else "×",
                            color = if (item.ok) scheme.primary else scheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = readinessLabel(item.id),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        )
                        if (!item.ok) {
                            Text(
                                text = readinessFixHint(item.id),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.primary,
                                modifier = Modifier.clickable {
                                    when (item.id) {
                                        ReadinessGate.ID_SHIZUKU -> readinessGate.requestShizukuPermission()
                                        ReadinessGate.ID_PROJECTION -> scope.launch {
                                            runCatching { screenCapture.ensureProjection() }
                                        }
                                        ReadinessGate.ID_NOTIFY -> runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                                            )
                                        }
                                    }
                                }
                                .padding(6.dp),
                            )
                        }
                    }
                    if (!item.ok) {
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 22.dp),
                        )
                    }
                }
            }
        }
        TextButton(onClick = { runCheck() }, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.float_settings_readiness_rerun))
        }
    }
}

/**
 * 接管与达成通报设置面（2026-10-20 通报方案 §十）：总闸 + uncertain 严格档 +
 * SMTP 邮件服务器配置（邮件实发 E3 接入，本区块先落配置，默认=测试环境阿里企业邮）。
 */
@Composable
private fun EscalationSection(
    settings: AdvisorPrefs.Settings,
    prefs: AdvisorPrefs,
) {
    val scheme = MaterialTheme.colorScheme
    SectionLabel(stringResource(R.string.float_settings_escalation_section))
    ToggleRow(
        title = stringResource(R.string.float_settings_escalation),
        hint = stringResource(R.string.float_settings_escalation_hint),
        checked = settings.escalationEnabled,
        onChange = { prefs.setEscalationEnabled(it) },
    )
    ToggleRow(
        title = stringResource(R.string.float_settings_escalation_strict),
        hint = stringResource(R.string.float_settings_escalation_strict_hint),
        checked = settings.escalationStrict,
        onChange = { prefs.setEscalationStrict(it) },
    )
    var smtpDialog by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { smtpDialog = true },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(R.string.float_settings_smtp_entry),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = settings.smtpHost + ":" + settings.smtpPort +
                    if (settings.smtpSsl) " (SSL)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
    if (smtpDialog) {
        SmtpConfigDialog(
            settings = settings,
            onSave = { host, port, ssl, user, password, fromName, fromEmail, timeout ->
                prefs.setSmtpConfig(host, port, ssl, user, password, fromName, fromEmail, timeout)
                smtpDialog = false
            },
            onDismiss = { smtpDialog = false },
        )
    }
}

/** SMTP 配置对话框：初始值取当前设置（首次打开=测试环境阿里企业邮缺省）。 */
@Composable
private fun SmtpConfigDialog(
    settings: AdvisorPrefs.Settings,
    onSave: (
        host: String,
        port: Int,
        ssl: Boolean,
        user: String,
        password: String,
        fromName: String,
        fromEmail: String,
        timeoutSec: Int,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf(settings.smtpHost) }
    var port by remember { mutableStateOf(settings.smtpPort.toString()) }
    var ssl by remember { mutableStateOf(settings.smtpSsl) }
    var user by remember { mutableStateOf(settings.smtpUser) }
    var password by remember { mutableStateOf(settings.smtpPassword) }
    var fromName by remember { mutableStateOf(settings.smtpFromName) }
    var fromEmail by remember { mutableStateOf(settings.smtpFromEmail) }
    var timeout by remember { mutableStateOf(settings.smtpTimeoutSec.toString()) }
    val portValue = port.toIntOrNull() ?: settings.smtpPort
    val timeoutValue = timeout.toIntOrNull() ?: settings.smtpTimeoutSec
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_settings_smtp_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.float_settings_smtp_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.float_settings_smtp_port)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.float_settings_smtp_ssl),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = ssl, onCheckedChange = { ssl = it })
                }
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.float_settings_smtp_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.float_settings_smtp_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fromName,
                    onValueChange = { fromName = it },
                    label = { Text(stringResource(R.string.float_settings_smtp_from_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fromEmail,
                    onValueChange = { fromEmail = it },
                    label = { Text(stringResource(R.string.float_settings_smtp_from_email)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { timeout = it },
                    label = { Text(stringResource(R.string.float_settings_smtp_timeout)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(host, portValue, ssl, user, password, fromName, fromEmail, timeoutValue)
                },
            ) { Text(stringResource(R.string.float_settings_smtp_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_settings_smtp_cancel))
            }
        },
    )
}

@Composable
private fun readinessLabel(id: String): String = stringResource(
    when (id) {
        ReadinessGate.ID_SHIZUKU -> R.string.float_readiness_shizuku
        ReadinessGate.ID_PROJECTION -> R.string.float_readiness_projection
        ReadinessGate.ID_OCR -> R.string.float_readiness_ocr
        ReadinessGate.ID_TEMPLATES -> R.string.float_readiness_templates
        ReadinessGate.ID_INPUT -> R.string.float_readiness_input
        ReadinessGate.ID_WHITELIST -> R.string.float_readiness_whitelist
        ReadinessGate.ID_NOTIFY -> R.string.float_readiness_notify
        else -> R.string.float_readiness_quiet
    },
)

/** 红行的下一步操作入口文案（不可直达的项提示做法，可直达的项点击即触发）。 */
@Composable
private fun readinessFixHint(id: String): String = stringResource(
    when (id) {
        ReadinessGate.ID_SHIZUKU -> R.string.float_readiness_fix_shizuku
        ReadinessGate.ID_PROJECTION -> R.string.float_readiness_fix_projection
        ReadinessGate.ID_OCR -> R.string.float_readiness_fix_ocr
        ReadinessGate.ID_TEMPLATES -> R.string.float_readiness_fix_templates
        ReadinessGate.ID_INPUT -> R.string.float_readiness_fix_input
        ReadinessGate.ID_WHITELIST -> R.string.float_readiness_fix_whitelist
        ReadinessGate.ID_NOTIFY -> R.string.float_readiness_fix_notify
        else -> R.string.float_readiness_fix_quiet
    },
)

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

private val BACKOFF_OPTIONS = listOf(
    LabeledOption(1, R.string.float_settings_backoff_off),
    LabeledOption(2, R.string.float_settings_backoff_2x),
    LabeledOption(4, R.string.float_settings_backoff_4x),
)

/** 后台摘要/萃取的频率档（回合数）。 */
private val DIGEST_FREQUENCY = listOf(3, 6, 12)
private val EXTRACT_FREQUENCY = listOf(3, 5, 10)

private val MEMORY_MODE_OPTIONS = listOf(
    LabeledOption(AdvisorPrefs.MEMORY_MODE_AUTO, R.string.float_settings_memory_auto),
    LabeledOption(AdvisorPrefs.MEMORY_MODE_FULL, R.string.float_settings_memory_full),
    LabeledOption(AdvisorPrefs.MEMORY_MODE_LEAN, R.string.float_settings_memory_lean),
)

private val ANSWER_LENGTH_OPTIONS = listOf(
    LabeledOption(AdvisorPrefs.ANSWER_CONCISE, R.string.float_settings_answer_concise),
    LabeledOption(AdvisorPrefs.ANSWER_STANDARD, R.string.float_settings_answer_standard),
    LabeledOption(AdvisorPrefs.ANSWER_DETAILED, R.string.float_settings_answer_detailed),
)

private val DEDUP_LEVEL_OPTIONS = listOf(
    LabeledOption(AdvisorPrefs.DEDUP_LOOSE, R.string.float_settings_dedup_loose),
    LabeledOption(AdvisorPrefs.DEDUP_STANDARD, R.string.float_settings_dedup_standard),
    LabeledOption(AdvisorPrefs.DEDUP_STRICT, R.string.float_settings_dedup_strict),
)

private val TOKEN_BUDGET_OPTIONS = listOf(
    LabeledOption(0L, R.string.float_settings_budget_none),
    LabeledOption(20_000L, R.string.float_settings_budget_20k),
    LabeledOption(50_000L, R.string.float_settings_budget_50k),
    LabeledOption(100_000L, R.string.float_settings_budget_100k),
)

/**
 * 用量统计卡片：主参谋回合的累计/本次会话 token 与回合数（持久化），
 * 以及 DeepSeek 官方账户余额（仅 DeepSeek 供应商显示，手动刷新）。
 */
@Composable
private fun TokenUsageCard(
    prefs: AdvisorPrefs,
    balanceRepository: DeepSeekBalanceRepository,
) {
    val scheme = MaterialTheme.colorScheme
    val stats by prefs.tokenStats.collectAsState()
    var balance by remember { mutableStateOf<DeepSeekBalanceRepository.Balance?>(null) }
    var balanceLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        balance = balanceRepository.refresh()
    }
    SectionLabel(stringResource(R.string.float_settings_usage_title))
    Text(
        text = stringResource(
            R.string.float_settings_usage_total,
            stats.totalInput,
            stats.totalOutput,
            stats.totalTurns,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.float_settings_usage_session, stats.sessionTotal),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
    )
            val current = balance
            if (current != null && current.supported) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    val amount = when {
                        current.total.isBlank() ->
                            stringResource(R.string.float_settings_balance_unknown)
                        !current.available ->
                            stringResource(
                                R.string.float_settings_balance_unavailable_fmt,
                                current.total,
                            )
                        else -> stringResource(R.string.float_settings_balance_value_fmt, current.total)
                    }
                    Text(
                        text = stringResource(R.string.float_settings_balance_line, amount),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = !balanceLoading,
                        onClick = {
                            scope.launch {
                                balanceLoading = true
                                balance = balanceRepository.refresh()
                                balanceLoading = false
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(
                                if (balanceLoading) {
                                    R.string.float_settings_balance_refreshing
                                } else {
                                    R.string.float_settings_balance_refresh
                                },
                            ),
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                OutlinedButton(onClick = { prefs.clearAllUsage() }) {
                    Text(text = stringResource(R.string.float_settings_usage_clear))
                }
            }
}

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

/** 回合频率选择（每 N 回合）；总开关关闭时 chips 置灰。 */
@Composable
private fun FrequencyRow(
    values: List<Int>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                enabled = enabled,
                onClick = { onSelect(value) },
                label = { Text(stringResource(R.string.float_settings_every_n_turns, value)) },
            )
        }
    }
}

/** 带说明文案的开关行（后台摘要/萃取等会产生额外网络消耗的能力）。 */
@Composable
private fun ToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
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
