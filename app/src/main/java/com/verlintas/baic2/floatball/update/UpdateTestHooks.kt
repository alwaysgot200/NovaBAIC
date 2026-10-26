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
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.update

import com.verlintas.baic2.BuildConfig

/**
 * 升级测试开关（仅 debug 生效）：
 * `local.properties` 配 `dev.updateManifestUrl=http://.../manifest.json` 后，
 * 强制更新/版本检查才在 debug 构建启用；release 恒走生产地址。
 */
internal object UpdateTestHooks {

    fun manifestUrl(): String? {
        if (!BuildConfig.DEBUG) return null
        return BuildConfig.DEV_UPDATE_MANIFEST_URL.takeIf { it.isNotBlank() }
    }

    fun manifestBaseUrl(): String =
        manifestUrl()
            ?.substringBeforeLast('/')
            ?.plus('/')
            ?: "${BuildConfig.SERVER_ORIGIN.trimEnd('/')}/download/haocanmou/"

    /** release 恒启用；debug 只有配了测试 manifest 才启用，避免本地开发被强制拦截。 */
    fun updateCheckEnabled(): Boolean = !BuildConfig.DEBUG || manifestUrl() != null

    fun forcedGateEnabled(): Boolean = updateCheckEnabled()
}
