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

package com.verlintas.baic2.floatball.account

import com.verlintas.baic2.core.data.repository.AgentRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 好参谋账号门面：登录会话状态 + AI 网关凭证落地。
 *
 * - [session] 为唯一登录态事实源（启动时从 [AccountPrefs] 恢复，过期即未登录）；
 * - [applyGateway] 把卡密写成默认供应商的网关凭证（baseUrl 指向 LiteLLM 网关、
 *   apiKey=卡密，商业化方案 §6.3），与登录解耦：未登录不允许先写卡密；
 * - [logout] 同时清会话与卡密，防止换号继承上一账号的卡密额度；
 * - 错误一律以 `REASON_*` 常量返回/抛出，展示层映射 strings.xml 文案。
 */
@Singleton
class AccountManager @Inject constructor(
    private val prefs: AccountPrefs,
    private val api: AccountApi,
    private val agentRepository: AgentRepository,
) {

    private val _session = MutableStateFlow(prefs.loadSession())
    val session: StateFlow<AccountPrefs.Session?> = _session.asStateFlow()

    val isLoggedIn: Boolean get() = _session.value != null

    /** 获取登录验证码；服务端开关关闭时 [AccountApi.Captcha.enabled] 为 false。 */
    suspend fun fetchCaptcha(): AccountApi.Captcha = api.fetchCaptcha()

    /**
     * 账号密码登录。成功保存会话并刷新 [session]；
     * 失败抛 [AccountException]（REASON_* + 服务端原因），由展示层映射文案。
     */
    suspend fun login(username: String, password: String, code: String?, uuid: String?) {
        val result = api.login(username, password, code, uuid)
        val session = AccountPrefs.Session(
            username = username,
            accessToken = result.accessToken,
            refreshToken = result.refreshToken,
            expireAtMs = System.currentTimeMillis() + result.expireInSeconds * 1_000L,
        )
        prefs.saveSession(session)
        _session.value = session
    }

    /** 退出登录：会话与卡密一起清空（换号不得继承上一账号的卡密额度）。 */
    fun logout() {
        prefs.clearSession()
        prefs.clearCardKey()
        _session.value = null
    }

    /**
     * 把 [cardKey] 落成 AI 网关凭证：默认供应商 baseUrl 指向
     * [AccountApi.gatewayBaseUrl]、apiKey=卡密（Bearer 鉴权，LiteLLM 虚拟 key）。
     *
     * 成功返回 null 并持久化卡密；失败返回 `REASON_*` 常量（不落盘、不改供应商）。
     */
    suspend fun applyGateway(cardKey: String): String? {
        if (cardKey.isBlank()) return REASON_CARD_INVALID
        val agent = agentRepository.getDefaultAgent() ?: return REASON_NO_AGENT
        return runCatching {
            agentRepository.update(
                agent.copy(baseUrl = api.gatewayBaseUrl()),
                newPlaintextApiKey = cardKey,
            )
            prefs.saveCardKey(cardKey)
            null
        }.getOrElse { REASON_GATEWAY }
    }

    /** 站点注册页（跟随当前服务器地址）。 */
    fun registerUrl(): String = api.registerUrl()

    /**
     * 装包登录门（用户口径「装完打开先登录」）：包的 lastUpdateTime 与上次登录成功
     * 时记录值不一致＝装包（安装/升级）后首启，即使本地会话未过期也强制先出登录页；
     * [consumeInstallGate] 在登录成功后消费，同一次安装内的后续启动不再强制。
     */
    fun installGatePending(packageLastUpdateTime: Long): Boolean =
        prefs.loadInstallGateTime() != packageLastUpdateTime

    /** 消费装包登录门（登录成功后调用）。 */
    fun consumeInstallGate(packageLastUpdateTime: Long) {
        prefs.saveInstallGateTime(packageLastUpdateTime)
    }

    /**
     * 绑定使用卡密：服务端绑定归属 + 面额入账成功后把卡密落成 AI 网关凭证。
     * 成功返回该卡信息（含该卡余额/到期）；失败抛 [AccountException]（REASON_*）。
     */
    suspend fun enableCard(cardKey: String): AccountApi.CardInfo {
        val token = _session.value?.accessToken ?: throw AccountException(REASON_NOT_LOGGED_IN)
        val info = api.enableCard(token, cardKey.trim())
        applyGateway(cardKey.trim())?.let { reason ->
            // 服务端已入账但本地网关落盘失败：不回滚入账（积分是用户的），
            // 报因让用户重试绑定（服务端幂等，重试安全）。
            throw AccountException(reason)
        }
        return info
    }

    /** 我的已绑定卡密列表（服务端实时余额/到期）；未登录抛 [AccountException]。 */
    suspend fun myCards(): List<AccountApi.MyCard> {
        val token = _session.value?.accessToken ?: throw AccountException(REASON_NOT_LOGGED_IN)
        return api.fetchMyCards(token)
    }

    /** 查询积分钱包；未登录抛 [AccountException]，网络/服务端错误同。 */
    suspend fun wallet(): AccountApi.Wallet {
        val token = _session.value?.accessToken ?: throw AccountException(REASON_NOT_LOGGED_IN)
        return api.fetchWallet(token)
    }

    /**
     * 测试免登录（仅 [AccountApi.TEST_LOGIN_ENABLED] 构建放行）：
     * `am start --es debugToken <token> [--es debugUsername <name>] [--es debugRefreshToken <rt>]
     * [--el debugExpireSeconds <s>]` 直接注入登录会话，跳过登录框，供自动化测试用。
     *
     * 成功返回 true（会话已落盘并刷新 [session]，装包登录门一并消费）；
     * 开关关闭或未带 debugToken 返回 false（完全走正常登录门）。
     * 生产 https 构建恒不放行，不留绕过入口。
     */
    fun debugInjectFromIntent(
        context: android.content.Context,
        intent: android.content.Intent?,
    ): Boolean {
        if (!AccountApi.TEST_LOGIN_ENABLED) return false
        val token = intent?.getStringExtra("debugToken")?.takeIf { it.isNotBlank() } ?: return false
        val session = AccountPrefs.Session(
            username = intent.getStringExtra("debugUsername")?.takeIf { it.isNotBlank() }
                ?: "test-account",
            accessToken = token,
            refreshToken = intent.getStringExtra("debugRefreshToken")?.takeIf { it.isNotBlank() },
            expireAtMs = System.currentTimeMillis() +
                (intent.getLongExtra("debugExpireSeconds", 30L * 24 * 60 * 60) * 1_000L),
        )
        prefs.saveSession(session)
        _session.value = session
        // 免登录即视为已过装包登录门，否则首启仍会被门拦回登录页。
        val lastUpdateTime = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        consumeInstallGate(lastUpdateTime)
        return true
    }

    companion object {
        /** 错误原因常量（展示层映射 strings.xml 文案）。 */
        const val REASON_NO_AGENT = "REASON_NO_AGENT"
        const val REASON_CARD_INVALID = "REASON_CARD_INVALID"
        const val REASON_GATEWAY = "REASON_GATEWAY"
        const val REASON_NOT_LOGGED_IN = "REASON_NOT_LOGGED_IN"
    }
}
