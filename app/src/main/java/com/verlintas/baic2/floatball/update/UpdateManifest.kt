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

import kotlinx.serialization.Serializable

/** 服务器 manifest（`/download/haocanmou/manifest.json`）的客户端模型。 */
@Serializable
data class UpdateManifest(
    val schema: Int = 1,
    val product: String = "",
    val name: String = "",
    val packageName: String = "",
    val version: String = "",
    val versionCode: Long = 0L,
    val publishedAt: String = "",
    val notes: String = "",
    val source: UpdateSource? = null,
    val files: List<UpdateFile> = emptyList(),
    val history: List<UpdateHistoryEntry> = emptyList(),
)

@Serializable
data class UpdateSource(
    val repo: String = "",
    val tag: String = "",
    val releaseUrl: String = "",
)

@Serializable
data class UpdateFile(
    val name: String = "",
    val abi: String = "",
    val size: Long = 0L,
    val sha256: String = "",
    val url: String = "",
)

@Serializable
data class UpdateHistoryEntry(
    val version: String = "",
    val versionCode: Long = 0L,
    val publishedAt: String = "",
    val notes: String = "",
    val url: String = "",
    val size: Long = 0L,
    val sha256: String = "",
)

/** 三段版本号（只比较数字部分；`-dev.gxxxx` 等后缀忽略）。 */
data class SemVer(val major: Int, val minor: Int, val patch: Int) {
    companion object {
        fun parse(raw: String?): SemVer? {
            val base = raw.orEmpty().trim().substringBefore('-')
            val parts = base.split('.')
            if (parts.size != 3) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            return SemVer(nums[0], nums[1], nums[2])
        }
    }
}

/** 升级判定：从左到右逐段比较（第三段变小不提示）。 */
enum class UpdateDecision { NONE, OPTIONAL, FORCED }

fun decideUpdate(
    installed: SemVer,
    installedCode: Long,
    manifest: UpdateManifest,
): UpdateDecision {
    val server = SemVer.parse(manifest.version) ?: return UpdateDecision.NONE
    // 防降级/防同版重复提示：安装层 versionCode 必须严格更大。
    if (manifest.versionCode <= installedCode) return UpdateDecision.NONE
    return when {
        installed.major < server.major -> UpdateDecision.FORCED
        installed.major > server.major -> UpdateDecision.NONE
        installed.minor < server.minor -> UpdateDecision.OPTIONAL
        // 第一、二段相同，第三段变小也不打扰用户（按产品规则）。
        else -> UpdateDecision.NONE
    }
}

/** 更新失败的统一原因，展示层映射 strings.xml，不解析异常字符串。 */
enum class UpdateFailure {
    NETWORK,
    MANIFEST_INVALID,
    NO_SPACE,
    DOWNLOAD,
    SHA_MISMATCH,
    ARCHIVE_INVALID,
    PACKAGE_MISMATCH,
    VERSION_INVALID,
    SIGNATURE_MISMATCH,
    LAUNCH_FAILED,
}

/** `checkNow()` 的同步结果，供设置页提示文案使用。 */
enum class CheckOutcome { UP_TO_DATE, OPTIONAL, FORCED, FAILED }
