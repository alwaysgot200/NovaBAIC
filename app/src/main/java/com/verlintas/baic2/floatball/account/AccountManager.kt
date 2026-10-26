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
import com.verlintas.baic2.core.model.Agent
import com.verlintas.baic2.core.model.ProviderId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    private val json: Json,
) {

    private val _session = MutableStateFlow(prefs.loadSession())
    val session: StateFlow<AccountPrefs.Session?> = _session.asStateFlow()

    /** 测试注入/网关落盘用（单例级，不随 Activity 生命周期）。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // 换号登录：清掉上一账号的离线快照，首次拉取前不展示他人余额。
        prefs.clearSnapshots()
        _session.value = session
    }

    /** 退出登录：会话与卡密一起清空（换号不得继承上一账号的卡密额度）。 */
    fun logout() {
        prefs.clearSession()
        prefs.clearCardKey()
        prefs.clearSnapshots()
        _session.value = null
    }

    /**
     * 会话失效（服务端 401）：只清登录态，保留已落盘的卡密——
     * AI 网关凭证与登录解耦（[applyGateway]），token 过期不应顺带中断已购额度。
     */
    fun expireSession() {
        prefs.clearSession()
        _session.value = null
    }

    /**
     * 把 [cardKey] 落成 AI 网关凭证：默认供应商 baseUrl 指向
     * [AccountApi.gatewayBaseUrl]、apiKey=卡密（Bearer 鉴权，LiteLLM 虚拟 key）。
     *
     * 成功返回 null 并持久化卡密；失败返回 `REASON_*` 常量（不落盘、不改供应商）。
     */
    suspend fun applyGateway(cardKey: String, model: String = DEFAULT_GATEWAY_MODEL): String? {
        if (cardKey.isBlank()) return REASON_CARD_INVALID
        return runCatching {
            val existing = agentRepository.getDefaultAgent()
            if (existing == null) {
                // 首次绑定卡密：直接创建云端默认 Agent（用户无需再手填 key/地址）。
                agentRepository.save(
                    Agent(
                        name = DEFAULT_AGENT_NAME,
                        provider = ProviderId.OPENAI_COMPATIBLE,
                        baseUrl = api.gatewayBaseUrl(),
                        model = model,
                        isDefault = true,
                    ),
                    cardKey,
                )
            } else {
                agentRepository.update(
                    existing.copy(baseUrl = api.gatewayBaseUrl(), model = model),
                    newPlaintextApiKey = cardKey,
                )
            }
            prefs.saveCardKey(cardKey)
            null
        }.getOrElse { REASON_GATEWAY }
    }

    /**
     * 注册好参谋账号（productId=haocanmou，注册即用）。成功返回服务端结果；
     * 失败抛 [AccountException]（REASON_* + 服务端原因），由展示层映射文案。
     * 注册不自动登录——成功后回登录页由用户登录（登录门口径不变）。
     */
    suspend fun register(
        email: String,
        password: String,
        confirmPassword: String,
        code: String?,
        uuid: String?,
    ): AccountApi.RegisterResult = api.register(email, password, confirmPassword, code, uuid)

    /**
     * 装包登录门：仅“全新安装”强制先登录；覆盖升级必须保留登录态。
     * 规则：
     *  - 本机已有登录过的时间戳（非 0）→ 永不因安装/升级触发登录门；
     *  - 时间戳为 0 且当前无会话 → 全新安装，触发登录门；
     *  - 时间戳为 0 但已有有效会话 → 旧版本遗留升级，按升级处理，补记时间戳。
     *
     * 旧实现比较包 `lastUpdateTime`，任何安装/升级都会强制重登；本方法改为
     * 只认“本机是否登录过”，从根上满足升级不重登。
     */
    fun installGatePending(): Boolean =
        prefs.loadInstallGateTime() == 0L && !isLoggedIn

    /** 消费装包登录门（登录成功后调用）。 */
    fun consumeInstallGate() {
        prefs.saveInstallGateTime(System.currentTimeMillis())
    }

    /** 旧版本升级兼容：已有会话但从未记录过时间戳时，静默补记一次。 */
    fun ensureInstallGateRecorded() {
        if (prefs.loadInstallGateTime() == 0L) {
            prefs.saveInstallGateTime(System.currentTimeMillis())
        }
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

    /**
     * 我的已绑定卡密列表（服务端实时余额/到期）；未登录抛 [AccountException]。
     * 成功即覆盖离线快照，供断网时继续展示存量卡密。
     */
    suspend fun myCards(): List<AccountApi.MyCard> {
        val token = _session.value?.accessToken ?: throw AccountException(REASON_NOT_LOGGED_IN)
        val cards = api.fetchMyCards(token)
        runCatching { prefs.saveCardsSnapshot(json.encodeToString(cards)) }
        return cards
    }

    /**
     * 查询积分钱包；未登录抛 [AccountException]，网络/服务端错误同。
     * 成功即覆盖离线快照，供断网时继续展示存量积分。
     */
    suspend fun wallet(): AccountApi.Wallet {
        val token = _session.value?.accessToken ?: throw AccountException(REASON_NOT_LOGGED_IN)
        val wallet = api.fetchWallet(token)
        runCatching { prefs.saveWalletSnapshot(json.encodeToString(wallet)) }
        return wallet
    }

    /** 离线兜底的最近一次成功钱包快照；无缓存或解析失败返回 null。 */
    fun cachedWallet(): AccountApi.Wallet? =
        prefs.loadWalletSnapshot()?.let { raw ->
            runCatching { json.decodeFromString<AccountApi.Wallet>(raw) }.getOrNull()
        }

    /** 离线兜底的最近一次成功卡密快照；无缓存或解析失败返回空列表。 */
    fun cachedCards(): List<AccountApi.MyCard> =
        prefs.loadCardsSnapshot()?.let { raw ->
            runCatching { json.decodeFromString<List<AccountApi.MyCard>>(raw) }.getOrNull()
        }.orEmpty()

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
        // 自动化测试一并注入卡密与模型（等价于用户绑定卡密后的网关落盘），
        // 读不到卡密或默认 Agent 缺失时静默跳过，不阻断登录注入本身。
        intent.getStringExtra("debugCardKey")?.takeIf { it.isNotBlank() }?.let { cardKey ->
            val model = intent.getStringExtra("debugModel")?.takeIf { it.isNotBlank() }
                ?: DEFAULT_GATEWAY_MODEL
            ioScope.launch { applyGateway(cardKey, model) }
        }
        // 免登录即视为已过装包登录门，否则首启仍会被门拦回登录页。
        consumeInstallGate()
        return true
    }

    companion object {
        /** 错误原因常量（展示层映射 strings.xml 文案）。 */
        const val REASON_NO_AGENT = "REASON_NO_AGENT"
        const val REASON_CARD_INVALID = "REASON_CARD_INVALID"
        const val REASON_GATEWAY = "REASON_GATEWAY"
        const val REASON_NOT_LOGGED_IN = "REASON_NOT_LOGGED_IN"

        /** 默认云端模型（与 service 端 defaultModel 一致）。 */
        const val DEFAULT_GATEWAY_MODEL = "deepseek-flash"

        /** 首次绑定卡密时自动创建的默认 Agent 名称（不暴露给配置向导的卡片文案）。 */
        const val DEFAULT_AGENT_NAME = "好参谋 AI"
    }
}
