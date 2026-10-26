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

package com.verlintas.baic2.floatball.account

import com.verlintas.baic2.core.data.gateway.AdvisorGateway
import com.verlintas.baic2.core.data.gateway.GatewayConfig
import com.verlintas.baic2.core.data.gateway.GatewayMode
import com.verlintas.baic2.core.data.gateway.GatewayModel
import com.verlintas.baic2.core.model.ModelPricing
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AdvisorGateway] 的实现：登录态拉服务端模型清单 + 本地读取已绑定卡密。
 *
 * baseUrl 由服务端相对路径（`/llm/v1`）与 [AccountApi.SERVER_ORIGIN] 拼成绝对地址，
 * 与 [AccountManager.applyGateway] 使用的地址完全一致。
 */
@Singleton
class AccountGateway @Inject constructor(
    private val accountManager: AccountManager,
    private val api: AccountApi,
    private val prefs: AccountPrefs,
) : AdvisorGateway {

    override suspend fun config(): GatewayConfig {
        val token = accountManager.session.value?.accessToken
            ?: throw AccountException(AccountManager.REASON_NOT_LOGGED_IN)
        val remote = api.fetchGatewayConfig(token)
        // 本地积分展示用的倍率快照（与服务端目录同步）。
        ModelPricing.update(remote.models.map { it.key to it.coefficientMilli })
        val base = if (remote.baseUrl.startsWith("http")) {
            remote.baseUrl.trimEnd('/')
        } else {
            "${AccountApi.SERVER_ORIGIN.trimEnd('/')}/${remote.baseUrl.trimStart('/')}".trimEnd('/')
        }
        return GatewayConfig(
            baseUrl = base,
            defaultModel = remote.defaultModel,
            models = remote.models.map {
                GatewayModel(
                    key = it.key,
                    displayName = it.displayName.ifBlank { it.key },
                    coefficientMilli = it.coefficientMilli,
                    coefficientLabel = it.coefficientLabel.ifBlank { "${it.coefficientMilli / 1000.0}x" },
                    supportsReasoning = it.supportsReasoning,
                    modes = it.modes.map { mode ->
                        GatewayMode(key = mode.key, label = mode.label, maxTokens = mode.maxTokens)
                    },
                )
            },
        )
    }

    override suspend fun cardKey(): String? = prefs.loadCardKey()
}
