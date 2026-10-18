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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NotificationGate 纯函数（M2-a 方案 §8.1 N1~N2 / §8.3 隐私红线）：
 * 包名白名单、标题/正文解析（泛化聚合标题剔除）、同联系人节流窗口。
 */
class NotificationGateTest {

    private val gate = NotificationGate()

    // ── 包名白名单 ─────────────────────────────────────────────────────

    @Test
    fun whitelistedImPackages_pass() {
        assertTrue(gate.isListenedPackage("com.tencent.mm"))
        assertTrue(gate.isListenedPackage("com.tencent.mobileqq"))
        assertTrue(gate.isListenedPackage("com.tencent.tim"))
        assertTrue(gate.isListenedPackage("com.tencent.wework"))
        assertTrue(gate.isListenedPackage("com.alibaba.android.rimet"))
        assertTrue(gate.isListenedPackage("com.ss.android.lark"))
        assertTrue(gate.isListenedPackage("org.telegram.messenger"))
    }

    @Test
    fun nonImPackagesAndNull_rejected() {
        assertFalse(gate.isListenedPackage("com.android.chrome"))
        assertFalse(gate.isListenedPackage("com.verlintas.baic2"))
        assertFalse(gate.isListenedPackage("com.tencent.mm.fake"))
        assertFalse(gate.isListenedPackage(null))
    }

    // ── 标题/正文解析 ──────────────────────────────────────────────────

    @Test
    fun normalNotification_parsesTitleAndBody() {
        val parsed = gate.parse("com.tencent.mm", "张三", "在吗？")
        assertNotNull(parsed)
        assertEquals("com.tencent.mm", parsed!!.packageName)
        assertEquals("张三", parsed.contactTitle)
        assertEquals("在吗？", parsed.body)
    }

    @Test
    fun emptyTitleOrBody_rejected() {
        assertNull(gate.parse("com.tencent.mm", "张三", null))
        assertNull(gate.parse("com.tencent.mm", "张三", "   "))
        assertNull(gate.parse("com.tencent.mm", null, "在吗"))
        assertNull(gate.parse("com.tencent.mm", "", "在吗"))
    }

    @Test
    fun genericAggregateTitles_rejected() {
        // 聚合推送的泛化标题不是联系人名，不得进起草档。
        assertNull(gate.parse("com.tencent.mobileqq", "3 条新消息", "张三：你好"))
        assertNull(gate.parse("com.tencent.mobileqq", "条新消息", "张三：你好"))
        assertNull(gate.parse("com.tencent.mobileqq", "QQ新消息", "正文"))
        // 普通人名不受影响。
        assertNotNull(gate.parse("com.tencent.mobileqq", "张三", "正文"))
    }

    @Test
    fun titleAndBody_trimmed() {
        val parsed = gate.parse("com.tencent.mm", "  张三  ", "\n在吗\n")
        assertNotNull(parsed)
        assertEquals("张三", parsed!!.contactTitle)
        assertEquals("在吗", parsed.body)
    }

    // ── 同联系人节流 ──────────────────────────────────────────────────

    @Test
    fun firstDispatchPasses_thenBlockedWithinWindow_thenPassesAfter() {
        val g = NotificationGate()
        val key = "com.tencent.mm|张三"
        assertTrue(g.shouldDispatch(key, nowMs = 1_000L))
        // 窗口内（90s）的重复通知直接忽略。
        assertFalse(g.shouldDispatch(key, nowMs = 1_000L + 89_999L))
        // 窗口过后重新放行。
        assertTrue(g.shouldDispatch(key, nowMs = 1_000L + 90_000L))
    }

    @Test
    fun differentContacts_throttleIndependently() {
        val g = NotificationGate()
        assertTrue(g.shouldDispatch("com.tencent.mm|张三", nowMs = 1_000L))
        assertTrue(g.shouldDispatch("com.tencent.mm|李四", nowMs = 1_000L))
        assertTrue(g.shouldDispatch("com.tencent.mobileqq|张三", nowMs = 1_000L))
    }

    @Test
    fun nonPositiveThrottle_alwaysPasses() {
        val g = NotificationGate()
        assertTrue(g.shouldDispatch("k", nowMs = 1_000L, throttleMs = 0L))
        assertTrue(g.shouldDispatch("k", nowMs = 1_000L, throttleMs = -1L))
    }
}
