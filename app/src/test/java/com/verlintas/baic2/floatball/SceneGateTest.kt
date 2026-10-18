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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneGateTest {

    private val chatBody = listOf(
        "昨天 14:30",
        "这周末有空一起吃饭吗",
        "发送",
    ).joinToString("\n")

    private val nonChatBody = listOf(
        "英伟达发布新一代消费级芯片",
        "算力提升百分之四十，功耗下降三成",
        "首批合作厂商名单将于下月公布",
    ).joinToString("\n")

    @Test
    fun whitelistPackageWithChatStructureClassifiesChat() {
        assertEquals(
            SceneGate.Scene.Chat,
            SceneGate.classify("com.tencent.mm", chatBody),
        )
    }

    @Test
    fun nonWhitelistPackageClassifiesNonChatEvenWithStructure() {
        // 浏览器里渲染出的聊天记录截图不能当聊天屏：白名单一票否决。
        assertEquals(
            SceneGate.Scene.NonChat,
            SceneGate.classify("com.android.chrome", chatBody),
        )
        assertEquals(
            SceneGate.Scene.NonChat,
            SceneGate.classify("com.android.launcher", nonChatBody),
        )
    }

    @Test
    fun whitelistPackageWithoutStructureIsUncertain() {
        val settings = listOf(
            "设置",
            "新消息通知",
            "隐私",
            "通用",
        ).joinToString("\n")
        assertEquals(
            SceneGate.Scene.Uncertain,
            SceneGate.classify("com.tencent.mm", settings),
        )
    }

    @Test
    fun missingPackageWithChatStructureIsUncertain() {
        assertEquals(SceneGate.Scene.Uncertain, SceneGate.classify(null, chatBody))
        assertEquals(SceneGate.Scene.Uncertain, SceneGate.classify("", chatBody))
    }

    @Test
    fun missingPackageWithoutStructureIsNonChat() {
        assertEquals(SceneGate.Scene.NonChat, SceneGate.classify(null, nonChatBody))
        assertEquals(SceneGate.Scene.NonChat, SceneGate.classify(null, null))
        assertEquals(SceneGate.Scene.NonChat, SceneGate.classify(null, ""))
    }

    // ── F10：UNCERTAIN 轻 LLM 一问的提示词与解析（UncertainPrompts 纯函数） ──

    @Test
    fun uncertainPrompts_bodyIncludedAndCapped() {
        val prompt = UncertainPrompts.userPrompt("张三：在吗\n我：在的")
        assertTrue(prompt.contains("张三：在吗"))
        assertTrue(prompt.contains("yes"))
        // 超长正文截断到 BODY_MAX_CHARS，判定界面形态足够。
        val long = "x".repeat(UncertainPrompts.BODY_MAX_CHARS + 50)
        assertTrue(UncertainPrompts.userPrompt(long).length < long.length)
    }

    @Test
    fun uncertainPrompts_isChatOnlyOnExplicitYes() {
        // 明确 yes（含大小写/前后缀空白）才判聊天。
        assertTrue(UncertainPrompts.isChat("yes"))
        assertTrue(UncertainPrompts.isChat("  Yes  "))
        assertTrue(UncertainPrompts.isChat("Yes, it looks like a chat"))
        assertTrue(UncertainPrompts.isChat("是"))
        // no/超时截断/畸形一律保守 false（NON_CHAT）。
        assertEquals(false, UncertainPrompts.isChat("no"))
        assertEquals(false, UncertainPrompts.isChat("NO"))
        assertEquals(false, UncertainPrompts.isChat("not sure"))
        assertEquals(false, UncertainPrompts.isChat("maybe"))
        assertEquals(false, UncertainPrompts.isChat(""))
        assertEquals(false, UncertainPrompts.isChat(null))
    }
}
