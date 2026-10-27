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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import com.verlintas.baic2.device.api.OcrLine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天页时间分割胶囊过滤（2026-10-27 真机「时间戳当来信」事故回归）：
 * 我方气泡被 OwnBubbleLocator 剔除后，只剩时间戳胶囊的屏幕会把
 * 「对方说了什么」变成 `|上午10:46 / 上午10:52`，白跑一整个 LLM 回合
 * 并把「屏幕只有时间戳」的垃圾回复写进会话历史。
 */
class OcrNoiseFilterTest {

    private val rules = CompiledNoiseRules(
        linePatterns = listOf(Regex(OcrNoiseFilter.TIMESTAMP_LINE), Regex("""^\d{1,2}:\d{2}$""")),
        blockedWords = listOf("对方正在输入"),
        keep = listOf(Regex("""^(今天|昨天|前天|星期[一二三四五六日天])$""")),
    )

    private fun line(text: String) = OcrLine(text = text, left = 0, top = 0, right = 10, bottom = 10)

    private fun kept(vararg texts: String): List<String> =
        OcrNoiseFilter.clean(texts.map { line(it) }, rules).map { it.text }

    @Test
    fun `drops chat time separator pills`() {
        // 真机两次事故的原文（10:46 与 10:52 回合）。
        assertEquals(emptyList<String>(), kept("上午10:19", "上午10:46", "|上午10:46", "上午10:52"))
        assertEquals(emptyList<String>(), kept("凌晨2:13", "下午 3:05", "昨天21:30", "10:46", "10:46:30"))
    }

    @Test
    fun `keeps real messages and date context`() {
        assertEquals(
            listOf("你上次说的那个质量复盘，什么时候能给我看看？", "很好"),
            kept("你上次说的那个质量复盘，什么时候能给我看看？", "很好"),
        )
        // 纯日期分割行是有效上下文（keepPatterns 优先于 linePatterns）。
        assertEquals(listOf("今天", "昨天"), kept("今天", "昨天"))
    }

    @Test
    fun `does not drop lines that merely contain a time`() {
        assertEquals(
            listOf("我10:46到会议室", "复盘 10:52 发您"),
            kept("我10:46到会议室", "复盘 10:52 发您"),
        )
    }

    /**
     * 生产路径读的是 assets/advisor/noise_rules.json（[NoiseRules]），Kotlin 常量只是
     * 资产缺失时的兜底——两条路径必须同规则，否则真机永远跑不到修复。
     */
    @Test
    fun `asset json carries the same timestamp rule`() {
        val asset = listOf(
            File("src/main/assets/advisor/noise_rules.json"),
            File("app/src/main/assets/advisor/noise_rules.json"),
        ).firstOrNull { it.isFile }
        assertNotNull("noise_rules.json not found from ${File(".").absolutePath}", asset)
        val json = asset!!.readText()
        val escaped = OcrNoiseFilter.TIMESTAMP_LINE.replace("\\", "\\\\")
        assertTrue("timestamp rule missing in asset json", json.contains(escaped))
    }
}