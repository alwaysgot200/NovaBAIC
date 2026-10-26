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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface InstallOutcome {
    /** 已拉起系统安装器，等待用户在系统弹窗确认。 */
    data object Started : InstallOutcome

    /** 未授予“安装未知应用”，已跳系统设置，返回后可重试。 */
    data object PermissionRequired : InstallOutcome

    data class Failure(val reason: UpdateFailure) : InstallOutcome
}

/**
 * 覆盖安装前做三重校验：包名、versionCode、签名证书与当前安装包完全一致。
 * 任一不符都不拉起安装器——尤其签名不一致会触发系统 UPDATE_INCOMPATIBLE，
 * 用户只能卸载重装（丢数据），必须提前拦截。
 */
@Singleton
class AppUpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun install(apk: File, manifest: UpdateManifest): InstallOutcome {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archive = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        }.getOrNull() ?: return InstallOutcome.Failure(UpdateFailure.ARCHIVE_INVALID)

        if (archive.packageName != context.packageName) {
            return InstallOutcome.Failure(UpdateFailure.PACKAGE_MISMATCH)
        }

        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }
            .getOrNull()
            ?: return InstallOutcome.Failure(UpdateFailure.ARCHIVE_INVALID)

        if (archive.versionName != null && manifest.version.isNotBlank() &&
            archive.versionName != manifest.version
        ) {
            return InstallOutcome.Failure(UpdateFailure.VERSION_INVALID)
        }
        if (versionCode(archive) <= versionCode(installed)) {
            return InstallOutcome.Failure(UpdateFailure.VERSION_INVALID)
        }

        val archiveSigners = signatureDigests(archive)
        val installedSigners = signatureDigests(installed)
        if (archiveSigners.isEmpty() || installedSigners.isEmpty() || archiveSigners != installedSigners) {
            return InstallOutcome.Failure(UpdateFailure.SIGNATURE_MISMATCH)
        }

        if (!pm.canRequestPackageInstalls()) {
            openUnknownSourcesSettings()
            return InstallOutcome.PermissionRequired
        }

        // 使用系统 PackageInstaller 会话安装：不经过 content:// / FileProvider，
        // 规避 MIUI/HyperOS 安装器中转后丢失 URI 授权（"provider is not exported"）的问题。
        return runCatching {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply { setSize(apk.length()) }
            val sessionId = pm.packageInstaller.createSession(params)
            pm.packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateInstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
            InstallOutcome.Started
        }.getOrElse {
            InstallOutcome.Failure(UpdateFailure.LAUNCH_FAILED)
        }
    }

    fun openUnknownSourcesSettings() {
        val candidates = listOf(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        candidates.forEach { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun signatureDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners ?: return emptySet()
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: return emptySet()
        }
        return signatures.map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
