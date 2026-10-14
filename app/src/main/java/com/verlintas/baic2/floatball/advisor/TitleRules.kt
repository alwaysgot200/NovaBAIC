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

package com.verlintas.baic2.floatball.advisor

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 标题规范化的壳后缀规则（方案 §11.1 ⑤；NFKC/角标/状态尾巴见 [ContactNames]）。
 * 资产缺失时回退内置微信/QQ 后缀。规则可热更新，不需发版。
 */
@Singleton
class TitleRules @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cached: Rules? = null

    fun get(): Rules = cached ?: load().also { cached = it }

    /**
     * 在已规范化（[ContactNames.normalize]）的匹配键上，按资产顺序尝试剥离
     * 壳后缀（企业微信→微信→QQ，长后缀先试），返回「原键 + 各剥离键」去重
     * 列表，原键始终在首位。剥离 [Rules.ambiguousSuffixes] 命中的调用方应
     * 在轻提示里说明（可能误剥名字本身含后缀的联系人）。
     */
    fun matchVariants(normalized: String): List<String> {
        val rules = get()
        val variants = ArrayList<String>()
        variants += normalized
        rules.stripSuffixes.sortedByDescending { it.length }.forEach { suffix ->
            if (normalized.endsWith(suffix) && normalized.length > suffix.length) {
                variants += normalized.removeSuffix(suffix)
            }
        }
        return variants.distinct()
    }

    private fun load(): Rules {
        val text = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        val parsed = text?.let {
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(TitleRulesFile.serializer(), it) }
                .getOrNull()
        }
        return Rules(
            stripSuffixes = parsed?.stripSuffixes?.takeIf { it.isNotEmpty() }
                ?: listOf("企业微信", "微信", "QQ"),
            ambiguousSuffixes = parsed?.ambiguousSuffixes ?: listOf("微信", "QQ"),
            renameDistance = parsed?.renameDistance?.takeIf { it in 1..4 } ?: 2,
        )
    }

    /** 已加载的规则集；[tailPatterns] 已并入 [ContactNames]，此处仅保留配置备查。 */
    data class Rules(
        val stripSuffixes: List<String>,
        val ambiguousSuffixes: List<String>,
        val renameDistance: Int,
    )

    @Serializable
    private data class TitleRulesFile(
        val stripSuffixes: List<String> = emptyList(),
        val tailPatterns: List<String> = emptyList(),
        val ambiguousSuffixes: List<String> = emptyList(),
        val renameDistance: Int? = null,
    )

    private companion object {
        const val ASSET_PATH = "advisor/title_rules.json"
    }
}
