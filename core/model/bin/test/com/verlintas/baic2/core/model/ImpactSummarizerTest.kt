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

package com.verlintas.baic2.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImpactSummarizerTest {

    private fun assistant(vararg calls: ToolCall) = ChatMessage(
        role = ChatRole.ASSISTANT,
        toolCalls = calls.toList(),
    )

    private fun call(name: String, args: String) = ToolCall(
        id = "c-$name-${args.hashCode()}",
        name = name,
        argumentsJson = args,
        status = ToolCallStatus.DONE,
    )

    @Test
    fun describesCommonToolActions() {
        val lines = ImpactSummarizer.summarize(
            listOf(
                assistant(
                    call("open_app", """{"name":"设置"}"""),
                    call("ui_control", """{"action":"tap","text":"Wi-Fi"}"""),
                    call("ui_control", """{"action":"type","value":"hello world"}"""),
                    call("file_write", """{"action":"write","name":"report.md"}"""),
                    call("run_shell", """{"command":"pm clear com.demo"}"""),
                    call("web_search", """{"query":["a","b"]}"""),
                ),
            ),
        )
        assertEquals(6, lines.size)
        assertTrue(lines[0].contains("打开应用"))
        assertTrue(lines[1].contains("点击"))
        assertTrue(lines[2].contains("输入文本"))
        assertTrue(lines[3].contains("写入文件"))
        assertTrue(lines[4].contains("pm clear"))
        assertTrue(lines[5].contains("a | b"))
    }

    @Test
    fun skipsReadOnlyNoiseAndDeduplicates() {
        val lines = ImpactSummarizer.summarize(
            listOf(
                assistant(
                    call("device_info", "{}"),
                    call("take_screenshot", "{}"),
                    call("take_screenshot", "{}"),
                    call("plan_update", """{"steps":[]}"""),
                ),
            ),
        )
        assertEquals(listOf("截屏"), lines)
    }

    @Test
    fun capsTheSummaryLength() {
        val calls = (1..20).map { call("set_volume", """{"level":$it}""") }
        val lines = ImpactSummarizer.summarize(listOf(assistant(*calls.toTypedArray())), limit = 5)
        assertEquals(5, lines.size)
    }
}
