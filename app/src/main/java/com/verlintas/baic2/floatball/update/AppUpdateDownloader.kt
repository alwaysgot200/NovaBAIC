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
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface DownloadResult {
    data class Success(val file: File) : DownloadResult
    data class Failure(val reason: UpdateFailure) : DownloadResult
}

/**
 * 从自有服务器下载好参谋 APK：
 * - 缓存目录下的 `.part` 文件断点续传；进程被杀后下次启动继续；
 * - 完成后 SHA-256 强校验，失败删除 `.part`；
 * - 下载前检查可用空间。
 */
@Singleton
class AppUpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {

    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (manifest.packageName.isNotBlank() && manifest.packageName != context.packageName) {
            return@withContext DownloadResult.Failure(UpdateFailure.PACKAGE_MISMATCH)
        }
        val file = manifest.files.firstOrNull { it.abi == "arm64-v8a" && it.url.isNotBlank() }
            ?: manifest.files.firstOrNull { it.url.isNotBlank() }
            ?: return@withContext DownloadResult.Failure(UpdateFailure.MANIFEST_INVALID)
        if (!SHA256_RE.matches(file.sha256) || !VERSION_RE.matches(manifest.version)) {
            return@withContext DownloadResult.Failure(UpdateFailure.MANIFEST_INVALID)
        }

        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeVersion = manifest.version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val target = File(dir, "haocanmou-$safeVersion.apk")
        val part = File(dir, "${target.name}.part")

        if (target.isFile && sha256Of(target).equals(file.sha256, ignoreCase = true)) {
            return@withContext DownloadResult.Success(target)
        }
        if (target.exists()) target.delete()

        if (file.size > 0L) {
            val stat = StatFs(dir.absolutePath)
            if (stat.availableBytes < file.size + file.size / 5) {
                return@withContext DownloadResult.Failure(UpdateFailure.NO_SPACE)
            }
        }

        val url = resolveUrl(file)
        return@withContext runCatching {
            downloadOnce(url, part, target, file, onProgress, allowRetry = true)
        }.getOrElse {
            // 网络/磁盘异常保留 .part，下次启动可续传。
            DownloadResult.Failure(UpdateFailure.DOWNLOAD)
        }
    }

    private fun resolveUrl(file: UpdateFile): String {
        val base = UpdateTestHooks.manifestBaseUrl().trimEnd('/') + "/"
        val rel = file.url.removePrefix("/")
        val raw = if (rel.startsWith("http://") || rel.startsWith("https://")) rel else base + rel
        val versionTag = file.sha256.take(8)
        return if (raw.contains('?')) raw else "$raw?v=$versionTag"
    }

    private fun downloadOnce(
        url: String,
        part: File,
        target: File,
        file: UpdateFile,
        onProgress: (Long, Long) -> Unit,
        allowRetry: Boolean,
    ): DownloadResult {
        val existing = if (part.isFile) part.length() else 0L
        val builder = Request.Builder().url(url).get()
        if (existing > 0L) builder.header("Range", "bytes=$existing-")

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 416 && allowRetry) {
                part.delete()
                return downloadOnce(url, part, target, file, onProgress, allowRetry = false)
            }
            if (!response.isSuccessful) return DownloadResult.Failure(UpdateFailure.DOWNLOAD)

            val append = response.code == 206
            if (!append) {
                part.delete()
            }
            val offset = if (append) existing else 0L
            val body = response.body ?: return DownloadResult.Failure(UpdateFailure.DOWNLOAD)
            val total = if (file.size > 0L) {
                file.size
            } else {
                offset + body.contentLength().coerceAtLeast(0L)
            }

            FileOutputStream(part, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = offset
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
                output.fd.sync()
            }
        }

        if (file.size > 0L && part.length() != file.size) {
            part.delete()
            return DownloadResult.Failure(UpdateFailure.DOWNLOAD)
        }
        if (!sha256Of(part).equals(file.sha256, ignoreCase = true)) {
            part.delete()
            return DownloadResult.Failure(UpdateFailure.SHA_MISMATCH)
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            return runCatching {
                part.copyTo(target, overwrite = true)
                part.delete()
                DownloadResult.Success(target)
            }.getOrElse {
                part.delete()
                DownloadResult.Failure(UpdateFailure.DOWNLOAD)
            }
        }
        return DownloadResult.Success(target)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        val SHA256_RE = Regex("^[0-9a-fA-F]{64}$")
        val VERSION_RE = Regex("^\\d+\\.\\d+\\.\\d+$")
    }
}
