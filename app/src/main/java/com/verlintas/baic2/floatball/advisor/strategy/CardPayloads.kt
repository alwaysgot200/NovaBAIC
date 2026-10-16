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
 * 策略卡 payload 的编解码与重编合并（纯函数，§6.4）。
 * 重编时 source=user 的 CardItem 按 text 指纹保留并插到新 ai 列表前部，ai 项全换；
 * roleLine 被用户改过（roleLineSource=user）则不被覆盖。
 */
object CardPayloads {

    fun parse(json: String?): StrategyCardPayload? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            strategyJson.decodeFromString(StrategyCardPayload.serializer(), json)
        }.getOrNull()
    }

    fun encode(payload: StrategyCardPayload): String =
        strategyJson.encodeToString(StrategyCardPayload.serializer(), payload)

    /**
     * 用 [newAi]（编译器新草稿）替换旧卡 [old] 中的 ai 内容，保留用户改写。
     * 四个分区各自独立合并；用户句若与新 ai 句文本撞指纹则不重复插入。
     */
    fun mergeRetained(
        old: StrategyCardPayload?,
        newAi: StrategyCardPayload,
    ): StrategyCardPayload {
        if (old == null) return newAi
        val roleLineKept = old.roleLineSource == CardItem.SOURCE_USER && old.roleLine.isNotBlank()
        return newAi.copy(
            roleLine = if (roleLineKept) old.roleLine else newAi.roleLine,
            roleLineSource = if (roleLineKept) CardItem.SOURCE_USER else newAi.roleLineSource,
            selfConstraints = mergeSection(old.selfConstraints, newAi.selfConstraints),
            theirImplications = mergeSection(old.theirImplications, newAi.theirImplications),
            topicBank = mergeSection(old.topicBank, newAi.topicBank),
            taboos = mergeSection(old.taboos, newAi.taboos),
        )
    }

    private fun mergeSection(oldItems: List<CardItem>, newItems: List<CardItem>): List<CardItem> {
        val newTexts = newItems.map { it.text.trim() }.toSet()
        val retained = oldItems
            .asSequence()
            .filter { it.source == CardItem.SOURCE_USER }
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() && it.text !in newTexts }
            .distinctBy { it.text }
            .toList()
        return retained + newItems
    }
}
