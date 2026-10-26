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

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.verlintas.baic2.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class CheckFailed(val forced: Boolean) : UpdateState
    data class Optional(val manifest: UpdateManifest) : UpdateState
    data class Forced(val manifest: UpdateManifest) : UpdateState
    data class Downloading(
        val manifest: UpdateManifest,
        val downloaded: Long,
        val total: Long,
        val forced: Boolean,
    ) : UpdateState
    data class ReadyToInstall(
        val manifest: UpdateManifest,
        val apk: File,
        val forced: Boolean,
    ) : UpdateState
    data class PermissionRequired(
        val manifest: UpdateManifest,
        val apk: File,
        val forced: Boolean,
    ) : UpdateState
    data class Failed(
        val manifest: UpdateManifest?,
        val forced: Boolean,
        val reason: UpdateFailure,
    ) : UpdateState
}

/**
 * 版本检查与升级编排（好参谋自有包，不触碰上游）：
 * - 进程级只自动检查一次（延迟 3s，空闲启动）；
 * - 强制版本持久化，断网也不能绕过；
 * - 下载/安装状态集中到 [state]，UI 只做映射。
 */
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AppUpdateApi,
    private val downloader: AppUpdateDownloader,
    private val installer: AppUpdateInstaller,
    private val json: Json,
) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val launchChecked = AtomicBoolean(false)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    private var lastManifest: UpdateManifest? = null

    init {
        val cached = cachedForcedManifest()
        if (cached != null && forcedBlocked()) {
            lastManifest = cached
            _state.value = UpdateState.Forced(cached)
        }
    }

    /** App 启动后调用；release 恒启用，debug 需配 `dev.updateManifestUrl`。 */
    fun checkOnLaunch() {
        if (!UpdateTestHooks.updateCheckEnabled()) return
        if (!launchChecked.compareAndSet(false, true)) return
        scope.launch {
            delay(LAUNCH_DELAY_MS)
            performCheck()
        }
    }

    /** 设置页手动检查。 */
    suspend fun checkNow(): CheckOutcome = performCheck()

    private suspend fun performCheck(): CheckOutcome = checkMutex.withLock {
        _state.value = UpdateState.Checking
        val manifest = api.fetch()
        if (manifest == null) {
            handleCheckFailure()
            return@withLock CheckOutcome.FAILED
        }
        if (manifest.packageName.isNotBlank() && manifest.packageName != context.packageName) {
            _state.value = UpdateState.Failed(null, forcedBlocked(), UpdateFailure.MANIFEST_INVALID)
            return@withLock CheckOutcome.FAILED
        }

        lastManifest = manifest
        val installed = installedPackage()
        val installedVersion = SemVer.parse(installed?.versionName) ?: SemVer(0, 0, 0)
        val installedCode = installed?.let { versionCode(it) } ?: 0L

        when (decideUpdate(installedVersion, installedCode, manifest)) {
            UpdateDecision.FORCED -> {
                cacheForced(manifest)
                _state.value = UpdateState.Forced(manifest)
                startDownload(manifest, forced = true)
                CheckOutcome.FORCED
            }

            UpdateDecision.OPTIONAL -> {
                _state.value = UpdateState.Optional(manifest)
                CheckOutcome.OPTIONAL
            }

            UpdateDecision.NONE -> {
                clearForcedIfNotNeeded(manifest)
                _state.value = UpdateState.UpToDate
                CheckOutcome.UP_TO_DATE
            }
        }
    }

    private fun handleCheckFailure() {
        val cached = cachedForcedManifest()
        when {
            forcedBlocked() && cached != null -> {
                lastManifest = cached
                _state.value = UpdateState.Forced(cached)
            }

            forcedBlocked() -> {
                _state.value = UpdateState.Failed(null, true, UpdateFailure.NETWORK)
            }

            else -> {
                _state.value = UpdateState.CheckFailed(forced = false)
            }
        }
    }

    fun startForcedDownload(manifest: UpdateManifest) = startDownload(manifest, forced = true)

    fun startOptionalDownload(manifest: UpdateManifest) = startDownload(manifest, forced = false)

    private fun startDownload(manifest: UpdateManifest, forced: Boolean) {
        if (downloadJob?.isActive == true) return
        val file = manifest.files.firstOrNull() ?: return
        _state.value = UpdateState.Downloading(manifest, 0L, file.size, forced)
        downloadJob = scope.launch {
            when (
                val result = downloader.download(manifest) { downloaded, total ->
                    _state.value = UpdateState.Downloading(manifest, downloaded, total, forced)
                }
            ) {
                is DownloadResult.Success -> {
                    _state.value = UpdateState.ReadyToInstall(manifest, result.file, forced)
                    if (forced) install(manifest, result.file, forced)
                }

                is DownloadResult.Failure -> {
                    _state.value = UpdateState.Failed(manifest, forced, result.reason)
                }
            }
        }
    }

    private fun install(manifest: UpdateManifest, file: File, forced: Boolean) {
        scope.launch {
            when (val outcome = installer.install(file, manifest)) {
                InstallOutcome.Started -> {
                    // 系统安装器已拉起；留在 Ready 态供“重新安装”兜底。
                    _state.value = UpdateState.ReadyToInstall(manifest, file, forced)
                }

                InstallOutcome.PermissionRequired -> {
                    _state.value = UpdateState.PermissionRequired(manifest, file, forced)
                }

                is InstallOutcome.Failure -> {
                    _state.value = UpdateState.Failed(manifest, forced, outcome.reason)
                }
            }
        }
    }

    /** 失败重试：有完整 APK 就重新拉起安装，否则重新下载/检查。 */
    fun retry(forced: Boolean) {
        val current = _state.value
        val manifest = lastManifest ?: when (current) {
            is UpdateState.Forced -> current.manifest
            is UpdateState.Optional -> current.manifest
            is UpdateState.Downloading -> current.manifest
            is UpdateState.ReadyToInstall -> current.manifest
            is UpdateState.PermissionRequired -> current.manifest
            is UpdateState.Failed -> current.manifest
            else -> null
        }
        if (manifest == null) {
            scope.launch { performCheck() }
            return
        }
        val apk = when (current) {
            is UpdateState.ReadyToInstall -> current.apk
            is UpdateState.PermissionRequired -> current.apk
            else -> null
        }
        if (apk != null && apk.isFile) {
            install(manifest, apk, forced)
        } else {
            startDownload(manifest, forced)
        }
    }

    fun dismissOptional() {
        val dismissible = when (val current = _state.value) {
            is UpdateState.Optional -> true
            is UpdateState.Failed -> !current.forced
            is UpdateState.PermissionRequired -> !current.forced
            is UpdateState.ReadyToInstall -> !current.forced
            else -> false
        }
        if (dismissible) _state.value = UpdateState.UpToDate
    }

    fun cancelOptionalDownload() {
        val current = _state.value
        if (current is UpdateState.Downloading && !current.forced) {
            downloadJob?.cancel()
            _state.value = UpdateState.UpToDate
        }
    }

    fun openInstallPermissionSettings() = installer.openUnknownSourcesSettings()

    /** 本地缓存了强制版本且本机仍低于它 -> 无条件拦截（断网也成立）。 */
    fun forcedBlocked(): Boolean {
        if (!UpdateTestHooks.forcedGateEnabled()) return false
        return installedVersionCode() < prefs.getLong(KEY_FORCED_CODE, 0L)
    }

    private fun cachedForcedManifest(): UpdateManifest? {
        val raw = prefs.getString(KEY_FORCED_MANIFEST, null) ?: return null
        return runCatching { json.decodeFromString<UpdateManifest>(raw) }.getOrNull()
    }

    private fun cacheForced(manifest: UpdateManifest) {
        prefs.edit()
            .putLong(KEY_FORCED_CODE, manifest.versionCode)
            .putString(KEY_FORCED_MANIFEST, json.encodeToString(manifest))
            .apply()
    }

    private fun clearForcedIfNotNeeded(manifest: UpdateManifest) {
        if (installedVersionCode() >= manifest.versionCode) {
            prefs.edit()
                .remove(KEY_FORCED_CODE)
                .remove(KEY_FORCED_MANIFEST)
                .apply()
        }
    }

    private fun installedPackage(): PackageInfo? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()

    private fun installedVersionCode(): Long =
        installedPackage()?.let { versionCode(it) } ?: 0L

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private companion object {
        const val PREFS_FILE = "float_update"
        const val KEY_FORCED_CODE = "forced_version_code"
        const val KEY_FORCED_MANIFEST = "forced_manifest_json"
        const val LAUNCH_DELAY_MS = 3_000L
    }
}
