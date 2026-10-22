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

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 情绪判定引擎（情感分析师方案 §4.4 五原则）：**"好的"四情形走查锁死** +
 * 中文歧义样例（呵呵负义 / 哈哈长度差 / 反讽不硬判）+ §三场景例强度锁。
 *
 * 全部用例跑真实内置词库资产（assets/advisor/emotion_lexicon.json），
 * 引擎与资产的组合行为即产品行为。
 */
class EmotionJudgeTest {

    private val lexicon: List<EmotionLexiconEntry> by lazy {
        parseEmotionLexiconSeed(readAsset("src/main/assets/advisor/emotion_lexicon.json"))
    }

    // ── 夹具 ───────────────────────────────────────────────────────────

    /** 话痨型基线：均值 30 字/条、1 分钟一条、样本充足（原则 2 可判偏离）。 */
    private fun talkativeBaseline() = EmotionJudge.Baseline(
        avgLen = 30.0,
        avgIntervalMs = 60_000L,
        shortReplyRatio = 0.1,
        activeFollowRatio = 0.5,
        sampleCount = 50,
    )

    /** 话少型基线：均值 4 字/条、30 秒一条——短回复在其基线内。 */
    private fun terseBaseline() = EmotionJudge.Baseline(
        avgLen = 4.0,
        avgIntervalMs = 30_000L,
        shortReplyRatio = 0.8,
        activeFollowRatio = 0.1,
        sampleCount = 50,
    )

    private fun judge(
        text: String,
        cue: EmotionJudge.EmotionCue = EmotionJudge.EmotionCue(),
        window: List<EmotionJudge.WindowItem> = emptyList(),
        baseline: EmotionJudge.Baseline? = null,
        scenario: String? = null,
    ): EmotionJudge.EmotionVerdict = EmotionJudge.judge(
        EmotionJudge.Input(
            text = text,
            cue = cue,
            window = window,
            baseline = baseline,
            lexicon = lexicon,
            scenario = scenario,
            contactId = 1L,
        ),
    )

    private fun labelsOf(v: EmotionJudge.EmotionVerdict): List<String> = v.labels.map { it.label }

    private fun scoreOf(v: EmotionJudge.EmotionVerdict, label: String): Double =
        v.labels.first { it.label == label }.score

    // ── "好的"四情形（方案 §4.4，单测锁死）─────────────────────────────

    @Test
    fun caseA_talkativeShortReply_watchOnly() {
        // 情形 A：话痨型回"好的"且间隔变长 → 观察态（疑似冷淡，记录不动作）。
        val v = judge(
            text = "好的",
            cue = EmotionJudge.EmotionCue(replyIntervalMs = 180_000L), // 3 分钟 vs 基线 1 分钟
            baseline = talkativeBaseline(),
        )
        assertEquals(EmotionJudge.EmotionState.WATCH, v.state)
        assertTrue(labelsOf(v).contains(EmotionLabels.PERFUNCTORY))
        // 词面"好的"= 中性确认：零分留证据链，不构成词面证据（原则 1）。
        val neutral = v.evidence.first { it.term == "好的" }
        assertEquals(0.0, neutral.score, 0.001)
        // 行为证据恰 2 类：长度骤降 + 间隔拉长（原则 2 偏离）。
        val fired = v.evidence.filter { it.score != 0.0 }.map { it.term }.toSet()
        assertEquals(setOf("length_drop", "interval_stretch"), fired)
    }

    @Test
    fun caseB_threeShortConfirms_confirmedPerfunctoryIntensity2() {
        // 情形 B：连续 3 条短确认且零追问 → 确定敷衍/冷淡，强度恒 2（原则 3）。
        val v = judge(
            text = "知道了",
            window = listOf(
                EmotionJudge.WindowItem("嗯"),
                EmotionJudge.WindowItem("好的"),
            ),
        )
        assertEquals(EmotionJudge.EmotionState.CONFIRMED, v.state)
        assertEquals(2, v.intensity)
        assertTrue(labelsOf(v).contains(EmotionLabels.PERFUNCTORY))
    }

    @Test
    fun caseC_followUpWashOut_calmPositive() {
        // 情形 C：短确认后主动追加实质消息 → 洗白（原则 4），平稳偏正面不打扰。
        val v = judge(
            text = "对了，那个文件发我下",
            cue = EmotionJudge.EmotionCue(hasFollowUp = true, requestedMaterial = true),
            window = listOf(EmotionJudge.WindowItem("好的")),
        )
        assertEquals(EmotionJudge.EmotionState.CALM, v.state)
        assertTrue(labelsOf(v).contains(EmotionLabels.JOY))
        assertFalse(labelsOf(v).any { it in EmotionLabels.NEGATIVE })
    }

    @Test
    fun caseC_followUpBreaksColdStreak() {
        // 原则 4 强锁：冷淡窗口已累积 3 条短确认，追加分一到立即清零，绝不补判敷衍。
        val v = judge(
            text = "对了，那个文件发我下",
            cue = EmotionJudge.EmotionCue(hasFollowUp = true, requestedMaterial = true),
            window = listOf(
                EmotionJudge.WindowItem("嗯"),
                EmotionJudge.WindowItem("好的"),
                EmotionJudge.WindowItem("知道了"),
            ),
        )
        assertEquals(EmotionJudge.EmotionState.CALM, v.state)
        assertFalse(labelsOf(v).contains(EmotionLabels.PERFUNCTORY))
    }

    @Test
    fun caseD_terseBaseline_calmZeroSignal() {
        // 情形 D：话少型联系人"好的"全在基线内 → 平稳零信号（原则 2）。
        val v = judge(
            text = "好的",
            cue = EmotionJudge.EmotionCue(replyIntervalMs = 30_000L),
            baseline = terseBaseline(),
        )
        assertEquals(EmotionJudge.EmotionState.CALM, v.state)
        assertTrue(v.labels.isEmpty())
        assertEquals(1, v.intensity)
        assertTrue("基线内零信号，证据链只剩中性零分", v.evidence.all { it.score == 0.0 })
    }

    @Test
    fun singleHaoDe_alone_neverJudges() {
        // 用户核心场景：单条"好的"= 知道了而已，任何情况下不判敷衍。
        val v = judge(text = "好的")
        assertEquals(EmotionJudge.EmotionState.CALM, v.state)
        assertTrue(v.labels.isEmpty())
    }

    // ── 中文歧义样例（方案 §五风险表，锁死）────────────────────────────

    @Test
    fun hehe_alone_confirmedPerfunctory() {
        // "呵呵"= 中文特有强负语义：单条显性强负词走例外通道即定档（原则 1）。
        val v = judge(text = "呵呵")
        assertEquals(EmotionJudge.EmotionState.CONFIRMED, v.state)
        assertTrue(labelsOf(v).contains(EmotionLabels.PERFUNCTORY))
        // 最长命中优先："呵呵"整体命中，"呵"不重复计分。
        assertEquals(1, v.evidence.count { it.score != 0.0 })
    }

    @Test
    fun hahaLengthDifference_weakVersusStrong() {
        // 长度差语义："哈哈"=礼貌性弱笑 ≠ "哈哈哈哈"=真笑（方案 §4.3.1 例）。
        val weak = judge(text = "哈哈")
        val strong = judge(text = "哈哈哈哈")
        assertEquals("哈哈", weak.evidence.single().term)
        assertEquals("哈哈哈哈", strong.evidence.single().term) // 最长命中消解重叠
        assertEquals(1.0, scoreOf(weak, EmotionLabels.JOY), 0.001) // 弱正面
        assertEquals(2.5, scoreOf(strong, EmotionLabels.JOY), 0.001) // 强正面
        assertTrue(strong.intensity > weak.intensity)
    }

    @Test
    fun negationFlip_dropsOriginalLabel() {
        // "不太开心" ≠ "开心"：否定反转翻向降档，joy 标签绝不计（方案 §4.3.1 例）。
        val v = judge(text = "不太开心")
        assertFalse(labelsOf(v).contains(EmotionLabels.JOY))
        assertTrue(v.evidence.any { it.note.startsWith("否定反转") })
    }

    @Test
    fun ironyPhrase_noHardJudgement() {
        // 反讽"可真有你的"语境歧义：宁判平稳不误判（原则 5 保守偏置）。
        val v = judge(text = "可真有你的")
        assertEquals(EmotionJudge.EmotionState.CALM, v.state)
        assertTrue(v.labels.isEmpty())
    }

    // ── §三场景例强度锁（与方案样例表逐条对齐）──────────────────────────

    @Test
    fun example1_salesSubtext_confirmedIntensity3() {
        // 例1：销售比价砍价 → 不满+试探，强度 3。
        val v = judge(text = "你们这价格也太贵了吧，别家便宜多了", scenario = "sales_c")
        assertEquals(EmotionJudge.EmotionState.CONFIRMED, v.state)
        assertEquals(3, v.intensity)
        val labels = labelsOf(v)
        assertTrue(labels.contains(EmotionLabels.DISSATISFACTION))
        assertTrue(labels.contains(EmotionLabels.PROBING))
    }

    @Test
    fun example3_excitement_confirmedIntensity4() {
        // 例3："哈哈哈" + 主动索取 → 兴奋，强度 4（推进窗口开启的判定源）。
        val v = judge(
            text = "哈哈哈这个有意思，发我看看",
            cue = EmotionJudge.EmotionCue(requestedMaterial = true),
        )
        assertEquals(EmotionJudge.EmotionState.CONFIRMED, v.state)
        assertEquals(4, v.intensity)
        assertEquals(EmotionLabels.JOY, v.labels.first().label)
    }

    @Test
    fun example5_highRiskProbing_confirmed() {
        // 例5：具体事件回溯式试探（高危）→ 委屈+试探，确定档。
        val v = judge(text = "你昨天是不是忘了跟我说晚安")
        assertEquals(EmotionJudge.EmotionState.CONFIRMED, v.state)
        val labels = labelsOf(v)
        assertTrue(labels.contains(EmotionLabels.GRIEVANCE))
        assertTrue(labels.contains(EmotionLabels.PROBING))
    }

    @Test
    fun example7_workplaceSubtext_confirmed() {
        // 例7：领导"你再看看吧"= 含蓄否定（场景潜台词库命中），禁辩解方针的判定源。
        val v = judge(text = "这个方案你再看看吧", scenario = "workplace_superior")
        assertEquals(EmotionJudge.EmotionState.CONFIRMED, v.state)
        assertTrue(labelsOf(v).contains(EmotionLabels.DISSATISFACTION))
        assertTrue("方案 §三 例7：强度 2-3", v.intensity in 2..3)
    }

    @Test
    fun subtextOnlyAppliesInScopedScenario() {
        // 潜台词库按场景生效：场景外不套"含蓄否定"强判，只按全局弱观望词走。
        val scoped = judge(text = "这个方案你再看看吧", scenario = "workplace_superior")
        val unscoped = judge(text = "这个方案你再看看吧", scenario = "dating")
        assertEquals(EmotionJudge.EmotionState.CONFIRMED, scoped.state)
        assertTrue(unscoped.state != EmotionJudge.EmotionState.CONFIRMED)
        assertFalse(labelsOf(unscoped).contains(EmotionLabels.DISSATISFACTION))
    }

    // ── 精判候选口径（E-d 高价值回合门前置契约）─────────────────────────

    @Test
    fun llmRefineCandidate_onlyUncertainNonCalm() = with(EmotionJudge) {
        // 情形 A：观察态且证据薄 → 精判候选。
        assertTrue(
            judge(
                text = "好的",
                cue = EmotionJudge.EmotionCue(replyIntervalMs = 180_000L),
                baseline = talkativeBaseline(),
            ).needsLlmRefine,
        )
        // 情形 B：连续累积高置信，无需精判。
        assertFalse(
            judge(
                text = "知道了",
                window = listOf(EmotionJudge.WindowItem("嗯"), EmotionJudge.WindowItem("好的")),
            ).needsLlmRefine,
        )
        // 平稳零信号：典型回合零 LLM（库为主、LLM 为辅的预算红线）。
        assertFalse(judge(text = "好的").needsLlmRefine)
    }
}

/**
 * 内置词库资产（assets/advisor/emotion_lexicon.json）质量门禁：
 * 规模、8 子库齐备、判重、正则合法、场景码与 13 场景包对齐、标签不越界。
 */
class EmotionLexiconSeedTest {

    private val raw: EmotionLexiconSeedFile by lazy {
        testJson.decodeFromString(
            EmotionLexiconSeedFile.serializer(),
            readAsset("src/main/assets/advisor/emotion_lexicon.json"),
        )
    }

    private val entries: List<EmotionLexiconEntry> by lazy {
        parseEmotionLexiconSeed(readAsset("src/main/assets/advisor/emotion_lexicon.json"))
    }

    @Test
    fun seedMeetsBuiltinScale() {
        assertTrue("内置词条规模不足（方案 D1：约 600 起步）：${entries.size}", entries.size >= 600)
    }

    @Test
    fun allEightLibrariesPopulated() {
        EmotionLibraries.ALL.forEach { lib ->
            assertTrue("子库 $lib（${EmotionLibraries.name(lib)}）为空", entries.any { it.library == lib })
        }
    }

    @Test
    fun noEntrySilentlyDropped() {
        assertEquals(raw.entries.size, entries.size)
    }

    @Test
    fun termsUniquePerLibrary() {
        val keys = entries.map { it.term to it.library }
        assertEquals("同子库内存在重复词条", keys.size, keys.toSet().size)
    }

    @Test
    fun rawFieldsStayInValidRange() {
        raw.entries.forEach { e ->
            assertTrue("library 越界：${e.term}", e.library in EmotionLibraries.ALL)
            assertTrue("polarity 越界：${e.term}", e.polarity in -2..2)
            assertTrue("intensityDelta 越界：${e.term}", e.intensityDelta in -2..2)
            e.labels.forEach { assertTrue("未知标签 $it：${e.term}", it in EmotionLabels.ALL) }
        }
    }

    @Test
    fun neutralConfirmWordsAreZeroPolarity() {
        // 单词不定论的数据层保障：中性确认词恒 polarity=0（原则 1）。
        listOf("好的", "知道了", "收到", "嗯").forEach { term ->
            val e = entries.firstOrNull { it.term == term }
            assertTrue("缺中性确认词：$term", e != null)
            assertEquals("$term 必须中性", 0, e!!.polarity)
        }
    }

    @Test
    fun ambiguitySamplesCuratedConservatively() {
        val hehe = entries.first { it.term == "呵呵" }
        assertTrue("呵呵必须负语义单列", hehe.polarity < 0)
        val haha = entries.first { it.term == "哈哈" }
        val hahaha = entries.first { it.term == "哈哈哈" }
        assertTrue("哈哈长度差：真笑须强于礼貌笑", hahaha.polarity > haha.polarity)
    }

    @Test
    fun punctuationPatternsAreValidRegex() {
        entries.filter { it.library == EmotionLibraries.PUNCTUATION }.forEach {
            assertTrue("标点正则非法：${it.term}", runCatching { Regex(it.term) }.isSuccess)
        }
    }

    @Test
    fun subtextScenarioCodesAlignWithPacks() {
        val codes = setOf(
            "dating", "couple", "family", "workplace_superior", "colleague", "job_hunt",
            "sales_b2b", "sales_c", "vendor", "networking", "adversarial", "complaint",
            "group_general",
        )
        entries.filter { it.library == EmotionLibraries.SUBTEXT }.forEach {
            assertTrue("未知场景码：${it.term} → ${it.scenario}", it.scenario == null || it.scenario in codes)
        }
    }

    @Test
    fun behaviorRuleIdsCoverEngineChannels() {
        val ids = entries.filter { it.library == EmotionLibraries.BEHAVIOR }.map { it.term }.toSet()
        listOf(
            "active_follow", "active_question", "new_topic", "night_initiative",
            "share_forward", "request_material", "short_confirm_streak",
            "length_drop", "long_reply", "interval_stretch", "quick_reply",
        ).forEach { assertTrue("缺行为规则：$it", it in ids) }
    }
}

/** 单测资产读取（与 ActionPlannerTest 同口径：cwd = :app 模块目录）。 */
internal fun readAsset(path: String): String {
    val file = File(path)
    assertTrue("asset missing: $path (cwd=${File(".").absolutePath})", file.exists())
    return file.readText()
}

private val testJson = Json { ignoreUnknownKeys = true }
