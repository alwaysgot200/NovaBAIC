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
import com.verlintas.baic2.device.api.ShellBridge
import com.verlintas.baic2.device.api.ShellResult
import com.verlintas.baic2.device.api.ShellState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** 一次「投影静默授权」尝试的结果。 */
sealed interface ShizukuGrantResult {
    /** appops 已写入且复查确认为 allow。 */
    data object Granted : ShizukuGrantResult

    /** shell 失败：携带 stderr/原因原文，展示层映射文案（权限不足指回前置①）。 */
    data class Failed(val reason: String) : ShizukuGrantResult
}

/**
 * 投影免弹窗授权（docs/2026-10-21-投影免弹窗授权方案.md）：通过上游
 * [ShellBridge]（Shizuku，shell 权限）执行一次
 * `appops set <pkg> PROJECT_MEDIA allow`，此后 MediaProjection 系统授权框
 * 对本应用静默通过（HyperOS 真机实测；appop 状态持久，重启不清）。
 * 无障碍仍是感知首选；本门只服务 a11y 关闭时 RPA 双模执行面的投影通道。
 */
@Singleton
class ShizukuGate @Inject constructor(
    private val shell: ShellBridge,
    private val installer: ShizukuInstaller,
    @ApplicationContext private val context: Context,
) {
    val shellState: StateFlow<ShellState> = shell.state

    /** Shizuku 管理器 app 是否已安装（未装则引导下载）。 */
    fun isManagerInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
    }.isSuccess

    /** 已安装的管理器版本；未装返回 null。 */
    fun installedVersion(): ShizukuVersion? = installer.installedVersion()

    /** 内置安装包版本（决定按钮是「安装」还是「升级」）；assets 异常返回 null。 */
    suspend fun bundledVersion(): ShizukuVersion? = installer.bundledVersion()

    /**
     * 内置安装（docs/2026-10-26-内置安装Shizuku方案.md）：
     * 复制 assets/shizuku.apk → 校验 → 拉起系统安装器，用户手动确认后才落装。
     */
    suspend fun installBundled(): ShizukuInstallOutcome = installer.install()

    /** 打开 Shizuku 管理器（无线调试启动/配对入口）。 */
    fun launchManager(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 请求 Shizuku API 授权（弹系统对话框，批准后 [shellState] 自动流转 Ready）。 */
    fun requestShellPermission() = shell.requestPermission()

    /** PROJECT_MEDIA appop 是否已 allow（adb 或 Shizuku 设置过都算）。 */
    suspend fun projectionSilentGranted(): Boolean = withContext(Dispatchers.IO) {
        val out = queryAppop() ?: return@withContext false
        APPOP_ALLOW.find(out) != null
    }

    /** 一键授权：appops set + 复查确认；失败携带 shell 原文。 */
    suspend fun grantProjectionSilent(): ShizukuGrantResult = withContext(Dispatchers.IO) {
        when (val r = shell.exec("appops set ${context.packageName} PROJECT_MEDIA allow")) {
            is ShellResult.Output -> {
                val confirm = queryAppop()
                if (r.exitCode == 0 && confirm != null && APPOP_ALLOW.find(confirm) != null) {
                    ShizukuGrantResult.Granted
                } else {
                    ShizukuGrantResult.Failed(
                        listOf(r.stderr, r.stdout).firstOrNull { it.isNotBlank() }
                            ?: "exit=${r.exitCode}",
                    )
                }
            }
            is ShellResult.Unavailable -> ShizukuGrantResult.Failed(r.reason)
            is ShellResult.Timeout -> ShizukuGrantResult.Failed("timeout: ${r.partialOutput.take(200)}")
        }
    }

    private suspend fun queryAppop(): String? = when (
        val r = shell.exec("appops get ${context.packageName} PROJECT_MEDIA")
    ) {
        is ShellResult.Output -> listOf(r.stdout, r.stderr)
            .firstOrNull { it.isNotBlank() }?.takeIf { it.isNotBlank() }
        else -> null
    }

    private companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

        /** `appops get` 输出行形如 `PROJECT_MEDIA: allow; time=...`。 */
        val APPOP_ALLOW = Regex("""PROJECT_MEDIA:\s*allow\b""")
    }
}
