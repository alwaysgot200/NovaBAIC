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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.advisor.PersonaValues
import com.verlintas.baic2.floatball.advisor.StyleAssets
import com.verlintas.baic2.floatball.advisor.StyleCardRepository
import com.verlintas.baic2.floatball.advisor.StyleIntent
import com.verlintas.baic2.floatball.advisor.StyleResolver
import com.verlintas.baic2.floatball.advisor.data.STYLE_SCOPE_CONTACT
import com.verlintas.baic2.floatball.advisor.data.STYLE_SCOPE_MY_DEFAULT
import com.verlintas.baic2.floatball.advisor.data.StyleCardEntity
import com.verlintas.baic2.floatball.advisor.strategy.StyleNoteRules
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 说话风格卡编辑页（方案 §10.2，P1）：全局模式（contactId=0）/ 联系人模式。
 * 基调单选、意图多选（≤5）+ do/dont/示例、一句自定义补充、实时提示词预览；
 * 联系人模式额外支持场景一键预填。选择只在内存，点保存才落库。
 */
@AndroidEntryPoint
class StyleCardActivity : ComponentActivity() {

    @Inject
    lateinit var styleCards: StyleCardRepository

    @Inject
    lateinit var styleAssets: StyleAssets

    @Inject
    lateinit var contacts: ContactRepository

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
        val contactId = intent.getLongExtra(EXTRA_CONTACT_ID, 0L)
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
                StyleCardScreen(contactId = contactId, onClose = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_CONTACT_ID = "extra_contact_id"
    }
}

private const val MAX_INTENTS = 5
private const val MAX_NOTE_CHARS = 60

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleCardScreen(contactId: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence<Context>(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<StyleCardActivity>().first()
    }
    val styleCards = activity.styleCards
    val assets = activity.styleAssets
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val isContact = contactId > 0L
    val playbook = assets.playbook()
    val packs = assets.scenarioPacks().packs

    // P3 §八：观察已保存的卡，customNote 编译确认条（compiling/ready/failed）由 DB 行驱动。
    val savedCardFlow = remember(contactId, isContact) {
        if (isContact) styleCards.observeContactCard(contactId) else styleCards.observeDefaultCard()
    }
    val savedCard by savedCardFlow.collectAsStateWithLifecycle(initialValue = null)
    val failedNoteIds by styleCards.noteCompileFailed.collectAsStateWithLifecycle()

    var loaded by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf<String?>(null) }
    val intents = remember { mutableStateListOf<String>() }
    var note by remember { mutableStateOf("") }
    var scenario by remember { mutableStateOf<String?>(null) }

    var scenarioMenuOpen by remember { mutableStateOf(false) }
    var pendingPack by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(contactId) {
        val card = if (isContact) styleCards.contactCard(contactId) else styleCards.defaultCard()
        card?.let {
            tone = it.toneCode
            intents.clear()
            intents.addAll(PersonaValues.decodeTags(it.intentCodes))
            note = it.customNote.orEmpty()
        }
        if (isContact) {
            scenario = styleCards.scenarioCode(contactId)
            contactName = activity.contacts.profile(contactId)?.displayName.orEmpty()
        }
        loaded = true
    }

    val previewCard = StyleCardEntity(
        scope = if (isContact) STYLE_SCOPE_CONTACT else STYLE_SCOPE_MY_DEFAULT,
        contactId = contactId,
        toneCode = tone,
        intentCodes = PersonaValues.encodeTags(intents),
        customNote = note,
    )
    val preview = StyleResolver.render(previewCard, playbook, stageCode = null)
    val scenarioLabel = packs.firstOrNull { it.code == scenario }?.label

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isContact) {
                            stringResource(R.string.float_style_title_contact, contactName)
                        } else {
                            stringResource(R.string.float_style_title_default)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onClose) {
                        Text(stringResource(R.string.float_profile_back))
                    }
                }

                if (isContact) {
                    ScenarioCard(
                        scenarioLabel = scenarioLabel
                            ?: stringResource(R.string.float_scenario_none),
                        onClick = { scenarioMenuOpen = true },
                    )
                }

                // P3 §八：含 customNote 的卡保存后为待确认草稿，确认前整卡不进提示词。
                val draftCard = savedCard
                if (draftCard != null && !draftCard.confirmed && !draftCard.customNote.isNullOrBlank()) {
                    CustomNoteDraftBar(
                        card = draftCard,
                        failed = draftCard.id in failedNoteIds,
                        onAdopt = {
                            scope.launch {
                                styleCards.confirmCompiled(draftCard.id)
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.float_style_note_adopted),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onRetry = { styleCards.recompileNote(draftCard.id) },
                        onDiscard = {
                            scope.launch {
                                styleCards.discardCustomNote(draftCard.id)
                                note = ""
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.float_style_note_discarded),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }

                Text(
                    text = stringResource(R.string.float_style_tone),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    playbook.tones.forEach { option ->
                        FilterChip(
                            selected = tone == option.code,
                            onClick = { tone = if (tone == option.code) null else option.code },
                            label = { Text(option.label) },
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.float_style_intents, MAX_INTENTS),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    playbook.intents.forEach { option ->
                        val selected = option.code in intents
                        FilterChip(
                            selected = selected,
                            enabled = selected || intents.size < MAX_INTENTS,
                            onClick = {
                                if (selected) intents.remove(option.code) else intents.add(option.code)
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
                intents.mapNotNull { code -> playbook.intent(code) }.forEach { intent ->
                    IntentDetailCard(intent)
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(MAX_NOTE_CHARS) },
                    label = { Text(stringResource(R.string.float_style_custom)) },
                    placeholder = { Text(stringResource(R.string.float_style_custom_hint)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )

                Text(
                    text = stringResource(R.string.float_style_preview),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = preview ?: stringResource(R.string.float_style_preview_empty),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = loaded,
                        onClick = {
                            scope.launch {
                                if (isContact) {
                                    styleCards.saveContact(
                                        contactId = contactId,
                                        toneCode = tone,
                                        intentCodes = intents.toList(),
                                        customNote = note,
                                        scenarioCode = scenario,
                                    )
                                } else {
                                    styleCards.saveDefault(
                                        toneCode = tone,
                                        intentCodes = intents.toList(),
                                        customNote = note,
                                    )
                                }
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.float_style_saved),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                // P3 §八：有手写补充时留在本页，等慢车道编译出草稿供确认；
                                // 纯选项组合即时生效，直接关闭。
                                if (note.trim().isEmpty()) onClose()
                            }
                        }
                    ) { Text(stringResource(R.string.float_style_save)) }
                    if (isContact) {
                        OutlinedButton(onClick = { confirmReset = true }) {
                            Text(stringResource(R.string.float_style_follow_default))
                        }
                    }
                }
            }
        }
    }

    if (scenarioMenuOpen) {
        AlertDialog(
            onDismissRequest = { scenarioMenuOpen = false },
            title = { Text(stringResource(R.string.float_scenario_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ScenarioDialogRow(stringResource(R.string.float_scenario_none)) {
                        scenario = null
                        scenarioMenuOpen = false
                    }
                    // 12 个场景包按四组分段（与目标页共用 SCENARIO_GROUPS 顺序）。
                    SCENARIO_GROUPS.forEach { (group, labelRes) ->
                        val groupPacks = packs.filter { it.group == group }
                        if (groupPacks.isNotEmpty()) {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                            groupPacks.forEach { pack ->
                                ScenarioDialogRow(pack.label) {
                                    pendingPack = pack.code
                                    scenarioMenuOpen = false
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { scenarioMenuOpen = false }) {
                    Text(stringResource(R.string.float_profile_cancel))
                }
            },
        )
    }

    pendingPack?.let { code ->
        AlertDialog(
            onDismissRequest = { pendingPack = null },
            title = { Text(stringResource(R.string.float_scenario_overwrite_title)) },
            text = { Text(stringResource(R.string.float_scenario_overwrite_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    styleCards.scenarioDefaults(code)?.let { (defaultTone, defaultIntents) ->
                        tone = defaultTone
                        intents.clear()
                        intents.addAll(defaultIntents.take(MAX_INTENTS))
                    }
                    scenario = code
                    pendingPack = null
                }) { Text(stringResource(R.string.float_scenario_load)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPack = null }) {
                    Text(stringResource(R.string.float_profile_cancel))
                }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.float_style_follow_default)) },
            text = { Text(stringResource(R.string.float_style_follow_default_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        styleCards.resetContactCard(contactId)
                        onClose()
                    }
                }) { Text(stringResource(R.string.float_style_follow_default)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.float_profile_cancel))
                }
            },
        )
    }
}

@Composable
private fun ScenarioCard(scenarioLabel: String, onClick: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                text = scenarioLabel,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.float_scenario_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * P3 §八 customNote 编译确认条：compiling（转圈文字）/ failed（重试+放弃）/
 * ready（do/dont 预览 + 采用/重新理解/放弃）。草稿期间整卡不进提示词。
 */
@Composable
private fun CustomNoteDraftBar(
    card: StyleCardEntity,
    failed: Boolean,
    onAdopt: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    val compiled = remember(card.compiledCustomJson) {
        card.compiledCustomJson?.let { StyleNoteRules.parse(it) }
    }
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when {
                compiled != null -> {
                    Text(
                        text = stringResource(R.string.float_style_note_ask),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (compiled.dos.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.float_style_note_do,
                                compiled.dos.joinToString("；"),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (compiled.dont.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.float_style_note_dont,
                                compiled.dont.joinToString("；"),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                failed -> {
                    Text(
                        text = stringResource(R.string.float_style_note_failed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    Text(
                        text = stringResource(R.string.float_style_note_compiling),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                if (compiled != null) {
                    Button(onClick = onAdopt) {
                        Text(stringResource(R.string.float_style_note_adopt))
                    }
                }
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.float_style_note_retry))
                }
                OutlinedButton(onClick = onDiscard) {
                    Text(stringResource(R.string.float_style_note_discard))
                }
            }
        }
    }
}

@Composable
private fun ScenarioDialogRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

@Composable
private fun IntentDetailCard(intent: StyleIntent) {
    var expanded by remember(intent.code) { mutableStateOf(false) }
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(intent.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            intent.`do`.filter { it.isNotBlank() }.forEach { line ->
                Text(
                    text = "✓ $line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            intent.dont.filter { it.isNotBlank() }.forEach { line ->
                Text(
                    text = "✕ $line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = (if (expanded) "▾ " else "▸ ") + stringResource(R.string.float_style_example),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { expanded = !expanded },
            )
            if (expanded) {
                if (intent.good.isNotBlank()) {
                    Text(
                        text = intent.good,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (intent.bad.isNotBlank()) {
                    Text(
                        text = intent.bad,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
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
