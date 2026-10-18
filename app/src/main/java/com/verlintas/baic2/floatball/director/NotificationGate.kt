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

package com.verlintas.baic2.floatball.director

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 一条已解析的聊天通知。文本即取即用，禁止落盘/写日志/进任何持久化。 */
data class IncomingNotification(
    val packageName: String,
    /** 通知标题原文（联系人名候选，规范化在 ContactRepository.resolve 内完成）。 */
    val contactTitle: String,
    val body: String,
)

/**
 * 通知→起草档的准入闸门（M2-a，方案 §8.1 N1~N2）。全部为纯逻辑：
 * 包名白名单、标题/正文解析、同联系人节流。不触碰 Android 框架类型，
 * JVM 可直接单测；时间由调用方注入便于测试。
 */
@Singleton
class NotificationGate @Inject constructor() {

    private val lastDispatchAt = ConcurrentHashMap<String, Long>()

    /** 是否监听该包名的通知：只认内置白名单常量组（方案未配置化前硬编码）。 */
    fun isListenedPackage(packageName: String?): Boolean =
        packageName != null && packageName in LISTENED_PACKAGES

    /**
     * 解析通知的联系人名与消息正文；不足以进入起草档（标题/正文全空、
     * 标题是「N 条新消息」类聚合文案）时返回 null。
     */
    fun parse(packageName: String, title: CharSequence?, text: CharSequence?): IncomingNotification? {
        val contactTitle = title?.toString()?.trim().orEmpty()
        val body = text?.toString()?.trim().orEmpty()
        if (contactTitle.isEmpty() || body.isEmpty()) return null
        if (contactTitle.matches(GENERIC_TITLE)) return null
        return IncomingNotification(
            packageName = packageName,
            contactTitle = contactTitle,
            body = body,
        )
    }

    /**
     * 同一联系人（包名+标题键）在 [throttleMs] 窗口内只放行一次；通过即记录
     * 本次时间戳。窗口与容量上限均在内存中，进程重启即清。
     */
    fun shouldDispatch(key: String, nowMs: Long, throttleMs: Long = DEFAULT_THROTTLE_MS): Boolean {
        if (throttleMs <= 0L) return true
        val last = lastDispatchAt[key]
        if (last != null && nowMs - last < throttleMs) return false
        lastDispatchAt[key] = nowMs
        if (lastDispatchAt.size > KEY_CAP) {
            val staleBefore = nowMs - throttleMs
            lastDispatchAt.entries.removeIf { it.value < staleBefore }
        }
        return true
    }

    internal companion object {
        /**
         * 常见 IM 包名白名单（M2-a 起草档的感知面；通知文本即取即用，
         * 只处理聊天类通知，其余应用一概不进管线）。v10 起同为 SceneGate
         * 的场景白名单同源常量（方案 §1.3），改这里两处同步生效。
         */
        internal val LISTENED_PACKAGES = setOf(
            "com.tencent.mm",            // 微信
            "com.tencent.mobileqq",      // QQ
            "com.tencent.tim",           // TIM
            "com.tencent.wework",        // 企业微信
            "com.alibaba.android.rimet", // 钉钉
            "com.ss.android.lark",       // 飞书
            "org.telegram.messenger",    // Telegram
        )

        /** 聚合推送的泛化标题（如「3 条新消息」）不是联系人名。 */
        val GENERIC_TITLE = Regex("""^\d*\s*条(新)?消息$|^[^：:]{0,6}新消息$""")

        /** 同联系人重复通知的合并窗口：窗口内的后续通知直接忽略。 */
        const val DEFAULT_THROTTLE_MS = 90_000L

        /** 节流键容量上限，超过先清过期项（防长期驻留膨胀）。 */
        const val KEY_CAP = 128
    }
}
