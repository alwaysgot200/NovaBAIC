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

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 读取服务器版本清单。复用 Hilt 注入的共享 OkHttpClient/Json，
 * 仅为本次调用派生 15s callTimeout（共享连接池与调度器，不另造网络栈）。
 */
@Singleton
class AppUpdateApi @Inject constructor(
    client: OkHttpClient,
    private val json: Json,
) {

    private val updateClient = client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): UpdateManifest? = withContext(Dispatchers.IO) {
        val url = UpdateTestHooks.manifestUrl()
            ?: "${UpdateTestHooks.manifestBaseUrl()}manifest.json?t=${System.currentTimeMillis()}"
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        runCatching {
            updateClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                json.decodeFromString<UpdateManifest>(body)
            }
        }.getOrNull()
    }
}
