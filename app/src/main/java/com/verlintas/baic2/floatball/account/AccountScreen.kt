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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.account

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Spacing
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.floatball.AdvisorPage
import dagger.hilt.android.AndroidEntryPoint
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 好参谋账号管理页入口。主题包装与 [com.verlintas.baic2.floatball.FloatSettingsActivity]
 * 同构（AppLocaleStore 语言 + Baic2Theme + 状态栏图标色），页面骨架走 AdvisorPage。
 */
@AndroidEntryPoint
class AccountActivity : ComponentActivity() {

    @Inject
    lateinit var accountManager: AccountManager

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
                AccountScreen(
                    accountManager = accountManager,
                    onClose = { finish() },
                )
            }
        }
    }
}

/**
 * 账号管理页（商业化方案 §5）：账号信息、积分钱包查询、卡密绑定使用
 * （入账 + AI 网关凭证落地一体，条目式输入、实时余额/到期）、退出登录。
 * 卡片式布局，错误一律映射文案。
 */
@Composable
internal fun AccountScreen(
    accountManager: AccountManager,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val session by accountManager.session.collectAsStateWithLifecycle()

    var wallet by remember { mutableStateOf<AccountApi.Wallet?>(null) }
    var walletBusy by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf<List<AccountApi.MyCard>>(emptyList()) }
    var cardsBusy by remember { mutableStateOf(false) }
    val entries = remember { mutableStateListOf(CardEntry()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var noticeText by remember { mutableStateOf<String?>(null) }
    val cardSuccessText = stringResource(R.string.float_account_card_success)
    val cardInvalidText = stringResource(R.string.float_account_error_card_invalid)

    fun refreshWallet() {
        scope.launch {
            walletBusy = true
            errorText = null
            runCatching { accountManager.wallet() }
                .onSuccess { wallet = it }
                .onFailure { errorText = errorTextOf(it, context) }
            walletBusy = false
        }
    }

    /** 拉取已绑定卡密（服务端实时余额/到期）：进页面、绑定成功、点「刷新」时调用。 */
    fun refreshCards() {
        scope.launch {
            cardsBusy = true
            runCatching { accountManager.myCards() }
                .onSuccess { cards = it }
                .onFailure { errorText = errorTextOf(it, context) }
            cardsBusy = false
        }
    }

    /** 绑定使用第 [index] 条卡密：成功后该条目移除、卡密进入「已绑定卡密」实时列表。 */
    fun bindCard(index: Int) {
        val entry = entries.getOrNull(index) ?: return
        val cardKey = entry.input.trim()
        if (cardKey.isBlank()) {
            entries[index] = entry.copy(error = cardInvalidText)
            return
        }
        scope.launch {
            entries[index] = entries[index].copy(busy = true, error = null)
            val result = runCatching { accountManager.enableCard(cardKey) }
            result.onSuccess { info ->
                wallet = info.wallet
                noticeText = cardSuccessText
                entries.removeAt(index)
                if (entries.isEmpty()) entries.add(CardEntry())
            }.onFailure { t ->
                entries[index] = entries[index].copy(busy = false, error = errorTextOf(t, context))
            }
            if (result.isSuccess) refreshCards()
        }
    }

    LaunchedEffect(session) {
        if (session != null) {
            if (wallet == null) refreshWallet()
            refreshCards()
        }
    }

    AdvisorPage(
        title = stringResource(R.string.float_account_manage_title),
        onBack = onClose,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Baic2Spacing.lg),
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                // 账号信息卡：账号名称 + 有效总积分（多卡合并展示，到期只在每张卡明细里显示）。
                AccountCard(stringResource(R.string.float_account_section_profile)) {
                    val current = session
                    if (current == null) {
                        Text(
                            text = stringResource(R.string.float_login_required),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.float_account_name_row, current.username),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = Baic2Spacing.xs),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.float_account_points_balance,
                                    formatPoints(wallet?.pointsBalanceMilli),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = { refreshWallet() },
                                enabled = !walletBusy,
                            ) {
                                Text(
                                    text = stringResource(
                                        if (walletBusy) {
                                            R.string.float_account_points_loading
                                        } else {
                                            R.string.float_account_points_refresh
                                        },
                                    ),
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.float_account_points_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Baic2Spacing.xs),
                        )
                    }
                }

                // 卡密卡：有效卡按绑定先后排队（首张「使用中」），下方是「输入框 + 绑定使用」条目。
                AccountCard(stringResource(R.string.float_account_section_card)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.float_account_card_list_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { refreshCards() },
                            enabled = !cardsBusy && session != null,
                        ) {
                            Text(
                                text = stringResource(
                                    if (cardsBusy) {
                                        R.string.float_account_points_loading
                                    } else {
                                        R.string.float_account_points_refresh
                                    },
                                ),
                            )
                        }
                    }
                    if (cards.isEmpty()) {
                        Text(
                            text = stringResource(R.string.float_account_card_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Baic2Spacing.xs),
                        )
                    } else {
                        // 服务端已按绑定先后升序返回，且只含「生效中·未过期·仍有余额」的卡：
                        // 首张即当前正在消耗的卡，其余为待用。
                        cards.forEachIndexed { index, card ->
                            BoundCardRow(card = card, consuming = index == 0)
                        }
                    }
                    Text(
                        text = stringResource(R.string.float_account_card_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Baic2Spacing.sm),
                    )
                    val anyBusy = entries.any { it.busy }
                    entries.forEachIndexed { index, entry ->
                        CardEntryRow(
                            entry = entry,
                            enabled = session != null && !anyBusy,
                            showRemove = entries.size > 1 && !anyBusy,
                            onValueChange = { entries[index] = entry.copy(input = it, error = null) },
                            onRemove = { entries.removeAt(index) },
                            onBind = { bindCard(index) },
                        )
                    }
                    TextButton(
                        onClick = { entries.add(CardEntry()) },
                        enabled = !anyBusy,
                        modifier = Modifier.padding(top = Baic2Spacing.xs),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(Baic2Spacing.xs))
                        Text(stringResource(R.string.float_account_card_add))
                    }
                }

                noticeText?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Baic2Spacing.sm),
                    )
                }
                errorText?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Baic2Spacing.sm),
                    )
                }

                Spacer(modifier = Modifier.height(Baic2Spacing.xl))
                OutlinedButton(
                    onClick = {
                        accountManager.logout()
                        onClose()
                    },
                    enabled = session != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.float_account_logout))
                }
                Spacer(modifier = Modifier.height(Baic2Spacing.xxl))
            }
        }
    }
}

/** 卡片容器：标题 + 内容，与好参谋设置页入口卡同观感。 */
@Composable
private fun AccountCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Baic2Spacing.md),
    ) {
        Column(modifier = Modifier.padding(Baic2Spacing.md)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(Baic2Spacing.sm))
            content()
        }
    }
}

/** 卡密输入条目：单输入框 + 「绑定使用」；[error] 为行内报因文案。 */
private data class CardEntry(
    val input: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

/** 有效卡密子卡：卡号 + 该卡实时积分余额/到期日期 + 状态（使用中/待用），卡片式非表格。 */
@Composable
private fun BoundCardRow(card: AccountApi.MyCard, consuming: Boolean) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Baic2Spacing.xs),
    ) {
        Column(modifier = Modifier.padding(Baic2Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.float_account_card_no_row, card.cardNo),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (consuming) {
                            R.string.float_account_card_status_active
                        } else {
                            R.string.float_account_card_status_waiting
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (consuming) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(modifier = Modifier.padding(top = Baic2Spacing.xs)) {
                Text(
                    text = stringResource(
                        R.string.float_account_card_points,
                        formatPoints(card.balanceMilli),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        R.string.float_account_card_expire,
                        card.expireAt?.let { formatIsoTime(it) } ?: "—",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 卡密输入条目行：输入框 + 「绑定使用」按钮（多条目时可移除）；报因文案在行下方。 */
@Composable
private fun CardEntryRow(
    entry: CardEntry,
    enabled: Boolean,
    showRemove: Boolean,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
    onBind: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Baic2Spacing.sm),
    ) {
        OutlinedTextField(
            value = entry.input,
            onValueChange = onValueChange,
            placeholder = { Text(stringResource(R.string.float_account_card_placeholder)) },
            singleLine = true,
            enabled = enabled && !entry.busy,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onBind,
            enabled = enabled && !entry.busy && entry.input.isNotBlank(),
            modifier = Modifier.padding(start = Baic2Spacing.sm),
        ) {
            Text(
                text = stringResource(
                    if (entry.busy) {
                        R.string.float_account_card_busy
                    } else {
                        R.string.float_account_card_enable
                    },
                ),
            )
        }
        if (showRemove) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.float_account_card_remove),
                )
            }
        }
    }
    entry.error?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 错误映射：REASON_* 常量 → strings.xml 文案；服务端动态原因优先展示。 */
private fun errorTextOf(t: Throwable, context: Context): String {
    val e = t as? AccountException
        ?: return context.getString(R.string.float_account_error_op, t.message ?: t.toString())
    return when (e.reason) {
        AccountApi.REASON_NETWORK -> context.getString(R.string.float_account_error_network)
        AccountApi.REASON_BAD_RESPONSE -> context.getString(R.string.float_account_error_bad_response)
        AccountManager.REASON_NOT_LOGGED_IN -> context.getString(R.string.float_login_required)
        AccountManager.REASON_CARD_INVALID -> context.getString(R.string.float_account_error_card_invalid)
        AccountManager.REASON_NO_AGENT -> context.getString(R.string.float_account_error_no_agent)
        AccountManager.REASON_GATEWAY -> context.getString(R.string.float_account_error_gateway)
        else -> context.getString(
            R.string.float_account_error_op,
            e.serverMsg ?: e.reason,
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

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** ISO-8601 → 本地时间；解析失败回退原文。 */
private fun formatIsoTime(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
}.getOrDefault(iso)

/** 毫积分字符串 → 积分（÷1000，去尾零）；解析失败回退原文/占位。 */
private fun formatPoints(milli: String?): String {
    if (milli.isNullOrBlank()) return "—"
    return runCatching {
        BigDecimal(milli).divide(BigDecimal(1000)).stripTrailingZeros().toPlainString()
    }.getOrDefault(milli)
}
