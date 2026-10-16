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
 * 目标模板占位符填充（纯函数，§5.2）：
 * - `{name}` → 联系人显示名（空则留填写位）；
 * - `{aspiration}` → 已确认理想自我标签（顿号拼接由调用方完成；空则「理想中的自己」）；
 * - 其他未知占位符（`{deadline}`/`{objection}` 等）→ 统一下划线填写位。
 */
object TemplateFill {

    const val BLANK = "＿＿＿＿"
    private const val FALLBACK_SELF = "理想中的自己"
    // 注意：结尾花括号必须转义——Android ICU 正则不接受裸 '}'（OpenJDK 可过，单测覆盖不到）。
    private val placeholderRegex = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")

    fun render(template: String, name: String, aspiration: String): String =
        placeholderRegex.replace(template) { match ->
            when (match.groupValues[1]) {
                "name" -> name.trim().ifEmpty { BLANK }
                "aspiration" -> aspiration.trim().ifEmpty { FALLBACK_SELF }
                else -> BLANK
            }
        }
}
