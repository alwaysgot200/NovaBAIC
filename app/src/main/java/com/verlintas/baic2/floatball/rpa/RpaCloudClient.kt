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
import android.provider.Settings
import com.verlintas.baic2.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 「我的 RPA」云端库（多产品方案 §4.10.4）：
 * 读取服务端 `user_market_library` 中 `product=haocanmou` 的条目，
 * 安装时复用市场统一下载接口（`/market/download/:scriptId`），
 * 字节交给 [com.verlintas.rpa.store.RpaScriptStore.importBytes] 走既有导入链路。
 *
 * 下载成功后服务端会自动把该脚本 upsert 进库（P6），客户端无需补录；
 * 库列表只做展示与安装，论坛附件不入库。
 */
@Singleton
class RpaCloudClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    @ApplicationContext private val context: Context,
) {

    /** 库条目（只取展示/安装需要的字段）。 */
    data class Item(
        val id: Long,
        val scriptId: Long?,
        val title: String,
        val packageType: String,
        val installedVersion: String?,
        val latestVersion: String?,
        val licensed: Boolean,
        val available: Boolean,
        val status: String,
    )

    /** 当前用户的 RPA 库；未登录/网络/协议错误统一抛 [RpaCloudException]。 */
    suspend fun list(accessToken: String): List<Item> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BuildConfig.SERVER_ORIGIN}/api/v1/market/library?product=$PRODUCT")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: throw RpaCloudException("响应格式不正确")
                val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull()
                if (!response.isSuccessful || (code != null && code != 200)) {
                    throw RpaCloudException(
                        root["msg"]?.jsonPrimitive?.contentOrNull ?: "服务端拒绝了请求",
                    )
                }
                val data = root["data"]?.jsonArray ?: return@withContext emptyList()
                data.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    Item(
                        id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                        scriptId = obj["scriptId"]?.jsonPrimitive?.longOrNull,
                        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        packageType = obj["packageType"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        installedVersion = obj["installedVersion"]?.jsonPrimitive?.contentOrNull,
                        latestVersion = obj["latestVersion"]?.jsonPrimitive?.contentOrNull,
                        licensed = obj["licensed"]?.jsonPrimitive?.booleanOrNull == true,
                        available = obj["available"]?.jsonPrimitive?.booleanOrNull == true,
                        status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            }
        } catch (e: RpaCloudException) {
            throw e
        } catch (e: Exception) {
            throw RpaCloudException("网络连接失败，请稍后重试", e)
        }
    }

    /** 下载脚本包字节（FREE 游客/已授权用户均可；收费需服务端核销设备席位）。 */
    suspend fun download(accessToken: String, scriptId: Long): ByteArray =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${BuildConfig.SERVER_ORIGIN}/api/v1/market/download/$scriptId")
                .header("Authorization", "Bearer $accessToken")
                .header("X-Device-Id", deviceId())
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (!response.isSuccessful) {
                        val msg = runCatching {
                            json.parseToJsonElement(bytes.decodeToString()).jsonObject
                                .get("msg")?.jsonPrimitive?.contentOrNull
                        }.getOrNull()
                        throw RpaCloudException(msg ?: "下载失败（${response.code}）")
                    }
                    if (bytes.isEmpty()) throw RpaCloudException("下载内容为空")
                    bytes
                }
            } catch (e: RpaCloudException) {
                throw e
            } catch (e: Exception) {
                throw RpaCloudException("网络连接失败，请稍后重试", e)
            }
        }

    /** 稳定设备标识（服务端收费核销按设备占席；不含硬件序列号）。 */
    private fun deviceId(): String {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() } ?: "unknown"
        return "haocanmou-$raw"
    }

    private companion object {
        const val PRODUCT = "haocanmou"
    }
}

/** 云端库错误：message 可直接展示（服务端中文原因或网络提示）。 */
class RpaCloudException(message: String, cause: Throwable? = null) : Exception(message, cause)
