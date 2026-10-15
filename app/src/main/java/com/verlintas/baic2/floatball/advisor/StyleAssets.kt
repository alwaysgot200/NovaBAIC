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

package com.verlintas.baic2.floatball.advisor

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 说话基调（单选）：[voiceRule] 是注入提示词的一句话行为准则。 */
@Serializable
data class StyleTone(
    val code: String,
    val label: String,
    val voiceRule: String,
)

/**
 * 本轮意图（多选）：do/dont 是行为级约束；good/bad 给模型的对照示例；
 * [fitStages] 为空表示全阶段适用，否则只在所列阶段渲染（P1 调用传 stageCode=null，不过滤）。
 */
@Serializable
data class StyleIntent(
    val code: String,
    val label: String,
    val `do`: List<String> = emptyList(),
    val dont: List<String> = emptyList(),
    val good: String = "",
    val bad: String = "",
    val fitStages: List<String>? = null,
)

@Serializable
data class StylePlaybook(
    val tones: List<StyleTone> = emptyList(),
    val intents: List<StyleIntent> = emptyList(),
) {
    fun tone(code: String?): StyleTone? = code?.let { c -> tones.firstOrNull { it.code == c } }

    fun intent(code: String): StyleIntent? = intents.firstOrNull { it.code == code }
}

/** 场景目标模板（P2 目标体系消费；P1 随资产加载）。 */
@Serializable
data class ScenarioGoalTemplate(
    val horizon: Int = 0,
    val text: String,
)

/** 场景阶段定义：advanceSignals 推进信号；missionHint 阶段任务提示（P2 阶段机消费）。 */
@Serializable
data class ScenarioStage(
    val code: String,
    val label: String,
    val advanceSignals: List<String> = emptyList(),
    val missionHint: String = "",
)

/**
 * 场景包：P1 只用 label/relationTypes/defaultTone/defaultIntents（一键预填风格卡）；
 * stages/retreatSignals/taboos/goalTemplates 资产先行，P2 阶段机与红线门消费。
 */
@Serializable
data class ScenarioPack(
    val code: String,
    val label: String,
    val relationTypes: List<String> = emptyList(),
    val defaultTone: String? = null,
    val defaultIntents: List<String> = emptyList(),
    val goalTemplates: List<ScenarioGoalTemplate> = emptyList(),
    val stages: List<ScenarioStage> = emptyList(),
    val retreatSignals: List<String> = emptyList(),
    val taboos: List<String> = emptyList(),
)

@Serializable
data class ScenarioPackBundle(
    val packs: List<ScenarioPack> = emptyList(),
) {
    fun byCode(code: String?): ScenarioPack? = code?.let { c -> packs.firstOrNull { it.code == c } }
}

/** 风格基调/意图与场景包资产加载（与 PersonaSchemas 同范式：assets JSON + 进程内缓存，不入库）。 */
@Singleton
class StyleAssets @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cachedPlaybook: StylePlaybook? = null

    @Volatile
    private var cachedPacks: ScenarioPackBundle? = null

    fun playbook(): StylePlaybook =
        cachedPlaybook ?: load<StylePlaybook>(PLAYBOOK_ASSET).also { cachedPlaybook = it }

    fun scenarioPacks(): ScenarioPackBundle =
        cachedPacks ?: load<ScenarioPackBundle>(PACKS_ASSET).also { cachedPacks = it }

    private inline fun <reified T> load(assetPath: String): T =
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
            json.decodeFromString(reader.readText())
        }

    private companion object {
        const val PLAYBOOK_ASSET = "advisor/style_playbook.json"
        const val PACKS_ASSET = "advisor/scenario_packs.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
