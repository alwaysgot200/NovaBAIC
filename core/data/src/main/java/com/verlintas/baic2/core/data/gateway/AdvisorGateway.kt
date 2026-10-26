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

package com.verlintas.baic2.core.data.gateway

/** 输出 tokens 精细度档位（Max / 标准 / 精省，服务端按模型上限派生）。 */
data class GatewayMode(
    val key: String,
    val label: String,
    val maxTokens: Int,
)

/** App 端模型目录项（服务端只下发展示信息，不含任何 key/上游地址）。 */
data class GatewayModel(
    val key: String,
    val displayName: String,
    val coefficientMilli: Int,
    val coefficientLabel: String,
    val supportsReasoning: Boolean,
    val modes: List<GatewayMode>,
) {
    /** Max 档（缺省时回退 4096）。 */
    val maxMode: GatewayMode?
        get() = modes.firstOrNull { it.key == "max" }
}

/** 服务端 LLM 网关配置（baseUrl 已由实现拼成绝对地址）。 */
data class GatewayConfig(
    val baseUrl: String,
    val defaultModel: String,
    val models: List<GatewayModel>,
)

/**
 * 好参谋云端 AI 网关门面（feature:agents 只依赖 core:data，不反向依赖 app）。
 *
 * 实现由 app 模块提供：`config()` 走登录态 `/point-card/gateway/config`，
 * `cardKey()` 返回当前绑定卡密（LiteLLM 虚拟 key）。
 */
interface AdvisorGateway {

    /** 拉取网关配置（模型清单）；未登录/网络失败抛异常，由展示层映射文案。 */
    suspend fun config(): GatewayConfig

    /** 当前绑定卡密明文；未绑卡返回 null。 */
    suspend fun cardKey(): String?
}
