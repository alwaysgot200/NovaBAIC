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

package com.verlintas.baic2

import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.model.Agent
import com.verlintas.baic2.core.model.ProviderId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 开发环境专用：首启自动预置 DeepSeek Agent，免去每次在模拟器手动填 API Key。
 *
 * 密钥来自 local.properties（已被 gitignore）的 dev.deepseekApiKey，经
 * BuildConfig.DEV_DEEPSEEK_API_KEY 注入，仅 debug 构建非空；release 恒为空串，
 * 且 BuildConfig.DEBUG 门控让本类在生产构建中恒为空操作（R8 会消除死代码）。
 *
 * 不覆盖用户在 App 内手动做的配置：仅当没有默认 Agent，或默认 Agent 就是
 * DeepSeek 但密钥缺失/无法解密时才写入。
 */
@Singleton
class DevAgentSeeder @Inject constructor(
    private val agentRepository: AgentRepository,
) {

    @Volatile
    private var done = false

    suspend fun seedIfNeeded() {
        // Application.onCreate 每进程只发起一次, 无并发; done 仅防同进程重复执行
        if (done) return
        val key = BuildConfig.DEV_DEEPSEEK_API_KEY
        if (!BuildConfig.DEBUG || key.isBlank()) return
        val default = agentRepository.observeDefaultAgent().first()
        when {
            default == null -> agentRepository.save(devAgent(), key)
            default.baseUrl == DEEPSEEK_BASE_URL -> {
                val usable = runCatching { agentRepository.resolveConfig(default.id) }
                    .map { config -> config?.apiKey?.isNotBlank() == true }
                    .getOrDefault(false)
                if (!usable) agentRepository.update(default, key)
            }
            // 默认 Agent 是用户配置的其他服务商：不动
        }
        done = true
    }

    private fun devAgent() = Agent(
        name = "DeepSeek (dev)",
        provider = ProviderId.OPENAI_COMPATIBLE,
        baseUrl = DEEPSEEK_BASE_URL,
        model = "deepseek-flash",
        isDefault = true,
    )

    private companion object {
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
    }
}
