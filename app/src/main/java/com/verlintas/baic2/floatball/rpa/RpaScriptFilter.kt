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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import com.verlintas.rpa.model.RpaScript

/**
 * 脚本库纯内存过滤：脚本名 / 描述 / 目标包名，大小写不敏感。
 *
 * 空 query 原样返回；不排序、不改 `updatedAt`，列表顺序始终由 store 决定。
 */
internal fun filterRpaScripts(scripts: List<RpaScript>, query: String): List<RpaScript> {
    val needle = query.trim()
    if (needle.isEmpty()) return scripts
    return scripts.filter { script ->
        script.name.contains(needle, ignoreCase = true) ||
            script.description.contains(needle, ignoreCase = true) ||
            script.appPackage.contains(needle, ignoreCase = true)
    }
}
