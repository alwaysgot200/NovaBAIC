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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.verlintas.baic2.floatball.advisor.data.SelfPersonaEntity
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 全局我方人设标签库页（VA-2 U1，UltraAdvisor v2.2 方案 §2.4「我的 → 人设」）：
 * 维护 self_personas 标签库（标签 + 备注）的增/删/改，是各联系人建档双建时
 * 复制 selfFacet 起点的模板源（复制发生在建档侧，本页只管库维护）。
 *
 * 入口挂在 floatball 自己的画像页（AdvisorProfileActivity「我的人设」屏顶部
 * 入口卡）：上游「我的/设置」导航零侵入，语义上人设标签库本就归属人设链路。
 * 独立成 Activity 而非 ProfileNav 内嵌屏：既有导航枚举/分支零改动。
 */
@AndroidEntryPoint
class SelfPersonaLibraryActivity : ComponentActivity() {

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
                SelfPersonaLibraryScreen(
                    contacts = contacts,
                    onClose = { finish() },
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

@Composable
private fun SelfPersonaLibraryScreen(
    contacts: ContactRepository,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SelfPersonaEntity>>(emptyList()) }
    var dialogFor by remember { mutableStateOf<SelfPersonaEntity?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SelfPersonaEntity?>(null) }

    suspend fun refresh() {
        items = contacts.selfPersonas()
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

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
                        text = stringResource(R.string.float_selfpersona_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onClose) {
                        Text(stringResource(R.string.float_ask_close))
                    }
                }
                Text(
                    text = stringResource(R.string.float_selfpersona_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                if (items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.float_selfpersona_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
                items.forEach { persona ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable { dialogFor = persona },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = persona.tag,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                persona.note?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.float_selfpersona_edit),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clickable { dialogFor = persona }
                                    .padding(6.dp),
                            )
                            Text(
                                text = stringResource(R.string.float_selfpersona_delete),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clickable { deleting = persona }
                                    .padding(6.dp),
                            )
                        }
                    }
                }

                Button(
                    onClick = { addOpen = true },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(stringResource(R.string.float_selfpersona_add))
                }
            }
        }
    }

    // 新增 / 编辑共用一个弹窗：编辑时改标签名 = 删旧行 + 建新行（tag 唯一键）。
    val editing = dialogFor
    if (addOpen || editing != null) {
        PersonaTagDialog(
            initial = editing,
            onDismiss = {
                addOpen = false
                dialogFor = null
            },
            onSave = { tag, note ->
                addOpen = false
                dialogFor = null
                scope.launch {
                    val old = editing
                    if (old != null && old.tag != tag.trim()) contacts.deleteSelfPersona(old.id)
                    contacts.upsertSelfPersona(tag, note)
                    refresh()
                }
            },
        )
    }

    deleting?.let { persona ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.float_selfpersona_delete_title)) },
            text = {
                Text(stringResource(R.string.float_selfpersona_delete_confirm, persona.tag))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        contacts.deleteSelfPersona(persona.id)
                        refresh()
                    }
                }) {
                    Text(
                        text = stringResource(R.string.float_selfpersona_delete_confirm_btn),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.float_profile_cancel))
                }
            },
        )
    }
}

/** 标签编辑弹窗：标签必填（空白不落库由 Repository 兜底），备注可选。 */
@Composable
private fun PersonaTagDialog(
    initial: SelfPersonaEntity?,
    onDismiss: () -> Unit,
    onSave: (tag: String, note: String?) -> Unit,
) {
    var tag by remember(initial) { mutableStateOf(initial?.tag.orEmpty()) }
    var note by remember(initial) { mutableStateOf(initial?.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial == null) {
                    stringResource(R.string.float_selfpersona_add)
                } else {
                    stringResource(R.string.float_selfpersona_edit)
                },
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.float_selfpersona_tag_label)) },
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.float_selfpersona_note_label)) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = tag.isNotBlank(), onClick = { onSave(tag, note.ifBlank { null }) }) {
                Text(stringResource(R.string.float_profile_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_profile_cancel))
            }
        },
    )
}
