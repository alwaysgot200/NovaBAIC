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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.auto

import android.app.PendingIntent
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.floatball.SceneGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * 会话导航器（托管方案 §四.A.2）：用 IM 通知自带的 `contentIntent`
 * （点通知直达会话的 PendingIntent，Android 生态里打开指定聊天最可靠的钥匙）
 * 把目标 IM 拉到前台会话页，替 SendSequence 铺平「前台校验」前置。
 *
 * - **缓存**：[AdvisorNotificationListener 侧][com.verlintas.baic2.floatball.director.AdvisorNotificationListener]
 *   在解析通知时捕获 contentIntent 交 [remember]，联系人解析成功后 [bindContact]
 *   补挂 contactId 键——**内存即取即用，不落盘不序列化不进日志**
 *   （contentIntent 与通知文本同隐私红线），LRU 有界防泄漏；
 * - **拉起**：派发时前台包名 ≠ imPackage → `send()` → 轮询（≤[NAV_TIMEOUT_MS]）
 *   前台包名 == imPackage，且 [SceneGate] 判 CHAT 双确认——判 NON_CHAT 拒绝，
 *   判 UNCERTAIN 时以「包名已在 IM 白名单且前台包名精确匹配」放行（聊天页
 *   节点结构多变，阻塞 UNCERTAIN 会把主路径频繁打落起草档）；
 * - **失败**：任何失败（无缓存/发送异常/超时/场景不符）由调用方按
 *   [REASON_NAV_FAILED] 系降级起草档，绝不盲导航。
 *
 * 兜底路径（launcher intent + a11y 找会话标题点击）A 期不做——盲导航错进
 * 会话的风险大于收益（方案 §四.A.2 明示）。
 */
/** key 格式：`t|<通知原始标题>`（捕获时）与 `c|<contactId>`（解析后补挂）。 */
internal class NavIntentCache(private val cap: Int = ImNavigator.CACHE_CAP) {

    private val map = object : LinkedHashMap<String, PendingIntent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>) =
            size > cap
    }

    /** 捕获通知 contentIntent（null 忽略）。 */
    fun remember(imPackage: String, rawTitle: String, intent: PendingIntent?) {
        if (intent == null) return
        synchronized(map) { map[keyTitle(imPackage, rawTitle)] = intent }
    }

    /** 联系人解析成功后补挂 contactId 键（同一 PendingIntent 双键别名）。 */
    fun bindContact(imPackage: String, contactId: Long, rawTitle: String) {
        val intent = synchronized(map) { map[keyTitle(imPackage, rawTitle)] } ?: return
        synchronized(map) { map[keyContact(imPackage, contactId)] = intent }
    }

    /** 派发用查询：contactId 键优先，[rawTitleHint] 兜底（通知标题常等于显示名）。 */
    fun lookup(imPackage: String, contactId: Long, rawTitleHint: String?): PendingIntent? =
        synchronized(map) {
            map[keyContact(imPackage, contactId)]
                ?: rawTitleHint?.takeIf { it.isNotBlank() }?.let { map[keyTitle(imPackage, it)] }
        }

    private fun keyTitle(imPackage: String, rawTitle: String) = "$imPackage|t|$rawTitle"

    private fun keyContact(imPackage: String, contactId: Long) = "$imPackage|c|$contactId"
}

@Singleton
class ImNavigator @Inject constructor(
    private val bridge: AccessibilityBridge,
    private val shizukuInjector: ShizukuInjector,
) {

    enum class NavOutcome {

        /** 前台已到目标 IM 且场景双确认通过。 */
        NAVIGATED,

        /** 缓存无该联系人的 contentIntent（通知未捕获/已被 LRU 淘汰）。 */
        NO_INTENT,

        /** PendingIntent 已失效或发送抛异常。 */
        SEND_FAILED,

        /** 超时内前台未到目标 IM。 */
        TIMEOUT,

        /** 前台到了目标 IM，但 SceneGate 判 NON_CHAT（落在非会话页）。 */
        WRONG_SCENE,
    }

    data class NavResult(val outcome: NavOutcome, val detail: String = "")

    private val cache = NavIntentCache()

    /** 捕获通知 contentIntent（onNotificationPosted 侧调用；null 忽略）。 */
    fun remember(imPackage: String, rawTitle: String, intent: PendingIntent?) =
        cache.remember(imPackage, rawTitle, intent)

    /** 联系人解析成功后补挂 contactId 键（同一 PendingIntent 双键别名）。 */
    fun bindContact(imPackage: String, contactId: Long, rawTitle: String) =
        cache.bindContact(imPackage, contactId, rawTitle)

    /**
     * 拉起目标会话并等待就位。前台探针按通道可用性自选（a11y 优先，免 a11y 走
     * Shizuku dumpsys）；两通道皆不可用 = 模式 C，直接判 [NavOutcome.NO_INTENT]
     * 同径降级（纯感知无导航能力）。
     */
    suspend fun navigate(
        imPackage: String,
        contactId: Long,
        rawTitleHint: String? = null,
        timeoutMs: Long = NAV_TIMEOUT_MS,
    ): NavResult {
        val intent = cache.lookup(imPackage, contactId, rawTitleHint)
            ?: return NavResult(NavOutcome.NO_INTENT)
        val sent = runCatching { intent.send() }.isSuccess
        if (!sent) return NavResult(NavOutcome.SEND_FAILED)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val fg = currentForeground()
            if (fg == imPackage) {
                // 双确认：前台包名精确匹配已过，SceneGate 复核页面结构
                //（NON_CHAT 拒绝；UNCERTAIN 以包名白名单语义放行，见类 KDoc）。
                val scene = SceneGate.classify(fg, runCatching { bridge.screenText(200) }.getOrNull())
                return when (scene) {
                    SceneGate.Scene.Chat -> NavResult(NavOutcome.NAVIGATED, "scene=chat")
                    SceneGate.Scene.Uncertain ->
                        NavResult(NavOutcome.NAVIGATED, "scene=uncertain_pkg_match")
                    SceneGate.Scene.NonChat ->
                        NavResult(NavOutcome.WRONG_SCENE, "scene=non_chat")
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        return NavResult(NavOutcome.TIMEOUT, "fg_wait_timeout")
    }

    private suspend fun currentForeground(): String? =
        if (bridge.connected.value) {
            bridge.foregroundPackage()
        } else if (shizukuInjector.available) {
            shizukuInjector.foregroundPackage()
        } else {
            null
        }

    companion object {

        const val REASON_NAV_FAILED = "nav_failed"

        /** 拉起后等待前台就位的总时长（方案 §四.A.2：约 5s）。 */
        const val NAV_TIMEOUT_MS = 5_000L

        /** 前台轮询间隔。 */
        const val POLL_INTERVAL_MS = 250L

        /** LRU 容量：PendingIntent 只存会话跳转键，量级小，32 足够。 */
        const val CACHE_CAP = 32
    }
}
