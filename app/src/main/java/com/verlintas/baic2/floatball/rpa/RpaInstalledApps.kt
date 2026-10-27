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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** 系统应用选择器的一行（包名 + 展示名）。 */
data class RpaInstalledApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
)

/**
 * 已安装且可启动的应用清单。
 *
 * 只用于属性面板的下拉选择，避免用户手抄包名写错。清单在 IO 线程加载后缓存；
 * 安装 / 卸载后需要重新打开选择器才可见（`forceRefresh` 可强制重读）。
 */
object RpaInstalledApps {

    @Volatile
    private var cache: List<RpaInstalledApp>? = null

    fun load(context: Context, forceRefresh: Boolean = false): List<RpaInstalledApp> {
        if (!forceRefresh) cache?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        val ownPackage = context.packageName
        val apps = resolved.asSequence()
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                val packageName = activity.packageName ?: return@mapNotNull null
                if (packageName == ownPackage) return@mapNotNull null
                val label = runCatching { info.loadLabel(pm)?.toString() }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
                val flags = activity.applicationInfo?.flags ?: 0
                RpaInstalledApp(
                    packageName = packageName,
                    label = label.ifBlank { packageName },
                    system = (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
        cache = apps
        return apps
    }

    fun find(context: Context, packageName: String): RpaInstalledApp? {
        if (packageName.isBlank()) return null
        return load(context).firstOrNull { it.packageName == packageName }
    }

    fun clearCache() {
        cache = null
    }
}
