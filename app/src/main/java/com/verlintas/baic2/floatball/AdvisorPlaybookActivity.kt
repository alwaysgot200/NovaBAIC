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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.verlintas.baic2.floatball.advisor.PlaybookRetriever
import com.verlintas.baic2.floatball.advisor.data.PlaybookEntity
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 话术库管理（方案 P5）：按分类浏览内置话术（只读）、增删用户自定义话术。
 * 自定义话术与内置同表同 FTS 索引，保存后立即参与 [PlaybookRetriever] 召回。
 */
@AndroidEntryPoint
class AdvisorPlaybookActivity : ComponentActivity() {

    @Inject
    lateinit var playbooks: PlaybookRetriever

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun attachBaseContext(newBase: Context) {
        val language = AppLocaleStore.readFrom(newBase)
        val tag = language.languageTag
        super.attachBaseContext(
            if (tag == null) newBase else newBase.withPlaybookLocale(Locale.forLanguageTag(tag)),
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
                PlaybookScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun PlaybookScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = generateSequence<android.content.Context>(context) {
        (it as? android.content.ContextWrapper)?.baseContext
    }.filterIsInstance<AdvisorPlaybookActivity>().firstOrNull()
        ?: error("AdvisorPlaybookActivity context expected")
    val playbooks = activity.playbooks
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<PlaybookEntity>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }

    suspend fun refresh() {
        all = playbooks.all()
        categories = playbooks.categories()
    }
    LaunchedEffect(Unit) { refresh() }

    val keyword = query.trim()
    val visible = all.filter { p ->
        keyword.isEmpty() ||
            p.scenario.contains(keyword, ignoreCase = true) ||
            p.content.contains(keyword, ignoreCase = true) ||
            p.tags.split(",").any { it.trim().contains(keyword, ignoreCase = true) }
    }

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
                        text = stringResource(R.string.float_playbook_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onClose) {
                        Text(stringResource(R.string.float_ask_close))
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.float_playbook_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Button(onClick = { adding = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.float_playbook_add))
                }

                visible.groupBy { it.category }.forEach { (category, list) ->
                    Text(
                        text = category,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    )
                    list.forEach { p ->
                        PlaybookCard(p) {
                            scope.launch {
                                playbooks.deleteUserPlaybook(p.id)
                                refresh()
                            }
                        }
                    }
                }
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(R.string.float_playbook_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        }
    }

    if (adding) {
        AddPlaybookDialog(
            existingCategories = categories,
            onDismiss = { adding = false },
            onSave = { category, scenario, tags, content ->
                adding = false
                scope.launch {
                    playbooks.addUserPlaybook(category, scenario, tags, content)
                    refresh()
                }
            },
        )
    }
}

@Composable
private fun PlaybookCard(p: PlaybookEntity, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = p.scenario,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (p.source == SOURCE_BUILTIN) {
                            R.string.float_playbook_builtin
                        } else {
                            R.string.float_playbook_custom
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            p.tags.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = p.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (p.source != SOURCE_BUILTIN) {
                Text(
                    text = stringResource(R.string.float_playbook_delete),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable(onClick = onDelete)
                        .padding(4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPlaybookDialog(
    existingCategories: List<String>,
    onDismiss: () -> Unit,
    onSave: (category: String, scenario: String, tags: String, content: String) -> Unit,
) {
    var category by remember { mutableStateOf(existingCategories.firstOrNull().orEmpty()) }
    var scenario by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val canSave = category.isNotBlank() && scenario.isNotBlank() && content.isNotBlank()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.float_playbook_add),
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    existingCategories.forEach { c ->
                        FilterChip(
                            selected = c == category,
                            onClick = { category = c },
                            label = { Text(c) },
                        )
                    }
                }
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(stringResource(R.string.float_playbook_field_category)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = scenario,
                    onValueChange = { scenario = it },
                    label = { Text(stringResource(R.string.float_playbook_field_scenario)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.float_playbook_field_tags)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.float_playbook_field_content)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Button(enabled = canSave, onClick = { onSave(category, scenario, tags, content) }) {
                        Text(stringResource(R.string.float_profile_save))
                    }
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.float_profile_cancel))
                    }
                }
            }
        }
    }
}

private const val SOURCE_BUILTIN = "builtin"

private fun Context.withPlaybookLocale(locale: Locale): Context {
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}
