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

package com.verlintas.baic2.floatball.auto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合规改写提示词契约（2026-10-27 真机修复）：改写调用必须带上「待改写稿」与
 * 「必须避免」的约束，且输出仍走 `<reply>` 单块协议——否则 Gatekeeper 复审
 * 拿到的可能仍是带承诺词的分析稿。
 */
class ReplyComposerPromptTest {

    @Test
    fun `rewriteUserPrompt 携带来信待改写稿与约束`() {
        val prompt = ReplyComposer.rewriteUserPrompt(
            draft = "领导，明天工作时间发您过目。",
            incoming = "很好",
            constraints = "不要出现时间承诺词",
            answerTargetChars = 260,
        )
        assertTrue(prompt.contains("很好"))
        assertTrue(prompt.contains("领导，明天工作时间发您过目。"))
        assertTrue(prompt.contains("必须避免："))
        assertTrue(prompt.contains("不要出现时间承诺词"))
        assertTrue(prompt.contains("只输出 <reply> 块"))
    }

    @Test
    fun `rewriteUserPrompt 无来信时标明主动开场`() {
        val prompt = ReplyComposer.rewriteUserPrompt(
            draft = "领导，本周的复盘我整理好了。",
            incoming = null,
            constraints = "不要出现时间承诺词",
            answerTargetChars = 120,
        )
        assertTrue(prompt.contains("主动开场"))
        assertTrue(prompt.contains("控制在 120 字以内"))
    }

    @Test
    fun `rewrite 系统提示词仍锁单块 reply 协议`() {
        assertTrue(ReplyComposer.REWRITE_SYSTEM_PROMPT.contains("<reply>"))
        assertTrue(ReplyComposer.REWRITE_SYSTEM_PROMPT.contains("不新增原稿没有的事实"))
        assertFalse(ReplyComposer.REWRITE_SYSTEM_PROMPT.contains("<analysis>"))
    }
}
