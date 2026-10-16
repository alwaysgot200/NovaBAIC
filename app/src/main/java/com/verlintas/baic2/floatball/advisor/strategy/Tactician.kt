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

package com.verlintas.baic2.floatball.advisor.strategy

/**
 * 临场指挥：<plan> 块的宽松解析（§7.1）。
 * 模型输出永远不可信——围栏、解释文字、半截 JSON 都可能出现；
 * 解析失败一律返回 null（全文当普通回复，不写 mission、不报错，降级矩阵）。
 */
object Tactician {

    private val fenceRegex = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$", RegexOption.IGNORE_CASE)

    /**
     * 解析 plan 原文：去代码围栏 → 截首对 `{}` → lenient 解码 → 规范化。
     * @return mission 非空的规范化 [MissionPlan]；任何异常或 mission 空均返回 null。
     */
    fun parsePlan(planRaw: String?): MissionPlan? {
        if (planRaw.isNullOrBlank()) return null
        var text = planRaw.trim()
        fenceRegex.matchEntire(text)?.let { text = it.groupValues[1].trim() }

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        val parsed = runCatching {
            strategyJson.decodeFromString(MissionPlan.serializer(), text.substring(start, end + 1))
        }.getOrNull() ?: return null

        val mission = parsed.mission.trim()
        if (mission.isEmpty()) return null
        return parsed.copy(
            mission = mission,
            actions = parsed.actions.clean(),
            forbidden = parsed.forbidden.clean(),
            signalsToWatch = parsed.signalsToWatch.clean(),
            successWhen = parsed.successWhen.trim(),
        )
    }

    private fun List<String>.clean(): List<String> =
        map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
