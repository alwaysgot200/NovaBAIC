/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import com.verlintas.baic2.core.data.repository.AgentRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * DeepSeek 账户余额查询（设置页「用量统计」卡片手动刷新）。
 *
 * DeepSeek 官方提供 `GET https://api.deepseek.com/user/balance`，用与对话
 * 相同的 API Key 鉴权。好参谋只做只读展示：
 * - 仅当当前默认供应商的 baseUrl 主机名包含 deepseek 时支持，其余返回 null
 *   （设置页不显示该行，不猜其他厂商的余额接口）；
 * - 复用 Hilt 注入的共享 OkHttpClient，不自行 new；
 * - 任何网络/解析失败都返回 null，由 UI 显示「--」，不打扰主链路。
 */
@Singleton
class DeepSeekBalanceRepository @Inject constructor(
    private val client: OkHttpClient,
    private val agentRepository: AgentRepository,
) {
    /** 余额展示模型；[supported]=false 表示当前供应商无余额接口。 */
    data class Balance(
        val supported: Boolean,
        val available: Boolean = false,
        val total: String = "",
        val currency: String = "",
    )

    suspend fun refresh(): Balance = withContext(Dispatchers.IO) {
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
        if (config == null || config.apiKey.isBlank()) return@withContext Balance(supported = false)
        val base = config.baseUrl.trim().trimEnd('/')
        val origin = when {
            base.endsWith("/v1") -> base.removeSuffix("/v1")
            else -> base
        }
        if (!origin.contains("deepseek", ignoreCase = true)) {
            return@withContext Balance(supported = false)
        }
        val request = Request.Builder()
            .url("$origin/user/balance")
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Balance(supported = true)
                val body = response.body?.string().orEmpty()
                parse(body)
            }
        }.getOrDefault(Balance(supported = true))
    }

    private fun parse(body: String): Balance {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }
            .getOrNull() ?: return Balance(supported = true)
        val available = root["is_available"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val infos = root["balance_infos"]?.jsonArray.orEmpty()
        val cny = infos.firstOrNull {
            runCatching {
                it.jsonObject["currency"]?.jsonPrimitive?.content?.equals("CNY", ignoreCase = true) == true
            }.getOrDefault(false)
        } ?: infos.firstOrNull()
        val obj = cny?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return Balance(supported = true, available = available)
        return Balance(
            supported = true,
            available = available,
            total = obj["total_balance"]?.jsonPrimitive?.content.orEmpty(),
            currency = obj["currency"]?.jsonPrimitive?.content ?: "CNY",
        )
    }
}
