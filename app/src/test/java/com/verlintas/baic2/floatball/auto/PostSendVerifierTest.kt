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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PostSendVerifier] 纯函数与回读时序（0 延迟注入）：回读片段截取、一次重查、
 * 宁降级不假成功（托管方案 §四.A.3）。
 */
class PostSendVerifierTest {

    @Test
    fun clipOf_takesFirstNonBlankLine() {
        val clip = PostSendVerifier.clipOf("  \n分析内容\n第二行\n<reply>好啊</reply>")
        assertEquals("分析内容", clip)
    }

    @Test
    fun clipOf_longLine_cappedAt8() {
        val clip = PostSendVerifier.clipOf("一二三四五六七八九十一二三四五六七八九")
        assertEquals(8, clip.length)
        assertEquals("一二三四五六七八", clip)
    }

    @Test
    fun clipsOf_addsShorterFallback() {
        // 主片段 8 字含 IME 空格/易错尾巴时，6 字备选去掉尾部降低 OCR 误判。
        val clips = PostSendVerifier.clipsOf("吴总，自动炒股 v1 我这两天")
        assertEquals(listOf("吴总，自动炒股", "吴总，自动炒"), clips)
    }

    @Test
    fun clipsOf_shortLine_dedupes() {
        assertEquals(listOf("好啊"), PostSendVerifier.clipsOf("好啊"))
        assertTrue(PostSendVerifier.clipsOf("  \n \n").isEmpty())
    }

    @Test
    fun clipOf_extractedDraft_takesPrefix() {
        // 派发侧传入的是抽取后的纯回复（无标记），回读片段即其前缀。
        val clip = PostSendVerifier.clipOf("好啊，那就这么定，周末见！")
        assertEquals("好啊，那就这么定", clip)
    }

    @Test
    fun clipOf_blankDraft_empty() {
        assertEquals("", PostSendVerifier.clipOf(""))
        assertEquals("", PostSendVerifier.clipOf("   \n \n"))
    }

    /** 计数证据源：前 [misses] 次未命中，其后命中。 */
    private class CountingFinder(private var misses: Int) : SendEvidenceFinder {
        var queried = 0
            private set
        override suspend fun contains(text: String): Boolean {
            queried++
            if (misses > 0) {
                misses--
                return false
            }
            return true
        }
    }

    @Test
    fun verify_hitFirstTry() = runBlocking {
        val finder = CountingFinder(misses = 0)
        assertTrue(PostSendVerifier.verify(finder, "好啊", delayMs = 0, retryMs = 0))
        assertEquals(1, finder.queried)
    }

    @Test
    fun verify_hitOnRetry() = runBlocking {
        val finder = CountingFinder(misses = 1)
        assertTrue(PostSendVerifier.verify(finder, "好啊", delayMs = 0, retryMs = 0))
        assertEquals(2, finder.queried)
    }

    @Test
    fun verify_missTwice_fails() = runBlocking {
        val finder = CountingFinder(misses = 2)
        assertFalse(PostSendVerifier.verify(finder, "好啊", delayMs = 0, retryMs = 0))
        assertEquals(2, finder.queried)
    }

    @Test
    fun verify_blankClip_shortCircuitsTrue() = runBlocking {
        val finder = CountingFinder(misses = 5)
        assertTrue(PostSendVerifier.verify(finder, "", delayMs = 0, retryMs = 0))
        assertEquals(0, finder.queried)
    }

    /** 只对指定片段命中的证据源（模拟 OCR 对主片段识别失败、备选命中）。 */
    private class TextFinder(private val hit: String) : SendEvidenceFinder {
        var queried = 0
            private set
        override suspend fun contains(text: String): Boolean {
            queried++
            return text == hit
        }
    }

    @Test
    fun verifyList_fallsBackToShorterClip() = runBlocking {
        val finder = TextFinder(hit = "吴总，自动炒")
        val clips = PostSendVerifier.clipsOf("吴总，自动炒股 v1 我这两天")
        assertTrue(PostSendVerifier.verify(finder, clips, delayMs = 0, retryMs = 0))
        assertEquals(2, finder.queried)
    }

    @Test
    fun verifyList_missesTwicePerRound_fails() = runBlocking {
        val finder = TextFinder(hit = "完全不存在的片段")
        val clips = PostSendVerifier.clipsOf("吴总，自动炒股 v1 我这两天")
        assertFalse(PostSendVerifier.verify(finder, clips, delayMs = 0, retryMs = 0))
        assertEquals(4, finder.queried)
    }
}
