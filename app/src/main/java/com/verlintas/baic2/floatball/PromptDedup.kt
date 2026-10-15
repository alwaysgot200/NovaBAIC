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

import com.verlintas.baic2.core.model.Note
import com.verlintas.baic2.core.model.NoteKind

/**
 * 提示词跨段去重（v4 方案 §8.2，修 D5）：画像双写上游后，同一条事实会在
 * 人设段（ChatBriefBuilder）、交往脉络段（recallByEntity/due/dealFacts）与
 * 通用召回（memoryContext 的 primed）三处重复，重复还会被模型误当强调权重。
 *
 * 两级判重：
 *  1. [Note.id] 精确去重——同一笔记被多条检索通道取回时只保留优先级最高处；
 *  2. 文本指纹（数字串 + CJK 二元组 + 拉丁词，剔除高频泛化二元组）——
 *     画像值是笔记原文的子串、或两条事实共享大部分指纹时判同事实。
 *
 * 保留优先级（方案 §8.2）：承诺/约定 ＞ 当前相关画像 ＞ 交往脉络 ＞ 通用笔记。
 * 调用方负责按该顺序排列输入；本对象只做保序过滤，纯函数、无 Android 依赖。
 */
internal object PromptDedup {

    /** 指纹重合度阈值：较短一方指纹被覆盖 ≥ 此比例视为同事实。 */
    private const val OVERLAP_DUP = 0.5

    /** 包含判定要求较短一方至少有多少个指纹（防 2~3 字泛化值误杀）。 */
    private const val MIN_SMALL_FINGERPRINTS = 2

    /** 画像值短于此规范化长度不参与包含判定（3 个 CJK 字符才有 2 个二元组）。 */
    private const val MIN_VALUE_CHARS = 3
    private const val NUMBER_PREFIX = "n:"

    private val NUMBERS = Regex("""\d+(?:\.\d+)?""")
    private val LATIN_WORD = Regex("""[a-z]{3,}""")
    // 平假名/片假名 + CJK 扩展 A + CJK 统一表意文字 + 兼容表意文字（不含标点）。
    private val CJK_RUN =
        Regex("""[\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]+""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    /** 跨段出现、对区分事实无贡献的高频 CJK 二元组。 */
    private val STOP_BIGRAMS = setOf(
        "我们", "你们", "他们", "她们", "可以", "现在", "什么", "怎么", "这个", "那个",
        "一个", "一些", "没有", "知道", "觉得", "因为", "所以", "但是", "如果", "就是",
        "一下", "不是", "是的", "自己", "时候", "这样", "那样", "那么", "这么", "应该",
        "可能", "需要", "想要", "请问", "谢谢", "你好", "或者", "以及", "然后", "而且",
    )

    /**
     * 文本指纹：数字串（价格/日期锚点，n: 前缀）、拉丁词（w:）、
     * CJK 二元组（c:，去停用二元组）。
     */
    fun fingerprint(text: String): Set<String> {
        val lower = text.lowercase()
        val out = LinkedHashSet<String>()
        NUMBERS.findAll(lower).forEach { out += "n:" + it.value }
        LATIN_WORD.findAll(lower).forEach { out += "w:" + it.value }
        CJK_RUN.findAll(lower).forEach { match ->
            val run = match.value
            if (run.length == 1) {
                out += "c:$run"
            } else {
                for (index in 0 until run.length - 1) {
                    val bigram = run.substring(index, index + 2)
                    if (bigram !in STOP_BIGRAMS) out += "c:$bigram"
                }
            }
        }
        return out
    }

    /**
     * 两段文本是否承载同一事实：较短指纹被较长方覆盖 ≥85%（画像值子串场景），
     * 或较短一方指纹被覆盖 ≥[OVERLAP_DUP]。过短文本一律不判同。
     * 两侧都带数字锚点但无任何共同数字时直接判否——两次不同报价/日期绝不能
     * 因共享「报价」等泛化二元组被当成同一条事实。
     */
    fun isDuplicate(a: String, b: String): Boolean {
        val fa = fingerprint(a)
        val fb = fingerprint(b)
        if (fa.isEmpty() || fb.isEmpty()) return false
        val numbersA = fa.filterTo(HashSet()) { it.startsWith(NUMBER_PREFIX) }
        val numbersB = fb.filterTo(HashSet()) { it.startsWith(NUMBER_PREFIX) }
        if (numbersA.isNotEmpty() && numbersB.isNotEmpty() &&
            numbersA.none { it in numbersB }
        ) {
            return false
        }
        val (small, large) = if (fa.size <= fb.size) fa to fb else fb to fa
        val shortText = minOf(normalizedLength(a), normalizedLength(b))
        if (shortText < MIN_VALUE_CHARS) return false
        var contained = 0
        for (token in small) if (token in large) contained++
        val coverage = contained.toDouble() / small.size
        if (small.size >= MIN_SMALL_FINGERPRINTS && coverage >= 0.85) return true
        return coverage >= OVERLAP_DUP
    }

    /**
     * 从 [notes] 中剔除：id 命中 [keepIds] 的；内容与 [keepTexts] 任一文本
     * 指纹相撞的。[protectedKinds] 内的笔记只按 id 剔除、不参与文本判同
     * （承诺/事件等不能因措辞相近被画像值吞掉）。保序返回。
     */
    fun filterNotes(
        notes: List<Note>,
        keepIds: Set<Long> = emptySet(),
        keepTexts: Collection<String> = emptyList(),
        protectedKinds: Set<NoteKind> = emptySet(),
    ): List<Note> {
        if (notes.isEmpty()) return emptyList()
        val keptTexts = keepTexts.map { it.trim() }.filter { it.isNotEmpty() }
        return notes.filter { note ->
            if (note.id in keepIds) return@filter false
            if (note.kind in protectedKinds || keptTexts.isEmpty()) return@filter true
            keptTexts.none { isDuplicate(note.content, it) }
        }
    }

    /**
     * 列表内部按指纹互斥去重，先出现者优先——调用方须先按优先级/时间排好序。
     * [protectedKinds] 内的笔记永不被剔除（独立承诺即便措辞相近也各自保留）。
     */
    fun dedupeNotes(notes: List<Note>, protectedKinds: Set<NoteKind> = emptySet()): List<Note> {
        val accepted = ArrayList<Note>()
        for (note in notes) {
            // 受保护笔记（承诺/事件）永不被剔除；普通笔记与任一已收录笔记
            // 指纹相撞即丢弃——已收录方按输入顺序代表更高优先级/更新时间。
            if (note.kind !in protectedKinds &&
                accepted.any { isDuplicate(it.content, note.content) }
            ) {
                continue
            }
            accepted += note
        }
        return accepted
    }

    /**
     * 过滤候选文本：剔除与 [against] 任一参考文本指纹相撞者；列表内部也按
     * 先到先得互斥。用于成交要件摘录（DealFact.content）等非 Note 文本。
     */
    fun filterTexts(candidates: List<String>, against: Collection<String> = emptyList()): List<String> {
        val refs = against.map { it.trim() }.filter { it.isNotEmpty() }
        val result = ArrayList<String>()
        for (candidate in candidates) {
            val text = candidate.trim()
            if (text.isEmpty()) continue
            if (refs.any { isDuplicate(text, it) }) continue
            if (result.any { isDuplicate(text, it) }) continue
            result += text
        }
        return result
    }

    private fun normalizedLength(text: String): Int =
        text.split(NON_ALNUM).joinToString("") { it }.length
}
