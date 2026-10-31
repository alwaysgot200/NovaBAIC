/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under the terms of the GNU
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

import com.verlintas.baic2.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 账号接口错误。[reason] 为 `REASON_*` 常量（展示层映射 strings.xml 文案）；
 * [serverMsg] 为服务端动态中文原因（DTO 校验/业务报错原文），有值时优先展示。
 */
class AccountException(
    val reason: String,
    val serverMsg: String? = null,
    cause: Throwable? = null,
) : Exception(serverMsg ?: reason, cause)

/**
 * 好参谋账号服务端接口（autojs6_Management，Result 信封 {code,msg,data}）。
 *
 * 覆盖登录链路（验证码获取、账号密码登录）与注册链路（[register]，productId=haocanmou，
 * 注册即用、只绑好参谋产品角色）。注册在 App 内走原生表单直连本接口，不再内嵌站点
 * WebView 页面（WebView 的输入法插入点异常与软键盘遮挡在客户端无法可靠修复）。
 * 复用 Hilt 注入的共享 OkHttpClient / Json，不自行 new；失败统一抛 [AccountException]，
 * 由 UI 映射文案。生产环境接口加密关闭（CRYPTO_ENABLED=false），明文 JSON 即契约。
 * 服务器根地址按环境分流（[SERVER_ORIGIN]，debug=测试、release=生产），
 * 由 server-config.properties 构建注入，不向用户暴露。
 */
@Singleton
class AccountApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {

    /**
     * 登录图形验证码（服务端开关关闭时 [enabled]=false，登录不带验证码）。
     * [image] 为服务端 svg-captcha 的 SVG 原文（`<svg` 开头）或 base64 位图，
     * 与 Web 端 toDataUrl 的判别口径一致，展示层按前缀分流渲染。
     */
    data class Captcha(val enabled: Boolean, val uuid: String, val image: String)

    data class LoginResult(
        val accessToken: String,
        val refreshToken: String?,
        val expireInSeconds: Long,
        val refreshExpireInSeconds: Long?,
    )

    /**
     * 注册结果（`POST /api/v1/auth/register-company`）。
     * [activationRequired] 为 false 表示产品注册「注册即用」，不需要激活邮件；
     * [message] 为服务端成功文案（注册即用口径）。
     */
    data class RegisterResult(
        val tenantId: String?,
        val activationRequired: Boolean,
        val message: String?,
    )

    /**
     * 积分钱包（毫积分字符串，BigInt 序列化为字符串的契约）。
     * [pointsExpireAt] 为 ISO-8601 或 null（无过期）。
     */
    @Serializable
    data class Wallet(
        val pointsBalanceMilli: String,
        val pointsExpireAt: String? = null,
    )

    /** 卡密启用结果：入账凭证 + 钱包快照 + 该卡余额/到期（绑定后就地展示用）。 */
    data class CardInfo(
        val cardNo: String,
        val wallet: Wallet,
        val activatedAt: String?,
        val cardBalanceMilli: String,
        val cardExpireAt: String?,
    )

    /**
     * 我的已绑定卡密（[GET /api/v1/point-card/my-cards]，服务端实时值）。
     * 服务端只回**有效卡**（ACTIVE / 未过期 / 仍有余额）且按 `boundAt` 升序，
     * 首张即当前正在消耗的卡（账户总积分按绑定先后逐卡扣，用完自动溢到下一张）。
     * [balanceMilli] 为该卡剩余额度（毫积分字符串，由时间轴水位法派生；
     * 卡到 [expireAt] 后冻结剩余，不再参与扣点）。
     */
    @Serializable
    data class MyCard(
        val cardNo: String,
        val facePoints: Int,
        val balanceMilli: String,
        val expireAt: String? = null,
        val activatedAt: String? = null,
        val boundAt: String? = null,
        val status: String,
    )

    /** App 端输出精细度档位。 */
    @Serializable
    data class GatewayMode(
        val key: String,
        val label: String = "",
        val maxTokens: Int = 4096,
    )

    /** App 端模型目录项（服务端只回展示信息，无 key/上游地址）。 */
    @Serializable
    data class GatewayModel(
        val key: String,
        val displayName: String = "",
        val coefficientMilli: Int = 1000,
        val coefficientLabel: String = "",
        val supportsReasoning: Boolean = false,
        val modes: List<GatewayMode> = emptyList(),
    )

    /** App 端 LLM 网关配置（baseUrl 为相对路径，调用方拼绝对地址）。 */
    data class GatewayConfig(
        val baseUrl: String,
        val defaultModel: String,
        val models: List<GatewayModel>,
    )

    /** 当前服务端根地址（[SERVER_ORIGIN]，环境配置文件注入，用户不可见）。 */
    private fun origin(): String = SERVER_ORIGIN

    /** LiteLLM 网关 OpenAI 兼容前缀（nginx /llm/ 剥前缀转发，商业化方案 §6.3）。 */
    fun gatewayBaseUrl(): String = "${origin()}/llm/v1"

    /** 获取登录验证码；网络失败返回 disabled 验证码（登录由服务端兜底校验开关）。 */
    suspend fun fetchCaptcha(): Captcha = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${origin()}/api/v1/auth/code").get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val data = envelope(response.code, response.body?.string().orEmpty())
                val obj = data.jsonObject
                Captcha(
                    enabled = obj["captchaEnabled"]?.jsonPrimitive?.content == "true",
                    uuid = obj["uuid"]?.jsonPrimitive?.content.orEmpty(),
                    image = obj["img"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
        }.getOrElse { Captcha(enabled = false, uuid = "", image = "") }
    }

    suspend fun login(
        username: String,
        password: String,
        code: String?,
        uuid: String?,
    ): LoginResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("username", username)
            put("password", password)
            if (!code.isNullOrBlank()) put("code", code)
            if (!uuid.isNullOrBlank()) put("uuid", uuid)
            put("clientId", CLIENT_ID)
            put("grantType", GRANT_TYPE)
        }
        val request = Request.Builder()
            .url("${origin()}/api/v1/auth/login")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val data = envelope(response.code, response.body?.string().orEmpty())
                val obj = data.jsonObject
                val accessToken = obj["access_token"]?.jsonPrimitive?.content
                    ?: throw AccountException(REASON_BAD_RESPONSE)
                LoginResult(
                    accessToken = accessToken,
                    refreshToken = obj["refresh_token"]?.jsonPrimitive?.content,
                    expireInSeconds = obj["expire_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3_600L,
                    refreshExpireInSeconds = obj["refresh_expire_in"]?.jsonPrimitive?.content?.toLongOrNull(),
                )
            }
        } catch (e: AccountException) {
            throw e
        } catch (e: Exception) {
            throw AccountException(REASON_NETWORK, cause = e)
        }
    }

    /**
     * 注册好参谋账号（`POST /api/v1/auth/register-company`）。
     *
     * 只提交产品注册必需字段：邮箱（即登录账号）、密码、确认密码、图形验证码；
     * 昵称/企业名称/联系电话由服务端按邮箱前缀生成默认值。`productId=haocanmou`
     * 使注册即用（不绑 tenant_admin、不发激活邮件），只绑好参谋产品角色。
     * 服务端限流每分钟 5 次；失败抛 [AccountException]（[AccountException.serverMsg]
     * 为 DTO 校验/业务原因原文，UI 直接展示）。
     */
    suspend fun register(
        email: String,
        password: String,
        confirmPassword: String,
        code: String?,
        uuid: String?,
    ): RegisterResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("email", email)
            put("password", password)
            put("confirmPassword", confirmPassword)
            put("productId", PRODUCT_ID)
            if (!code.isNullOrBlank()) put("code", code)
            if (!uuid.isNullOrBlank()) put("uuid", uuid)
        }
        val request = Request.Builder()
            .url("${origin()}/api/v1/auth/register-company")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val message = runCatching {
                    json.parseToJsonElement(body).jsonObject["msg"]?.jsonPrimitive?.content
                }.getOrNull()
                val obj = envelope(response.code, body).jsonObject
                RegisterResult(
                    tenantId = obj["tenantId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    activationRequired = obj["activationRequired"]?.jsonPrimitive?.content != "false",
                    message = message?.takeIf { it.isNotBlank() },
                )
            }
        } catch (e: AccountException) {
            throw e
        } catch (e: Exception) {
            throw AccountException(REASON_NETWORK, cause = e)
        }
    }

    /**
     * 绑定使用卡密（商业化方案 §5.3：卡密=入账凭证+LiteLLM 虚拟 key 凭证）。
     * 单输入框只收卡密；绑定即归属当前账号（其他账号不可再绑），多卡积分累加、
     * 有效期取最晚；服务端幂等（同一账号重复绑定返回现状）。
     */
    suspend fun enableCard(accessToken: String, cardKey: String): CardInfo =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("cardKey", cardKey)
            }
            val request = Request.Builder()
                .url("${origin()}/api/v1/point-card/enable")
                .header("Authorization", "Bearer $accessToken")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val data = envelope(response.code, response.body?.string().orEmpty())
                    cardInfoOf(data.jsonObject)
                }
            } catch (e: AccountException) {
                throw e
            } catch (e: Exception) {
                throw AccountException(REASON_NETWORK, cause = e)
            }
        }

    /** 我的已绑定卡密列表（服务端实时余额/到期，进页面与刷新时拉取）。 */
    suspend fun fetchMyCards(accessToken: String): List<MyCard> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${origin()}/api/v1/point-card/my-cards")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val data = envelope(response.code, response.body?.string().orEmpty())
                data.jsonArray.map { element ->
                    val obj = element.jsonObject
                    MyCard(
                        cardNo = obj["cardNo"]?.jsonPrimitive?.content.orEmpty(),
                        facePoints = obj["facePoints"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        balanceMilli = obj["balanceMilli"]?.jsonPrimitive?.content.orEmpty(),
                        expireAt = obj.str("expireAt"),
                        activatedAt = obj.str("activatedAt"),
                        boundAt = obj.str("boundAt"),
                        status = obj["status"]?.jsonPrimitive?.content.orEmpty(),
                    )
                }
            }
        } catch (e: AccountException) {
            throw e
        } catch (e: Exception) {
            throw AccountException(REASON_NETWORK, cause = e)
        }
    }

    /**
     * App 端 LLM 网关配置（`GET /api/v1/point-card/gateway/config`，登录态）。
     * 服务端只回模型清单与网关相对路径，不含上游地址/密钥。
     */
    suspend fun fetchGatewayConfig(accessToken: String): GatewayConfig = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${origin()}/api/v1/point-card/gateway/config")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val data = envelope(response.code, response.body?.string().orEmpty())
                val obj = data.jsonObject
                GatewayConfig(
                    baseUrl = obj.str("baseUrl") ?: "/llm/v1",
                    defaultModel = obj.str("defaultModel") ?: "deepseek-flash",
                    models = obj["models"]?.jsonArray?.map { element ->
                        val item = element.jsonObject
                        GatewayModel(
                            key = item.str("key").orEmpty(),
                            displayName = item.str("displayName") ?: item.str("key").orEmpty(),
                            coefficientMilli = item["coefficientMilli"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1000,
                            coefficientLabel = item.str("coefficientLabel")
                                ?: formatCoefficient(item["coefficientMilli"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1000),
                            supportsReasoning = item["supportsReasoning"]?.jsonPrimitive?.content == "true",
                            modes = item["modes"]?.jsonArray?.mapNotNull { modeElement ->
                                val mode = modeElement.jsonObject
                                val modeKey = mode.str("key") ?: return@mapNotNull null
                                GatewayMode(
                                    key = modeKey,
                                    label = mode.str("label") ?: modeKey,
                                    maxTokens = mode["maxTokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4096,
                                )
                            }.orEmpty(),
                        )
                    }.orEmpty(),
                )
            }
        } catch (e: AccountException) {
            throw e
        } catch (e: Exception) {
            throw AccountException(REASON_NETWORK, cause = e)
        }
    }

    /** 查询积分钱包（毫积分字符串 + 过期时刻）。 */
    suspend fun fetchWallet(accessToken: String): Wallet = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${origin()}/api/v1/point-card/wallet")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val data = envelope(response.code, response.body?.string().orEmpty())
                walletOf(data.jsonObject)
            }
        } catch (e: AccountException) {
            throw e
        } catch (e: Exception) {
            throw AccountException(REASON_NETWORK, cause = e)
        }
    }

    /** 服务端未回 coefficientLabel 时的本地兜底格式（1000 → 1.0x）。 */
    private fun formatCoefficient(coefficientMilli: Int): String {
        val value = coefficientMilli / 1000.0
        return if (value % 1.0 == 0.0) String.format("%.1fx", value) else String.format("%.2fx", value)
    }

    private fun walletOf(obj: kotlinx.serialization.json.JsonObject): Wallet {
        val milli = obj["pointsBalanceMilli"]?.jsonPrimitive?.content
            ?: throw AccountException(REASON_BAD_RESPONSE)
        return Wallet(
            pointsBalanceMilli = milli,
            pointsExpireAt = obj.str("pointsExpireAt"),
        )
    }

    private fun cardInfoOf(obj: kotlinx.serialization.json.JsonObject): CardInfo = CardInfo(
        cardNo = obj["cardNo"]?.jsonPrimitive?.content.orEmpty(),
        wallet = walletOf(obj),
        activatedAt = obj.str("activatedAt"),
        cardBalanceMilli = obj["cardBalanceMilli"]?.jsonPrimitive?.content.orEmpty(),
        cardExpireAt = obj.str("cardExpireAt"),
    )

    /**
     * 取可空字符串字段：JSON null / 空串一律归一为 null
     * （JsonNull.content 会返回字面量 "null"，直接取会把「永不过期」显示成 null）。
     */
    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (get(key) as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.content
            ?.takeIf { it.isNotBlank() }

    /** 校验 HTTP 状态与 Result 信封，返回 data 字段（调用方再按需取值）。 */
    private fun envelope(httpCode: Int, body: String) = try {
        val root = json.parseToJsonElement(body).jsonObject
        val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull()
        // 401 = 会话失效（token 过期/被拒），单独归类，由展示层静默登出而非红字报错。
        if (httpCode == 401 || code == 401) {
            throw AccountException(
                REASON_UNAUTHORIZED,
                serverMsg = root["msg"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            )
        }
        if (httpCode !in 200..299 || code != 200) {
            throw AccountException(
                REASON_SERVER,
                serverMsg = root["msg"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            )
        }
        root["data"] ?: throw AccountException(REASON_BAD_RESPONSE)
    } catch (e: AccountException) {
        throw e
    } catch (e: Exception) {
        throw AccountException(REASON_BAD_RESPONSE, cause = e)
    }

    companion object {
        /**
         * 服务端站点根地址（登录/注册/Web 入口同一域名）。
         * 由 server-config.properties 经构建注入（debug=测试服务器，release=生产服务器，
         * server.env 可显式指定环境），用户不可见、无运行时入口。
         */
        val SERVER_ORIGIN: String = BuildConfig.SERVER_ORIGIN

        /**
         * 测试免登录开关（自动化测试专用）：服务器根地址非 https（测试环境明文联调）
         * 时才允许 `am start --es debugToken` 注入会话；生产 https 构建恒为 false，
         * 不留任何绕过登录门的入口。
         */
        val TEST_LOGIN_ENABLED: Boolean = !SERVER_ORIGIN.startsWith("https://")

        /** 好参谋 App 在服务端的客户端标识（登录审计/限流按客户端区分）。 */
        const val CLIENT_ID = "haocanmou"

        /** 注册产品标识：好参谋（服务端据此只绑产品角色并跳过激活邮件）。 */
        const val PRODUCT_ID = "haocanmou"

        /**
         * 充值/购买商品页（单一配置点）：当前链接为示例商品，后续更换点卡商品时
         * 只需替换本 URL（同商城同类商品页）。余额不足「充值」、购买分享与
         * 「浏览器购买」统一使用它；页面自带的 OG 标签决定社交卡片内容。
         */
        const val RECHARGE_URL = "https://www.80fafa.com/Goods/EB827AC2D47CA617"

        const val GRANT_TYPE = "password"

        /** 错误原因常量（展示层映射 strings.xml 文案）。 */
        const val REASON_NETWORK = "REASON_NETWORK"
        const val REASON_SERVER = "REASON_SERVER"
        const val REASON_UNAUTHORIZED = "REASON_UNAUTHORIZED"
        const val REASON_BAD_RESPONSE = "REASON_BAD_RESPONSE"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
