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

import android.content.Context

/**
 * 云端脚本来源映射（多产品方案 §4.10.4）：云端 scriptId → 本机脚本 id。
 * 「已安装」判定与安装去重按 scriptId 而非标题（改名/重名不误判，也不会因
 * `importBytes` 撞 id 取新 id 而重复安装）；无记录的旧脚本由调用方回退标题匹配。
 * 键按 [RpaUserScope.currentUserId] 分区，多账号互不可见。
 */
class RpaCloudSourceIndex(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    /** 云端 [cloudScriptId] 对应的本机脚本 id；无记录返回 null。 */
    fun localIdFor(cloudScriptId: Long): String? =
        prefs.getString(key(cloudScriptId), null)?.takeIf { it.isNotBlank() }

    /** 安装成功后记录来源；重复安装覆盖为最新本机 id。 */
    fun record(cloudScriptId: Long, localScriptId: String) {
        prefs.edit().putString(key(cloudScriptId), localScriptId).apply()
    }

    private fun key(cloudScriptId: Long): String =
        "${RpaUserScope.currentUserId()}/$cloudScriptId"

    private companion object {
        const val PREFS_NAME = "float_rpa_cloud_sources"
    }
}
