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

package com.verlintas.baic2.floatball.advisor.emotion

import kotlin.math.abs
import kotlin.math.max

/**
 * 情绪判定引擎（情感分析师方案 §4.4）：词库信号采集 + 个人基线对照 + 证据聚合，
 * 纯函数零 LLM。判定**不是关键词匹配**，按五原则执法：
 *
 * 1. **单词不定论**：中性词（polarity=0，如"好的"）单独不构成任何证据；判负面
 *    标签需 ≥2 条独立证据（词 + 行为/趋势共现），或单条显性强负词（有效得分 ≥2.5，
 *    如"呵呵""烦死了"——语义明确无歧义的强负表达，与"好的"类中性词互斥）。
 *    行为/基线类证据不带词面，单独出现最高只到观察态（不进方针）。
 * 2. **个人基线校准**：长度骤降/间隔拉长/秒回/长文都是**相对该联系人 30 天基线**
 *    的偏离，不是绝对阈值——话少型人格的"好的"在基线内，零信号。
 * 3. **连续窗口累积**：敷衍/冷淡须连续 ≥3 条短确认且零主动追问才成立；
 *    单条短回复只进观察态。
 * 4. **信号撤回（追加分洗白）**：疑似冷淡后对方主动追加实质消息（追问/补充/
 *    新话题）→ 清零冷淡计数并追加正面信号。
 * 5. **保守偏置 + 置信分级**：证据不足输出平稳；三档输出：确定（影响方针）/
 *    观察态（仅记录+分屏提示）/ 平稳。正面确定档阈值高于负面（误判"兴奋"会
 *    错误开启推进窗口，比漏判更伤）；正面观察态还须词面在场——纯行为性活跃
 *    （追加分洗白/主动索取）只出"平稳偏正面"，不打扰（四情形 C）。
 *
 * 判定强度 = **主导方向有效总分**（子库 2 强度修饰整体缩放）取整，1..5 夹逼；
 * 连续累积确定档恒 2；平稳档恒 1。强度只随证据厚度与语气修饰走，不随词面
 * 单条最大值虚高（"烦死了"≈3，"好的"偏离基线≈2，四情形锁）。
 *
 * "好的"四情形走查（单测锁死，方案 §4.4）：A 话痨回"好的"+间隔变长→观察态；
 * B 连续 3 条短确认零追问→确定敷衍强度 2；C 追加分洗白→平稳偏正面；
 * D 话少型基线内→平稳零信号。
 *
 * 输出的 [EmotionVerdict] 供哨官（IncomingReview.emotion）→ 军师（方针映射）→
 * 审查官（情绪红线）消费；本对象零 UI 零 Android 依赖，JVM 直接单测。
 */
object EmotionJudge {

    /** 负面确定档：词面证据在场 + 独立证据 ≥2 条 + 总分 ≥ 此值。 */
    const val NEG_CONFIRM_TOTAL = 2.0

    /** 负面确定档：单条显性强负词有效得分 ≥ 此值（"呵呵"/"烦死了"类）。 */
    const val NEG_CONFIRM_SINGLE = 2.5

    /** 正面确定档：≥2 条证据 + 总分 ≥ 此值（比负面保守，防误开推进窗口）。 */
    const val POS_CONFIRM_TOTAL = 3.0

    /** 观察态下限：任一方向总分 ≥ 此值但未达确定档 → 观察态。 */
    const val WATCH_TOTAL = 1.0

    /** 连续短确认长度上限（字）：不超过即算短确认（原则 3）。 */
    const val SHORT_REPLY_MAX_LEN = 6

    /** 短确认样例词（polarity=0 的确认语），参与连续累积计数但不计情绪分。 */
    private val CONFIRM_WORDS = listOf(
        "好的", "好", "好哒", "好嘞", "好呀", "好哇", "好滴", "行", "行的", "行吧", "可以",
        "嗯", "嗯嗯", "嗯好", "嗯呢", "嗯呐", "哦", "哦哦", "噢", "知道了", "知道", "收到",
        "ok", "OK", "Okay", "okay", "没问题", "没毛病", "得嘞", "中", "成", "就这样",
    )

    /** 是否需要哨官 LLM 精判（E-d 再叠加"高价值回合"条件后才真正触发）：非平稳且置信非高。 */
    val EmotionVerdict.needsLlmRefine: Boolean
        get() = state != EmotionState.CALM && confidence != EmotionConfidence.HIGH

    /** 一条行为线索：不可得字段传 null/false，绝不臆测（HeatRules 同口径）。 */
    data class EmotionCue(
        /** 距上次来信的间隔（原则 2 偏离用）；null = 不可得。 */
        val replyIntervalMs: Long? = null,
        /** 本条含主动追问/实质问题（正面行为信号）。 */
        val hasActiveQuestion: Boolean = false,
        /** 本条是对短确认的主动追加（追问/补充/新话题）——原则 4 洗白触发器。 */
        val hasFollowUp: Boolean = false,
        /** 主动开启新话题（正面参与信号）。 */
        val newTopic: Boolean = false,
        /** 深夜主动发起（行为库正面/亲密信号）。 */
        val nightInitiative: Boolean = false,
        /** 转发/分享内容给对方（愿意交流）。 */
        val sharedContent: Boolean = false,
        /** 主动索取资料（兴趣信号）。 */
        val requestedMaterial: Boolean = false,
    )

    /** 近期消息窗口项（原则 3/4 的连续累积与撤回判定）。 */
    data class WindowItem(
        val text: String,
        val hasActiveQuestion: Boolean = false,
        val hasFollowUp: Boolean = false,
    )

    /**
     * 联系人 30 天滚动基线（原则 2）；样本不足传 null 或样本量不够——退化为
     * 不判基线类信号，绝不按全局绝对值硬判人格差异。滚动统计见
     * EmotionBaselineCalculator（E-b）。
     */
    data class Baseline(
        val avgLen: Double,
        val avgIntervalMs: Long,
        val shortReplyRatio: Double,
        val activeFollowRatio: Double,
        val sampleCount: Int,
    ) {
        /** 有效基线：样本量达标才有资格做偏离判定。 */
        val usable: Boolean get() = sampleCount >= MIN_SAMPLES && avgLen > 0.0

        companion object {
            const val MIN_SAMPLES = 10
        }
    }

    data class Input(
        val text: String,
        val cue: EmotionCue = EmotionCue(),
        val window: List<WindowItem> = emptyList(),
        val baseline: Baseline? = null,
        val lexicon: List<EmotionLexiconEntry> = emptyList(),
        val scenario: String? = null,
        val contactId: Long? = null,
    )

    /**
     * 证据链条目（分屏"点开证据链"透出）。
     * score=0 的中性命中也保留供解释（"好的（中性确认，不构成证据）"）。
     */
    data class Evidence(
        val kind: String,
        val term: String,
        val labels: List<String>,
        /** 否定反转后的极性。 */
        val polarity: Int,
        val intensityDelta: Int,
        val score: Double,
        val note: String,
    ) {
        /** 词面类证据（参与"单词不定论"的词类门槛）；行为/基线不带词面。 */
        val isWordClass: Boolean
            get() = kind !in setOf(KIND_BEHAVIOR, KIND_BASELINE)

        companion object {
            const val KIND_WORD = "word"
            const val KIND_SLANG = "slang"
            const val KIND_EMOJI = "emoji"
            const val KIND_PUNCT = "punct"
            const val KIND_SUBTEXT = "subtext"
            const val KIND_BEHAVIOR = "behavior"
            const val KIND_BASELINE = "baseline"
        }
    }

    data class LabelScore(val label: String, val score: Double)

    /** 三档输出（方案 §4.4 原则 5）：确定=影响方针；观察态=仅记录；平稳=不干预。 */
    enum class EmotionState { CONFIRMED, WATCH, CALM }

    enum class EmotionConfidence { HIGH, MEDIUM, LOW }

    data class EmotionVerdict(
        val state: EmotionState,
        /** 按 |score| 降序的标签得分（带符号：负=负面标签强度）。 */
        val labels: List<LabelScore>,
        /** 判定强度 1..5 = 主导方向有效总分取整（盛怒红线看 anger 强度≥4；连续累积确定档恒为 2）。 */
        val intensity: Int,
        val confidence: EmotionConfidence,
        val evidence: List<Evidence>,
        val reason: String,
    )

    /** 主判定入口：纯函数，同输入恒同输出。 */
    fun judge(input: Input): EmotionVerdict {
        val evidence = collectEvidence(input)
        val contributing = evidence.filter { it.score != 0.0 }

        // ── 标签计分：同标签得分求和（词条可多标签，正负各自归堆） ──
        val labelScores = contributing
            .flatMap { ev -> ev.labels.map { it to ev.score } }
            .groupBy({ it.first }, { it.second })
            .map { (label, scores) -> LabelScore(label, scores.sum()) }
            .sortedByDescending { abs(it.score) }

        val negItems = contributing.filter { it.score < 0 }
        val posItems = contributing.filter { it.score > 0 }
        val negWordItems = negItems.filter { it.isWordClass }
        val posWordItems = posItems.filter { it.isWordClass }
        val negTotal = negItems.sumOf { -it.score }
        val posTotal = posItems.sumOf { it.score }

        // ── 原则 3：连续短确认累积（敷衍/冷淡专用成立通道） ──
        val streak = trailingShortConfirmStreak(input)
        val streakFired =
            streak >= STREAK_MIN && !input.cue.hasActiveQuestion && !input.cue.hasFollowUp

        // ── 判级（原则 1+5）：词面在场门槛 + 行为/基线单独不进确定档 ──
        val negConfirmed = when {
            streakFired -> true
            negWordItems.isNotEmpty() && negItems.size >= 2 && negTotal >= NEG_CONFIRM_TOTAL -> true
            negItems.size == 1 && negWordItems.size == 1 && negTotal >= NEG_CONFIRM_SINGLE -> true
            else -> false
        }
        val posConfirmed =
            posWordItems.isNotEmpty() && posItems.size >= 2 && posTotal >= POS_CONFIRM_TOTAL

        val state = when {
            negConfirmed || posConfirmed -> EmotionState.CONFIRMED
            negTotal >= WATCH_TOTAL -> EmotionState.WATCH
            // 正面观察态须词面在场：纯行为性活跃（追加分/索取）= 平稳偏正面，不打扰（情形 C）。
            posTotal >= WATCH_TOTAL && posWordItems.isNotEmpty() -> EmotionState.WATCH
            else -> EmotionState.CALM
        }

        // ── 强度（1..5）：主导方向有效总分（子库 2 修饰整体缩放）取整；
        //    连续累积确定档恒 2（四情形锁）；平稳档恒 1 ──
        val intensity = when {
            streakFired -> 2
            state == EmotionState.CALM -> 1
            else -> {
                val dominant = max(negTotal, posTotal) * (1.0 + 0.25 * intensifierBoost(input))
                dominant.toInt().coerceIn(1, 5)
            }
        }

        // ── 置信（原则 5）：证据越厚越确定；观察态单条 → LOW，触发精判 ──
        val confidence = when {
            state == EmotionState.CALM -> EmotionConfidence.HIGH
            state == EmotionState.CONFIRMED &&
                (contributing.size >= 3 || streakFired || negTotal >= NEG_CONFIRM_SINGLE) ->
                EmotionConfidence.HIGH
            state == EmotionState.CONFIRMED -> EmotionConfidence.MEDIUM
            contributing.size >= 3 -> EmotionConfidence.MEDIUM
            else -> EmotionConfidence.LOW
        }

        return EmotionVerdict(
            state = state,
            labels = labelScores,
            intensity = intensity,
            confidence = confidence,
            evidence = evidence,
            reason = buildReason(state, labelScores, streakFired, negTotal, posTotal),
        )
    }

    // ── 证据采集 ────────────────────────────────────────────────────────

    private fun collectEvidence(input: Input): List<Evidence> {
        val text = input.text
        val out = mutableListOf<Evidence>()

        if (text.isNotBlank()) {
            val scoped = input.lexicon.filter { entry ->
                entry.enabled &&
                    (entry.scopeContactId == null || entry.scopeContactId == input.contactId) &&
                    (entry.library != EmotionLibraries.SUBTEXT ||
                        entry.scenario == null || entry.scenario == input.scenario)
            }
            out += matchTextLibraries(text, scoped)
        }
        out += behaviorEvidence(input)
        return out
    }

    /** 子库 1/4/5/6/7 文本匹配：最长命中优先、否定反转、中性命中留证据链但零分。 */
    private fun matchTextLibraries(
        text: String,
        lexicon: List<EmotionLexiconEntry>,
    ): List<Evidence> {
        val textLibs = setOf(
            EmotionLibraries.TRIGGER,
            EmotionLibraries.PUNCTUATION,
            EmotionLibraries.EMOJI,
            EmotionLibraries.NET_SLANG,
            EmotionLibraries.SUBTEXT,
        )
        val candidates = mutableListOf<Match>()
        lexicon.filter { it.library in textLibs }.forEach { entry ->
            val regex = if (entry.library == EmotionLibraries.PUNCTUATION) {
                runCatching { Regex(entry.term) }.getOrNull()
            } else {
                null
            }
            if (regex != null) {
                regex.findAll(text).forEach { m -> candidates += Match(entry, m.range.first, m.value) }
            } else {
                var idx = text.indexOf(entry.term)
                while (idx >= 0) {
                    candidates += Match(entry, idx, entry.term)
                    idx = text.indexOf(entry.term, idx + entry.term.length)
                }
            }
        }

        // 最长命中优先消解重叠（"哈哈" vs "哈哈哈哈"、"开心" vs "不开心"）。
        val accepted = mutableListOf<Match>()
        candidates
            .sortedWith(compareByDescending<Match> { it.matched.length }.thenBy { it.start })
            .forEach { m ->
                val overlaps = accepted.any {
                    it.start < m.start + m.matched.length && m.start < it.start + it.matched.length
                }
                if (!overlaps) accepted += m
            }

        val negations = lexicon.filter { it.library == EmotionLibraries.NEGATION }
        return accepted.sortedBy { it.start }.map { it.toEvidence(text, negations) }
    }

    private class Match(val entry: EmotionLexiconEntry, val start: Int, val matched: String) {
        fun toEvidence(text: String, negations: List<EmotionLexiconEntry>): Evidence {
            val negated = entry.negatable && isNegated(text, start, negations)
            // 否定反转："不太开心"≠"开心"——翻向且降一档（归零即中性化，不计标签）。
            val polarity = if (negated) {
                (if (entry.polarity > 0) -entry.polarity + 1 else if (entry.polarity < 0) -entry.polarity - 1 else 0)
                    .coerceIn(-2, 2)
            } else {
                entry.polarity
            }
            return Evidence(
                kind = kindOf(entry.library),
                term = matched,
                labels = if (negated || polarity == 0) emptyList() else entry.labels,
                polarity = polarity,
                intensityDelta = entry.intensityDelta,
                score = effectiveScore(polarity, entry.intensityDelta, entry.weight),
                note = if (negated) "否定反转：「$matched」不按原义计" else entry.note,
            )
        }
    }

    /** 有效得分：极性 × 权重 × 强度乘子（词面强度越高越可信）。 */
    private fun effectiveScore(polarity: Int, intensityDelta: Int, weight: Float): Double {
        if (polarity == 0) return 0.0
        return polarity * weight * (1.0 + 0.25 * intensityDelta)
    }

    private fun isNegated(text: String, start: Int, negations: List<EmotionLexiconEntry>): Boolean {
        val from = (start - NEG_LOOKBACK).coerceAtLeast(0)
        val window = text.substring(from, start)
        return negations.any { window.contains(it.term) }
    }

    private fun kindOf(library: Int): String = when (library) {
        EmotionLibraries.TRIGGER -> Evidence.KIND_WORD
        EmotionLibraries.NET_SLANG -> Evidence.KIND_SLANG
        EmotionLibraries.EMOJI -> Evidence.KIND_EMOJI
        EmotionLibraries.PUNCTUATION -> Evidence.KIND_PUNCT
        EmotionLibraries.SUBTEXT -> Evidence.KIND_SUBTEXT
        else -> Evidence.KIND_WORD
    }

    /**
     * 行为信号规则（子库 8）：规则 id=term，用户可停用/调权重（词库管理页）。
     * 原则 4：追加分洗白优先——冷淡计数不成立，改追正面活跃信号。
     */
    private fun behaviorEvidence(input: Input): List<Evidence> {
        val rules = input.lexicon.associateBy { it.term }
            .filterValues { it.library == EmotionLibraries.BEHAVIOR && it.enabled }
        val out = mutableListOf<Evidence>()
        val cue = input.cue

        fun add(id: String, polarity: Int, labels: List<String>, note: String) {
            val entry = rules[id]
                ?.takeIf { it.scopeContactId == null || it.scopeContactId == input.contactId }
                ?: return
            val p = polarity.coerceIn(-2, 2)
            if (p == 0) return
            out += Evidence(
                kind = Evidence.KIND_BEHAVIOR,
                term = id,
                labels = labels,
                polarity = p,
                intensityDelta = entry.intensityDelta,
                score = effectiveScore(p, entry.intensityDelta, entry.weight),
                note = note,
            )
        }

        // 原则 4：追加分洗白 + 主动诉求（正面）
        if (cue.hasFollowUp) {
            add("active_follow", 1, listOf(EmotionLabels.JOY), "主动追加实质消息（追加分洗白，原则 4）")
        }
        if (cue.hasActiveQuestion) {
            add("active_question", 1, listOf(EmotionLabels.JOY), "含主动追问（正面参与信号）")
        }
        if (cue.newTopic) {
            add("new_topic", 1, listOf(EmotionLabels.JOY), "主动开启新话题（正面参与信号）")
        }
        if (cue.nightInitiative) {
            add("night_initiative", 1, listOf(EmotionLabels.JOY), "深夜主动发起（亲密/信任信号）")
        }
        if (cue.sharedContent) {
            add("share_forward", 1, listOf(EmotionLabels.JOY), "转发分享内容（愿意交流）")
        }
        if (cue.requestedMaterial) {
            add("request_material", 1, listOf(EmotionLabels.JOY), "主动索取资料（兴趣信号）")
        }

        // 原则 3：连续短确认累积（敷衍/冷淡）
        val streak = trailingShortConfirmStreak(input)
        if (streak >= STREAK_MIN && !cue.hasActiveQuestion && !cue.hasFollowUp) {
            add(
                "short_confirm_streak", -1, listOf(EmotionLabels.PERFUNCTORY),
                "连续 $streak 条短确认且零主动追问（原则 3 累积）",
            )
        }

        // 原则 2：基线偏离（样本不足不判，绝不按全局绝对值硬判人格差异）
        val baseline = input.baseline
        if (baseline != null && baseline.usable) {
            val len = input.text.trim().length
            val gap = cue.replyIntervalMs?.takeIf { it >= 0 }
            when {
                len > 0 && len < baseline.avgLen * LENGTH_DROP_RATIO -> add(
                    "length_drop", -1, listOf(EmotionLabels.PERFUNCTORY),
                    "篇幅骤降：本条 $len 字不足基线均值 ${baseline.avgLen.toInt()} 字的三成（原则 2 偏离）",
                )
                len > 0 && len > baseline.avgLen * LONG_REPLY_RATIO -> add(
                    "long_reply", 1, listOf(EmotionLabels.JOY),
                    "篇幅高于基线 1.5 倍（高投入信号，原则 2）",
                )
            }
            if (gap != null && baseline.avgIntervalMs > 0) {
                when {
                    gap > baseline.avgIntervalMs * INTERVAL_STRETCH_RATIO -> add(
                        "interval_stretch", -1, listOf(EmotionLabels.PERFUNCTORY),
                        "回复间隔拉长：${gap / 60000} 分钟 vs 基线 ${baseline.avgIntervalMs / 60000} 分钟（原则 2 偏离）",
                    )
                    gap < baseline.avgIntervalMs * QUICK_REPLY_RATIO -> add(
                        "quick_reply", 1, listOf(EmotionLabels.JOY),
                        "回复节奏明显加快（热度上升信号，原则 2）",
                    )
                }
            }
        }
        return out
    }

    /** 尾部连续短确认计数（原则 3）：从当前条回看，遇非短确认即止。 */
    private fun trailingShortConfirmStreak(input: Input): Int {
        val texts = input.window.map { it.text.trim() } + input.text.trim()
        if (texts.isEmpty()) return 0
        var count = 0
        for (i in texts.indices.reversed()) {
            if (isShortConfirm(texts[i])) count++ else break
        }
        return count
    }

    private fun isShortConfirm(text: String): Boolean {
        if (text.isEmpty() || text.length > SHORT_REPLY_MAX_LEN) return false
        if (text.contains('？') || text.contains('?')) return false
        return CONFIRM_WORDS.any { text == it || text == "$it。" || text == "$it，" }
    }

    /** 强度修饰（子库 2）对本条整体的放大/缩小贡献，夹逼 ±2。 */
    private fun intensifierBoost(input: Input): Int {
        val boost = input.lexicon
            .filter {
                it.library == EmotionLibraries.INTENSIFIER && it.enabled &&
                    (it.scopeContactId == null || it.scopeContactId == input.contactId)
            }
            .sumOf { if (input.text.contains(it.term)) it.intensityDelta else 0 }
        return boost.coerceIn(-2, 2)
    }

    private fun buildReason(
        state: EmotionState,
        labels: List<LabelScore>,
        streakFired: Boolean,
        negTotal: Double,
        posTotal: Double,
    ): String {
        val top = labels.firstOrNull()
        val labelPart = if (top != null) "「${EmotionLabels.display(top.label)}」" else ""
        return when {
            streakFired -> "连续短确认累积成立，判$labelPart（原则 3）"
            state == EmotionState.CONFIRMED && negTotal >= posTotal -> "证据充分，判$labelPart（确定档）"
            state == EmotionState.CONFIRMED -> "证据充分，判$labelPart（确定档，正面）"
            state == EmotionState.WATCH && negTotal >= posTotal ->
                "疑似$labelPart，证据不足进观察态（原则 5，不干预方针）"
            state == EmotionState.WATCH -> "疑似$labelPart（正面倾向），观察态（原则 5）"
            posTotal > 0 -> "平稳偏正面（活跃），不干预方针"
            else -> "无有效情绪证据，判平稳"
        }
    }

    private const val STREAK_MIN = 3
    private const val NEG_LOOKBACK = 4
    private const val LENGTH_DROP_RATIO = 0.3
    private const val LONG_REPLY_RATIO = 1.5
    private const val INTERVAL_STRETCH_RATIO = 1.8
    private const val QUICK_REPLY_RATIO = 0.4
}
