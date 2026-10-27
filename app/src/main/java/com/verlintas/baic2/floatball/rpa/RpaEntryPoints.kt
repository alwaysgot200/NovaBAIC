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
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import android.content.Context
import android.content.Intent

/**
 * RPA 工具的入口门面：悬浮菜单只依赖这里，不 import 具体页面内部。
 *
 * 以图找图 / 布局分析会在各自阶段补齐；当前只有已可用的 RPA 设计入口，
 * 避免菜单出现点了没反应的死入口。
 */
internal object RpaEntryPoints {

    /** 打开脚本库；[imPackage] 非空时只用于新建脚本时预填包名。 */
    fun design(context: Context, imPackage: String? = null): Intent =
        RpaActivity.intent(context, imPackage)
}
