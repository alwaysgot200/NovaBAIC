/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this
 * program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor.strategy

import com.verlintas.baic2.floatball.advisor.PersonaFieldDef
import com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity

/**
 * 画像字段裁剪候选项（方案 P3 §十一）：[displayed] 已由调用方把 code/`~x` 转成显示文本。
 */
data class PersonaCandidate(
    val def: PersonaFieldDef,
    val entity: PersonaFieldEntity,
    val displayed: String,
)

/** 选中的画像字段：[stale]=true 时调用方渲染「早期信息，可能已变化，请先验证」措辞。 */
data class SelectedPersona(
    val candidate: PersonaCandidate,
    val stale: Boolean,
)

/**
 * 人设段字段裁剪纯函数（P3 §十一，单测覆盖）：
 *
 * 1. [PROTECTED_KEYS]（禁忌/红线）永不裁剪，优先级最高；
 * 2. 当前站信号与字段 label/选项 label 弱关联命中的字段优先（关键词表随资产走）；
 * 3. 其次 salience high > normal > low，同档 [PersonaFieldEntity.updatedAt] 新者优先；
 * 4. 总数 ≤ [limit]（保护字段本身超额时以保护字段为准，红线不裁）；
 * 5. 超过 [PersonaFieldDef.staleAfterDays] 的字段保留但标 [SelectedPersona.stale]，不删除。
 */
object PersonaSelector {

    /** 永不裁剪的红线字段（their taboos / my taboos / 群内禁忌）。 */
    val PROTECTED_KEYS: Set<String> = setOf("taboos", "my_taboo", "groupTaboos")

    const val DEFAULT_LIMIT = 6
    const val STALE_NOTE = "早期信息，可能已变化，请先验证"
    const val DAY_MS = 86_400_000L

    /**
     * @param signals 当前站 advanceSignals + retreatSignals 原文（弱关联匹配原料）。
     */
    fun select(
        candidates: List<PersonaCandidate>,
        signals: Collection<String>,
        limit: Int = DEFAULT_LIMIT,
        now: Long = System.currentTimeMillis(),
    ): List<SelectedPersona> {
        val protected = candidates.filter { it.def.key in PROTECTED_KEYS }
        val others = candidates.filterNot { it.def.key in PROTECTED_KEYS }
            .sortedWith(
                compareByDescending<PersonaCandidate> { isRelevant(it, signals) }
                    .thenBy { salienceRank(it.def.salience) }
                    .thenByDescending { it.entity.updatedAt },
            )
        val picked = if (protected.size >= limit) {
            protected
        } else {
            protected + others.take(limit - protected.size)
        }
        return picked.map { c ->
            val days = c.def.staleAfterDays
            val stale = days != null && now - c.entity.updatedAt > days * DAY_MS
            SelectedPersona(c, stale)
        }
    }

    /**
     * 弱关联：任一信号短语包含字段 label 或其码表选项 label（长度 ≥2 才参与），即判相关。
     * 关联词全部来自画像资产（label/options），不硬编码业务词。
     */
    internal fun isRelevant(candidate: PersonaCandidate, signals: Collection<String>): Boolean {
        if (signals.isEmpty()) return false
        val words = linkedSetOf<String>().apply {
            candidate.def.label.takeIf { it.length >= 2 }?.let(::add)
            candidate.def.options.orEmpty().forEach { opt ->
                opt.label.takeIf { it.length >= 2 }?.let(::add)
            }
        }
        if (words.isEmpty()) return false
        return signals.any { signal ->
            val s = signal.trim()
            s.isNotEmpty() && words.any { it in s }
        }
    }

    private fun salienceRank(salience: String?): Int = when (salience) {
        PersonaFieldDef.SALIENCE_HIGH -> 0
        PersonaFieldDef.SALIENCE_NORMAL -> 1
        else -> 2
    }
}
