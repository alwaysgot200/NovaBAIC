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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.floatball.advisor.CardCompiler
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.advisor.PATH_PURSUE
import com.verlintas.baic2.floatball.advisor.StyleAssets
import com.verlintas.baic2.floatball.advisor.StrategyCardRepository
import com.verlintas.baic2.floatball.advisor.data.CARD_DRAFT
import com.verlintas.baic2.floatball.advisor.data.CONTACT_TYPE_GROUP
import com.verlintas.baic2.floatball.advisor.data.StrategyCardEntity
import com.verlintas.baic2.floatball.advisor.strategy.CardItem
import com.verlintas.baic2.floatball.advisor.strategy.CardPayloads
import com.verlintas.baic2.floatball.advisor.strategy.StrategyCardPayload
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 策略卡审核页（P2 §6.4）：全局卡（contactId=0）/ 联系人卡。
 * 角色句可直接改写（首改即标 source=user，重编不覆盖）；四个分区中 ai 条目可
 * 「改」（升级为用户句并就地编辑）或删除，可随时新增用户句。✓ 确认后归档同联系人
 * 其余版本；重新编译走 [CardCompiler]，完成后经 Flow 自动刷新。
 */
@AndroidEntryPoint
class StrategyCardActivity : ComponentActivity() {

    @Inject
    lateinit var cards: StrategyCardRepository

    @Inject
    lateinit var compiler: CardCompiler

    @Inject
    lateinit var contacts: ContactRepository

    @Inject
    lateinit var assets: StyleAssets

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
        val contactId = intent.getLongExtra(EXTRA_CONTACT_ID, GLOBAL_CONTACT_ID)
        setContent {
            val themeMode by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val accent by settingsRepository.accentColor
                .collectAsStateWithLifecycle(initialValue = AccentColor.BLUE)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
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
                StrategyCardScreen(contactId = contactId, onClose = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_CONTACT_ID = "extra_contact_id"
        const val GLOBAL_CONTACT_ID = 0L
    }
}

@Composable
private fun StrategyCardScreen(contactId: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence<Context>(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<StrategyCardActivity>().first()
    }
    val cards = activity.cards
    val scope = rememberCoroutineScope()

    val isContact = contactId > 0L
    val card by cards.observeLatest(contactId)
        .collectAsStateWithLifecycle(initialValue = null)

    var contactName by remember { mutableStateOf("") }
    var scenarioPathKind by remember { mutableStateOf<String?>(null) }
    // P3：群=单一对方实体，策略卡两处文案切换为群口径。
    var isGroup by remember { mutableStateOf(false) }
    LaunchedEffect(contactId) {
        if (isContact) {
            val profile = activity.contacts.profile(contactId)
            contactName = profile?.displayName.orEmpty()
            isGroup = profile?.contactType == CONTACT_TYPE_GROUP
            scenarioPathKind = activity.assets.scenarioPacks()
                .byCode(profile?.scenarioCode)?.pathKind
        }
    }

    // 本地编辑副本：DB 行变化（重编/确认）时整份重置；未保存期间不受重组影响。
    var payload by remember { mutableStateOf<StrategyCardPayload?>(null) }
    LaunchedEffect(card?.id, card?.payloadJson) {
        payload = CardPayloads.parse(card?.payloadJson)
    }

    fun toast(resId: Int) {
        android.widget.Toast.makeText(
            context, context.getString(resId), android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AdvisorPage(
            title = if (isContact) {
                stringResource(R.string.float_strategy_card_title_contact, contactName)
            } else {
                stringResource(R.string.float_strategy_card_title_default)
            },
            onBack = onClose,
        ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                val current = card
                val edited = payload
                if (current == null || edited == null) {
                    Text(
                        text = stringResource(
                            if (isGroup) R.string.float_strategy_empty_group else R.string.float_strategy_empty,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                activity.compiler.compile(contactId)
                                toast(R.string.float_strategy_compile_start)
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(stringResource(R.string.float_strategy_compile_now)) }
                } else {
                    CardStatusBadge(current)

                    OutlinedTextField(
                        value = edited.roleLine,
                        onValueChange = { text ->
                            payload = edited.copy(
                                roleLine = text,
                                roleLineSource = CardItem.SOURCE_USER,
                            )
                        },
                        label = { Text(stringResource(R.string.float_strategy_role_line)) },
                        minLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )

                    CardSection(
                        title = stringResource(R.string.float_strategy_section_self),
                        items = edited.selfConstraints,
                        emptyHint = null,
                        onChange = { index, text ->
                            payload = edited.copy(
                                selfConstraints = edited.selfConstraints.replaceAt(index, text),
                            )
                        },
                        onPromote = { index ->
                            payload = edited.copy(
                                selfConstraints = edited.selfConstraints.promoteAt(index),
                            )
                        },
                        onDelete = { index ->
                            payload = edited.copy(
                                selfConstraints = edited.selfConstraints.dropAt(index),
                            )
                        },
                        onAdd = {
                            payload = edited.copy(
                                selfConstraints = edited.selfConstraints + CardItem(source = CardItem.SOURCE_USER),
                            )
                        },
                    )

                    if (isContact) {
                        CardSection(
                            title = stringResource(
                                if (isGroup) {
                                    R.string.float_strategy_section_their_group
                                } else {
                                    R.string.float_strategy_section_their
                                },
                            ),
                            items = edited.theirImplications,
                            emptyHint = null,
                            onChange = { index, text ->
                                payload = edited.copy(
                                    theirImplications = edited.theirImplications.replaceAt(index, text),
                                )
                            },
                            onPromote = { index ->
                                payload = edited.copy(
                                    theirImplications = edited.theirImplications.promoteAt(index),
                                )
                            },
                            onDelete = { index ->
                                payload = edited.copy(
                                    theirImplications = edited.theirImplications.dropAt(index),
                                )
                            },
                            onAdd = {
                                payload = edited.copy(
                                    theirImplications = edited.theirImplications +
                                        CardItem(source = CardItem.SOURCE_USER),
                                )
                            },
                        )
                    }

                    CardSection(
                        title = stringResource(R.string.float_strategy_section_topic),
                        items = edited.topicBank,
                        emptyHint = if (scenarioPathKind != null && scenarioPathKind != PATH_PURSUE) {
                            stringResource(R.string.float_strategy_empty_topic)
                        } else {
                            null
                        },
                        onChange = { index, text ->
                            payload = edited.copy(
                                topicBank = edited.topicBank.replaceAt(index, text),
                            )
                        },
                        onPromote = { index ->
                            payload = edited.copy(
                                topicBank = edited.topicBank.promoteAt(index),
                            )
                        },
                        onDelete = { index ->
                            payload = edited.copy(
                                topicBank = edited.topicBank.dropAt(index),
                            )
                        },
                        onAdd = {
                            payload = edited.copy(
                                topicBank = edited.topicBank + CardItem(source = CardItem.SOURCE_USER),
                            )
                        },
                    )

                    CardSection(
                        title = stringResource(R.string.float_strategy_section_taboos),
                        items = edited.taboos,
                        emptyHint = null,
                        onChange = { index, text ->
                            payload = edited.copy(
                                taboos = edited.taboos.replaceAt(index, text),
                            )
                        },
                        onPromote = { index ->
                            payload = edited.copy(
                                taboos = edited.taboos.promoteAt(index),
                            )
                        },
                        onDelete = { index ->
                            payload = edited.copy(
                                taboos = edited.taboos.dropAt(index),
                            )
                        },
                        onAdd = {
                            payload = edited.copy(
                                taboos = edited.taboos + CardItem(source = CardItem.SOURCE_USER),
                            )
                        },
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = {
                            scope.launch {
                                cards.saveEditedDraft(current.id, edited)
                                cards.confirm(current.id)
                                toast(R.string.float_strategy_confirmed)
                            }
                        }) { Text(stringResource(R.string.float_strategy_confirm)) }
                        if (current.status == CARD_DRAFT) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    cards.discardDraft(current.id)
                                    toast(R.string.float_strategy_discarded)
                                }
                            }) { Text(stringResource(R.string.float_strategy_discard)) }
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                activity.compiler.compile(contactId)
                                toast(R.string.float_strategy_compile_start)
                            }
                        }) { Text(stringResource(R.string.float_strategy_recompile)) }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun CardStatusBadge(card: StrategyCardEntity) {
    val text = if (card.status == CARD_DRAFT) {
        stringResource(R.string.float_goal_card_draft, card.version)
    } else {
        stringResource(R.string.float_goal_card_confirmed, card.version)
    }
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CardSection(
    title: String,
    items: List<CardItem>,
    emptyHint: String?,
    onChange: (Int, String) -> Unit,
    onPromote: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
    items.forEachIndexed { index, item ->
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (item.source == CardItem.SOURCE_USER) {
                    OutlinedTextField(
                        value = item.text,
                        onValueChange = { onChange(index, it) },
                        label = { Text(stringResource(R.string.float_strategy_item_user_tag)) },
                        minLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (item.source != CardItem.SOURCE_USER) {
                        Text(
                            text = stringResource(R.string.float_goal_edit),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { onPromote(index) }
                                .padding(vertical = 4.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.float_strategy_item_delete),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { onDelete(index) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
    if (items.isEmpty() && emptyHint != null) {
        Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    TextButton(onClick = onAdd, modifier = Modifier.padding(top = 4.dp)) {
        Text(stringResource(R.string.float_strategy_add_item))
    }
}

private fun List<CardItem>.replaceAt(index: Int, text: String): List<CardItem> =
    mapIndexed { i, item -> if (i == index) item.copy(text = text) else item }

/** 「改」：ai 句升级为用户句并就地进入可编辑态。 */
private fun List<CardItem>.promoteAt(index: Int): List<CardItem> =
    mapIndexed { i, item ->
        if (i == index) item.copy(source = CardItem.SOURCE_USER) else item
    }

private fun List<CardItem>.dropAt(index: Int): List<CardItem> =
    filterIndexed { i, _ -> i != index }

private fun Context.withLocale(locale: Locale): Context {
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}
