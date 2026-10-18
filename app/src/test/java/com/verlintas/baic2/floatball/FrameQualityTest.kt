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
import org.junit.Test

/**
 * OCR 文本相似度纯函数（F2 签名复用入口）：[FrameQuality.shingles] 与
 * [FrameQuality.similarityOf] 必须与既有 [FrameQuality.textSimilarity] 同口径，
 * 供会话复用回看在候选循环外只算一次签名逐候选复用。
 */
class FrameQualityTest {

    @Test
    fun shingles_buildsCharBigrams() {
        assertEquals(setOf("ab", "bc", "cd"), FrameQuality.shingles("abcd"))
        // 空白不进二元组：换行/空格剥除后与紧凑串同签名。
        assertEquals(FrameQuality.shingles("你好\n世界"), FrameQuality.shingles("你好世界"))
    }

    @Test
    fun shingles_blankOrSingleCharIsEmpty() {
        // 少于 2 个有效字符无二元组。
        assertEquals(emptySet<String>(), FrameQuality.shingles("x"))
        assertEquals(emptySet<String>(), FrameQuality.shingles(""))
        assertEquals(emptySet<String>(), FrameQuality.shingles("   \n "))
    }

    @Test
    fun similarityOf_matchesTextSimilarityExactly() {
        val current = "报价多少？上次说的方案能发我吗"
        val near = "报价多少？上次说的方案能发我看看吗"
        val far = "周末天气不错，一起去爬山吗"
        assertEquals(
            FrameQuality.textSimilarity(current, near),
            FrameQuality.similarityOf(FrameQuality.shingles(current), FrameQuality.shingles(near)),
            0.0,
        )
        assertEquals(
            FrameQuality.textSimilarity(current, far),
            FrameQuality.similarityOf(FrameQuality.shingles(current), FrameQuality.shingles(far)),
            0.0,
        )
    }

    @Test
    fun signatureReuse_oneSignatureManyCandidates() {
        // F2 复用模式：固定一侧签名算一次，逐候选 similarityOf 与逐对
        // textSimilarity 结果一致（省掉候选数-1 次重复 shingle）。
        val current = "在吗？想问下上次的报价单"
        val candidates = listOf(
            "在吗？想问下上次的报价单",
            "在吗",
            "明天例会改到下午三点，请知悉",
        )
        val signature = FrameQuality.shingles(current)
        candidates.forEach { candidate ->
            assertEquals(
                FrameQuality.textSimilarity(current, candidate),
                FrameQuality.similarityOf(signature, FrameQuality.shingles(candidate)),
                0.0,
            )
        }
    }

    @Test
    fun similarityOf_identicalOneAndEmptySideZero() {
        val text = "同一句话没有变化"
        assertEquals(
            1.0,
            FrameQuality.similarityOf(FrameQuality.shingles(text), FrameQuality.shingles(text)),
            0.0,
        )
        // 任一侧空签名（含单字符输入）→ 0.0，与 textSimilarity 同口径。
        assertEquals(0.0, FrameQuality.similarityOf(emptySet(), FrameQuality.shingles(text)), 0.0)
        assertEquals(0.0, FrameQuality.similarityOf(FrameQuality.shingles(text), emptySet()), 0.0)
        assertEquals(0.0, FrameQuality.textSimilarity("a", "a"), 0.0)
    }

    @Test
    fun shingles_capsCompactTextAt4000() {
        // 超长文本截到前 4000 紧凑字符：5000 字周期串与前 4000 字同签名。
        val period = "abcdefghij"
        assertEquals(
            FrameQuality.shingles(period.repeat(500)),
            FrameQuality.shingles(period.repeat(400)),
        )
    }
}
