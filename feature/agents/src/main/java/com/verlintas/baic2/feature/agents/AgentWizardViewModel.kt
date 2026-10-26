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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verlintas.baic2.core.data.gateway.AdvisorGateway
import com.verlintas.baic2.core.data.gateway.GatewayMode
import com.verlintas.baic2.core.data.gateway.GatewayModel
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.model.Agent
import com.verlintas.baic2.core.model.ProviderId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WizardStep {
    PARAMS,
    PROMPT,
}

data class AgentWizardUiState(
    val editingId: Long = 0L,
    val step: WizardStep = WizardStep.PARAMS,
    val modelKey: String = "",
    val displayName: String = "",
    val coefficientLabel: String = "",
    val supportsReasoning: Boolean = false,
    /** 服务端下发的绝对网关地址（用户不可见、不可改）。 */
    val baseUrl: String = "",
    /** 输出 tokens 拉满（Max 模式）；关闭后按精细度档位选择。 */
    val maxMode: Boolean = true,
    val selectedModeKey: String = "standard",
    val modes: List<GatewayMode> = emptyList(),
    val temperature: Float = 0.7f,
    val reasoning: Boolean = false,
    val systemPrompt: String = "",
    val cardBound: Boolean = false,
    val loading: Boolean = true,
    val loadError: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: SetupError? = null,
) {
    /** 最终落库的最大输出 tokens。 */
    val resolvedMaxTokens: Int
        get() = if (maxMode) {
            modes.firstOrNull { it.key == "max" }?.maxTokens ?: DEFAULT_MAX_TOKENS
        } else {
            modes.firstOrNull { it.key == selectedModeKey }?.maxTokens
                ?: modes.firstOrNull { it.key != "max" }?.maxTokens
                ?: (DEFAULT_MAX_TOKENS / 2)
        }

    companion object {
        const val DEFAULT_MAX_TOKENS = 4096
    }
}

sealed interface SetupError {
    data object MissingCard : SetupError

    data object MissingModel : SetupError

    data object KeyStore : SetupError
}

/**
 * 模型设置（服务端模型目录选定后只编辑参数）：Max/精细度、温度、思考、系统提示词。
 *
 * 不再有服务商、API Key、上游地址；保存时模型、地址、凭证全部由服务端目录 + 卡密决定。
 */
@HiltViewModel
class AgentWizardViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val gateway: AdvisorGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentWizardUiState())
    val uiState: StateFlow<AgentWizardUiState> = _uiState.asStateFlow()

    fun startForModel(modelKey: String) {
        _uiState.value = AgentWizardUiState(modelKey = modelKey)
        viewModelScope.launch {
            val cardKey = runCatching { gateway.cardKey() }.getOrNull()
            runCatching { gateway.config() }
                .onSuccess { config ->
                    val model = config.models.firstOrNull { it.key == modelKey }
                        ?: config.models.firstOrNull { it.key == config.defaultModel }
                        ?: config.models.firstOrNull()
                    if (model == null) {
                        _uiState.update {
                            it.copy(loading = false, loadError = "no_models", cardBound = !cardKey.isNullOrBlank())
                        }
                        return@launch
                    }
                    val existing = agentRepository.getDefaultAgent()?.takeIf { it.model == model.key }
                    val modes = normalizeModes(model)
                    val maxModeTokens = modes.firstOrNull { it.key == "max" }?.maxTokens ?: AgentWizardUiState.DEFAULT_MAX_TOKENS
                    val existingMax = existing?.maxTokens
                    val maxMode = existingMax == null || existingMax >= maxModeTokens
                    _uiState.update {
                        it.copy(
                            modelKey = model.key,
                            displayName = model.displayName,
                            coefficientLabel = model.coefficientLabel,
                            supportsReasoning = model.supportsReasoning,
                            baseUrl = config.baseUrl,
                            modes = modes,
                            maxMode = maxMode,
                            selectedModeKey = if (maxMode) "standard" else nearestModeKey(modes, existingMax),
                            temperature = existing?.temperature?.toFloat() ?: 0.7f,
                            reasoning = (existing?.reasoning ?: false) && model.supportsReasoning,
                            systemPrompt = existing?.systemPrompt.orEmpty(),
                            editingId = existing?.id ?: 0L,
                            cardBound = !cardKey.isNullOrBlank(),
                            loading = false,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            loadError = error.message ?: error.javaClass.simpleName,
                            cardBound = !cardKey.isNullOrBlank(),
                        )
                    }
                }
        }
    }

    fun setMaxMode(enabled: Boolean) = _uiState.update { it.copy(maxMode = enabled, error = null) }

    fun selectMode(modeKey: String) = _uiState.update {
        it.copy(selectedModeKey = modeKey, maxMode = false, error = null)
    }

    fun updateTemperature(value: Float) = _uiState.update { it.copy(temperature = value) }

    fun updateReasoning(value: Boolean) = _uiState.update { it.copy(reasoning = value) }

    fun updateSystemPrompt(value: String) = _uiState.update { it.copy(systemPrompt = value) }

    fun next() {
        when (_uiState.value.step) {
            WizardStep.PARAMS -> _uiState.update { it.copy(step = WizardStep.PROMPT, error = null) }
            WizardStep.PROMPT -> save()
        }
    }

    fun back() {
        if (_uiState.value.step == WizardStep.PROMPT) {
            _uiState.update { it.copy(step = WizardStep.PARAMS, error = null) }
        }
    }

    private fun save() {
        val current = _uiState.value
        if (current.modelKey.isBlank()) {
            _uiState.update { it.copy(error = SetupError.MissingModel) }
            return
        }
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val cardKey = runCatching { gateway.cardKey() }.getOrNull()
            val existing = agentRepository.getDefaultAgent()
            val existingKey = existing?.id?.let { id ->
                runCatching { agentRepository.resolveConfig(id) }.getOrNull()?.apiKey
            }
            val apiKey = cardKey ?: existingKey
            if (apiKey.isNullOrBlank()) {
                _uiState.update { it.copy(saving = false, error = SetupError.MissingCard) }
                return@launch
            }
            try {
                val agent = Agent(
                    id = existing?.id ?: 0L,
                    name = current.displayName.ifBlank { current.modelKey },
                    provider = ProviderId.OPENAI_COMPATIBLE,
                    baseUrl = current.baseUrl.trim().trimEnd('/'),
                    model = current.modelKey,
                    temperature = current.temperature.toDouble(),
                    maxTokens = current.resolvedMaxTokens,
                    reasoning = current.reasoning,
                    systemPrompt = current.systemPrompt.trim(),
                    isDefault = true,
                )
                if (existing == null) {
                    agentRepository.save(agent, apiKey)
                } else {
                    agentRepository.update(agent, apiKey.takeIf { it != existingKey })
                }
                _uiState.update { it.copy(saving = false, saved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(saving = false, error = SetupError.KeyStore) }
            }
        }
    }

    /** 服务端未给档位时兜底（Max / 标准 / 精省）。 */
    private fun normalizeModes(model: GatewayModel): List<GatewayMode> {
        if (model.modes.isNotEmpty()) return model.modes
        val max = model.maxMode?.maxTokens ?: AgentWizardUiState.DEFAULT_MAX_TOKENS
        return listOf(
            GatewayMode("max", "Max", max),
            GatewayMode("standard", "标准", maxOf(512, max / 2)),
            GatewayMode("economy", "精省", maxOf(256, max / 8)),
        )
    }

    /** 现有 maxTokens 最接近的档位（编辑历史配置时回显）。 */
    private fun nearestModeKey(modes: List<GatewayMode>, maxTokens: Int?): String {
        val candidates = modes.filter { it.key != "max" }
        if (candidates.isEmpty()) return "standard"
        if (maxTokens == null) return candidates.first().key
        return candidates.minByOrNull { kotlin.math.abs(it.maxTokens - maxTokens) }?.key
            ?: candidates.first().key
    }
}
