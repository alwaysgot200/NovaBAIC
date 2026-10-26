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

package com.verlintas.baic2.floatball.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 内置安装结果：展示层只映射文案，不解析异常字符串。 */
sealed interface ShizukuInstallOutcome {
    /** 已安装且版本不低于内置包，无需重复安装。 */
    data object AlreadyInstalled : ShizukuInstallOutcome

    /** 未授予「安装未知应用」，已跳系统开关页，用户返回后再点一次即可。 */
    data object PermissionRequired : ShizukuInstallOutcome

    /** 已拉起系统安装器，等待用户手动确认。 */
    data class Started(val versionName: String?) : ShizukuInstallOutcome

    /** 未能发起安装。 */
    data class Failed(val reason: Failure) : ShizukuInstallOutcome

    enum class Failure { ASSET_MISSING, ASSET_CORRUPT, LAUNCH_FAILED }
}

/** 版本三元组（[code] 用于比较，[name] 用于展示）。 */
data class ShizukuVersion(val code: Long, val name: String?)

/**
 * 内置 Shizuku 安装（docs/2026-10-26-内置安装Shizuku方案.md）：
 * assets/shizuku.apk → 复制到 `cacheDir/shizuku_installer/` → 校验包名/版本 →
 * FileProvider 转 content:// → 系统安装器弹窗 → 用户手动确认。
 *
 * 无 root/系统签名时静默安装不可行，本类只负责“把系统安装弹窗调起来”；
 * 复制每次都以 assets 为准（2.5 MB，毫秒级），避免 cache 陈旧导致装到旧版。
 */
@Singleton
class ShizukuInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 只读缓存：给 UI 做「已装版本 vs 内置版本」比较用，不影响真正安装时的重新复制。 */
    @Volatile
    private var cachedBundled: ShizukuVersion? = null

    /** 内置包版本；assets 缺失或损坏返回 null（UI 退化为只判断已装/未装）。 */
    suspend fun bundledVersion(): ShizukuVersion? = withContext(Dispatchers.IO) {
        cachedBundled?.let { return@withContext it }
        val apk = cacheFile()
        val info = readArchive(apk) ?: runCatching {
            copyAsset(apk)
            readArchive(apk)
        }.getOrNull()
        info?.toVersion()?.also { cachedBundled = it }
    }

    /**
     * 执行内置安装：已装且不比内置旧 → [ShizukuInstallOutcome.AlreadyInstalled]；
     * 未开「安装未知应用」→ 跳设置并返回 [ShizukuInstallOutcome.PermissionRequired]；
     * 否则拉起系统安装器，用户确认后才真正安装。
     */
    suspend fun install(): ShizukuInstallOutcome = withContext(Dispatchers.IO) {
        val apk = cacheFile()
        runCatching { copyAsset(apk) }
            .getOrElse { return@withContext ShizukuInstallOutcome.Failed(ShizukuInstallOutcome.Failure.ASSET_MISSING) }
        // 复制后立刻校验：包名不符或解析失败视为内置包损坏，删除避免误装。
        val bundled = readArchive(apk)?.toVersion()
            ?: run {
                apk.delete()
                return@withContext ShizukuInstallOutcome.Failed(ShizukuInstallOutcome.Failure.ASSET_CORRUPT)
            }
        val installed = installedVersion()
        if (installed != null && installed.code >= bundled.code) {
            return@withContext ShizukuInstallOutcome.AlreadyInstalled
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            openUnknownSourcesSettings()
            return@withContext ShizukuInstallOutcome.PermissionRequired
        }
        if (!launchInstaller(apk)) {
            return@withContext ShizukuInstallOutcome.Failed(ShizukuInstallOutcome.Failure.LAUNCH_FAILED)
        }
        ShizukuInstallOutcome.Started(bundled.name)
    }

    /** 已安装的 Shizuku 版本；未装返回 null。 */
    fun installedVersion(): ShizukuVersion? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
    }.getOrNull()?.toVersion()

    private fun cacheFile(): File =
        File(context.cacheDir, INSTALLER_DIR).apply { mkdirs() }
            .resolve(ASSET_NAME)

    /** 以 assets 原文覆盖缓存文件（先写 .tmp 再改名，避免半截文件被系统安装器读到）。 */
    private fun copyAsset(target: File) {
        val tmp = File(target.parentFile, "$ASSET_NAME.tmp")
        context.assets.open(ASSET_NAME).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("rename failed")
            }
        }
    }

    /** 解析 APK 归档信息并校验包名（防止内置包被替换成别的应用）。 */
    private fun readArchive(file: File): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
    }.getOrNull()?.takeIf { it.packageName == MANAGER_PACKAGE && file.length() > 0L }

    private fun PackageInfo.toVersion(): ShizukuVersion = ShizukuVersion(
        code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        },
        name = versionName,
    )

    private fun openUnknownSourcesSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        candidates.forEach { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    private fun launchInstaller(apk: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}$PROVIDER_SUFFIX", apk)
        }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        const val ASSET_NAME = "shizuku.apk"
        const val INSTALLER_DIR = "shizuku_installer"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val PROVIDER_SUFFIX = ".shizukuinstaller"
        const val BUFFER_SIZE = 64 * 1024
    }
}
