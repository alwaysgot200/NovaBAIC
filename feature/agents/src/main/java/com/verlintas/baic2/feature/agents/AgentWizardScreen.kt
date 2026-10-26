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

package com.verlintas.baic2.feature.agents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.core.data.gateway.GatewayMode
import com.verlintas.baic2.designsystem.Baic2Mono
import com.verlintas.baic2.designsystem.Baic2Spacing
import kotlinx.coroutines.delay

/**
 * 模型设置页（选定模型后只编辑通用参数）：Max/精细度、温度、思考、系统提示词。
 *
 * 模型、网关地址与凭证全部来自服务端目录 + 卡密，界面不出现服务商/Key/上游地址。
 */
@Composable
fun AgentWizardScreen(
    modelKey: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(modelKey) { viewModel.startForModel(modelKey) }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            delay(900)
            onClose()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsTopPadding()
                .imePadding(),
        ) {
            WizardTopBar(state = state, onClose = onClose)
            StepIndicator(step = state.step)

            AnimatedContent(
                targetState = state.step,
                transitionSpec = { stepTransition(targetState.ordinal > initialState.ordinal) },
                label = "model-settings-step",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { step ->
                when (step) {
                    WizardStep.PARAMS -> ParamsStep(state, viewModel)
                    WizardStep.PROMPT -> PromptStep(state, viewModel)
                }
            }

            WizardBottomBar(state = state, viewModel = viewModel)
        }

        if (state.saved) {
            SuccessOverlay()
        }
    }
}

private fun stepTransition(forward: Boolean) =
    (slideInHorizontally(animationSpec = spring(stiffness = 380f)) { if (forward) it / 6 else -it / 6 } +
        fadeIn(tween(200)))
        .togetherWith(
            slideOutHorizontally(animationSpec = spring(stiffness = 380f)) { if (forward) -it / 6 else it / 6 } +
                fadeOut(tween(140)),
        )

@Composable
private fun Modifier.windowInsetsTopPadding(): Modifier =
    this.padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())

@Composable
private fun WizardTopBar(state: AgentWizardUiState, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Baic2Spacing.sm, vertical = Baic2Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.wizard_close),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.displayName.ifBlank { stringResource(R.string.wizard_title_edit) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (state.coefficientLabel.isBlank()) {
                    stringResource(state.step.titleRes())
                } else {
                    "${state.modelKey} · ${state.coefficientLabel}"
                },
                style = Baic2Mono.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun WizardStep.titleRes(): Int = when (this) {
    WizardStep.PARAMS -> R.string.wizard_step_tuning
    WizardStep.PROMPT -> R.string.wizard_step_prompt
}

@Composable
private fun StepIndicator(step: WizardStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Baic2Spacing.xl, vertical = Baic2Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.xs),
    ) {
        WizardStep.entries.forEach { entry ->
            Box(
                modifier = Modifier
                    .weight(if (entry == step) 2f else 1f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(
                        if (entry.ordinal <= step.ordinal) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun ParamsStep(state: AgentWizardUiState, viewModel: AgentWizardViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Baic2Spacing.xl,
            end = Baic2Spacing.xl,
            top = Baic2Spacing.md,
            bottom = Baic2Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
    ) {
        if (state.loading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Baic2Spacing.sm))
                    Text(stringResource(R.string.wizard_models_loading), style = MaterialTheme.typography.bodySmall)
                }
            }
            return@LazyColumn
        }
        state.loadError?.let { error ->
            item(key = "load-error") {
                WizardErrorCard(stringResource(R.string.wizard_models_error, error))
            }
        }
        if (!state.cardBound) {
            item(key = "no-card") { WizardNoticeCard(stringResource(R.string.wizard_no_card)) }
        }
        item(key = "max") {
            SettingSwitchRow(
                title = stringResource(R.string.wizard_max_mode),
                subtitle = stringResource(
                    R.string.wizard_max_mode_desc,
                    state.modes.firstOrNull { it.key == "max" }?.maxTokens ?: AgentWizardUiState.DEFAULT_MAX_TOKENS,
                ),
                checked = state.maxMode,
                onCheckedChange = viewModel::setMaxMode,
            )
        }
        if (!state.maxMode) {
            item(key = "precision") {
                Column {
                    Text(
                        text = stringResource(R.string.wizard_output_precision),
                        style = Baic2Mono.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Baic2Spacing.xs),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.sm)) {
                        items(state.modes.filter { it.key != "max" }, key = { it.key }) { mode: GatewayMode ->
                            FilterChip(
                                selected = state.selectedModeKey == mode.key,
                                onClick = { viewModel.selectMode(mode.key) },
                                shape = RoundedCornerShape(10.dp),
                                label = { Text("${mode.label} · ${mode.maxTokens}") },
                            )
                        }
                    }
                }
            }
        }
        item(key = "temperature") {
            Column {
                Text(
                    text = stringResource(R.string.wizard_temperature, (state.temperature * 100).toInt()),
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.temperature,
                    onValueChange = viewModel::updateTemperature,
                    valueRange = 0f..2f,
                    steps = 19,
                )
            }
        }
        if (state.supportsReasoning) {
            item(key = "reasoning") {
                SettingSwitchRow(
                    title = stringResource(R.string.wizard_reasoning),
                    subtitle = stringResource(R.string.wizard_reasoning_desc),
                    checked = state.reasoning,
                    onCheckedChange = viewModel::updateReasoning,
                )
            }
        }
        item(key = "summary") {
            Text(
                text = stringResource(R.string.wizard_max_tokens) + " · " + state.resolvedMaxTokens,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { error ->
            item(key = "error") { WizardErrorCard(setupErrorText(error)) }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PromptStep(state: AgentWizardUiState, viewModel: AgentWizardViewModel) {
    val templates = listOf(
        stringResource(R.string.wizard_prompt_tpl_general),
        stringResource(R.string.wizard_prompt_tpl_translate),
        stringResource(R.string.wizard_prompt_tpl_code),
        stringResource(R.string.wizard_prompt_tpl_concise),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Baic2Spacing.xl),
    ) {
        Text(
            text = stringResource(R.string.wizard_prompt_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Baic2Spacing.sm))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.sm)) {
            items(templates) { template ->
                FilterChip(
                    selected = state.systemPrompt == template,
                    onClick = { viewModel.updateSystemPrompt(template) },
                    shape = RoundedCornerShape(10.dp),
                    label = { Text(template.take(10)) },
                )
            }
        }
        OutlinedTextField(
            value = state.systemPrompt,
            onValueChange = viewModel::updateSystemPrompt,
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text(stringResource(R.string.agent_setup_system_prompt_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
                .padding(top = Baic2Spacing.md),
        )
        state.error?.let { error ->
            Spacer(Modifier.height(Baic2Spacing.md))
            WizardErrorCard(setupErrorText(error))
        }
        Spacer(Modifier.height(Baic2Spacing.xl))
    }
}

@Composable
private fun WizardBottomBar(state: AgentWizardUiState, viewModel: AgentWizardViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Baic2Spacing.xl, vertical = Baic2Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
    ) {
        if (state.step == WizardStep.PROMPT) {
            OutlinedButton(
                onClick = viewModel::back,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.wizard_back))
            }
        }
        Button(
            onClick = viewModel::next,
            enabled = !state.saving && !state.loading,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(2f),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = stringResource(
                        if (state.step == WizardStep.PROMPT) R.string.agent_setup_save else R.string.wizard_next,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SuccessOverlay() {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 300f),
        label = "settings-success",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size((36 * scale).dp),
                )
            }
            Spacer(Modifier.height(Baic2Spacing.lg))
            Text(
                text = stringResource(R.string.wizard_saved),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WizardNoticeCard(text: String) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), shape)
            .padding(Baic2Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Baic2Spacing.sm))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun WizardErrorCard(text: String) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), shape)
            .padding(Baic2Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Baic2Spacing.sm))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun setupErrorText(error: SetupError): String = when (error) {
    SetupError.MissingCard -> stringResource(R.string.wizard_no_card)
    SetupError.MissingModel -> stringResource(R.string.wizard_models_empty)
    SetupError.KeyStore -> stringResource(R.string.agent_setup_error_keystore)
}
