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

package com.verlintas.baic2.floatball.experts.strategist

import com.verlintas.baic2.floatball.advisor.emotion.EmotionJudge
import com.verlintas.baic2.floatball.advisor.emotion.EmotionLabels
import kotlin.math.abs

/**
 * 情绪→方针映射产物（情感分析师方案 §4.6）：仅确定档命中映射行时 [active] = true；
 * 平稳/观察态/未列组合恒不干预（原则 5 执法面）。
 *
 * @property key 行键（台账/日志/军师缓存键用）：rage / dissatisfaction / ...
 * @property label 行中文名（提示词段首展示）
 * @property intensity 判定强度 1..5（原样带出，提示词展示用）
 * @property approaches 映射建议的 approach 重点（[APPROACH_*] 词表常量）；空 = 不指定
 * @property bannedApproaches 禁用 approach 词表（[StrategistRules.enforce] 硬剥/覆写）
 * @property bannedDims 禁用敏感维（[StrategistRules.DIM_*]，enforce 层剥 use）
 * @property bannedNote 语义禁令（推进/长篇等非词表手段，进提示词约束；盛怒的
 * 玩笑/推进硬红线由审查官 E-f 拦截降级重写）
 * @property redline 审查官情绪红线启用位（方案 §4.5 行 11）：true 时稿件玩笑/推进
 * 词表命中即拦截降级重写——**仅盛怒行**开启（误报保守：其余行一律放行）
 * @property guidance 话术方向（提示词引导句）
 */
data class EmotionRoute(
    val active: Boolean = false,
    val key: String = "",
    val label: String = "",
    val intensity: Int = 0,
    val approaches: List<String> = emptyList(),
    val bannedApproaches: List<String> = emptyList(),
    val bannedDims: Set<String> = emptySet(),
    val bannedNote: String = "",
    val redline: Boolean = false,
    val guidance: String = "",
) {
    companion object {
        /** 不干预（平稳/观察态/未列组合/无裁决）。 */
        val INACTIVE = EmotionRoute()
    }
}

/**
 * 情绪→方针映射（方案 §4.6 映射表，纯函数 JVM 可单测）：情感分析师三档裁决 →
 * 本回合话术方针。执法口径——
 * 1. 只有**确定档**生效：观察态/平稳一律 [EmotionRoute.INACTIVE]（原则 5）；
 * 2. 主导标签定行：|score| 最大的标签定行（同分取先），派生态在本层派生
 *    （盛怒 = anger 强度≥4；兴奋 = joy 强度≥3，EmotionLabels 注释预留位）；
 * 3. 强度列按下限读（不满表列 2-3、实作 ≥2）：情绪更强绝不拿到更松的方针；
 * 4. 映射表未列的负面主导标签（anger<4、sadness/fear/disgust 等）强度≥2 落
 *    [NEGATIVE_FALLBACK] 保守兜底行（empathy+倾听，禁说教/推进）——表未列
 *    不等于放任伤害性话术；其余未列组合（如 joy<3）不干预。
 */
internal object EmotionRouting {

    /**
     * 三档裁决 → 方针行。null（合议旁路）→ 不干预；[EmotionJudge.EmotionVerdict.intensity]
     * 原样带出供提示词展示。
     */
    fun route(verdict: EmotionJudge.EmotionVerdict?): EmotionRoute {
        if (verdict == null) return EmotionRoute.INACTIVE
        if (verdict.state != EmotionJudge.EmotionState.CONFIRMED) return EmotionRoute.INACTIVE
        val primary = verdict.labels.maxByOrNull { abs(it.score) }?.label
            ?: return EmotionRoute.INACTIVE
        val intensity = verdict.intensity
        val row = when {
            primary == EmotionLabels.ANGER && intensity >= 4 -> RAGE
            primary == EmotionLabels.DISSATISFACTION && intensity >= 2 -> UNHAPPY
            primary == EmotionLabels.HESITATION -> HESITANT
            primary == EmotionLabels.JOY && intensity >= 3 -> EXCITED
            primary == EmotionLabels.PERFUNCTORY -> PERFUNCTORY
            primary == EmotionLabels.DISAPPOINTMENT && intensity >= 3 -> DISAPPOINTED
            primary == EmotionLabels.GRIEVANCE && intensity >= 3 -> DISAPPOINTED
            primary == EmotionLabels.PROBING -> PROBING
            primary == EmotionLabels.LOW -> LOW
            primary in EmotionLabels.NEGATIVE && intensity >= 2 -> NEGATIVE_FALLBACK
            else -> return EmotionRoute.INACTIVE
        }
        return row.copy(intensity = intensity)
    }

    /** 盛怒（anger 强度≥4，派生态）：短句认错安抚，禁玩笑/类比/推进。 */
    val RAGE = EmotionRoute(active = true,
        key = "rage",
        label = "盛怒",
        approaches = listOf(APPROACH_EMPATHY),
        bannedApproaches = listOf(APPROACH_HUMOR, APPROACH_STORY),
        bannedDims = setOf(StrategistRules.DIM_EXEMPLIFYING),
        bannedNote = "推进（不逼单、不营销下一步）、开玩笑玩梗",
        redline = true,
        guidance = "短句、先道歉再解释、给出补救行动",
    )

    /** 不满（表列强度 2-3，实作 ≥2）：先认感受再摆价值，禁价值展示抢话。 */
    val UNHAPPY = EmotionRoute(active = true,
        key = "dissatisfaction",
        label = "不满",
        approaches = listOf(APPROACH_EMPATHY, APPROACH_BENEFIT_LIST),
        bannedApproaches = listOf(APPROACH_VALUE_SHOW),
        guidance = "先承认对方的感受，再摆利弊与价值",
    )

    /** 犹豫（任意强度）：案例与保障降顾虑，禁逼单式推进。 */
    val HESITANT = EmotionRoute(active = true,
        key = "hesitation",
        label = "犹豫",
        approaches = listOf(APPROACH_BENEFIT_LIST, APPROACH_STORY),
        bannedNote = "逼单式推进（不催决定、不制造紧迫感）",
        guidance = "用案例与保障降低对方的顾虑",
    )

    /** 兴奋（joy 强度≥3，派生态）：趁热打铁给推进窗口（L1 判据）。 */
    val EXCITED = EmotionRoute(active = true,
        key = "excitement",
        label = "兴奋",
        approaches = listOf(APPROACH_VALUE_SHOW),
        guidance = "趁热打铁，可顺势给出约下一步的推进窗口",
    )

    /** 敷衍（任意强度）：降温换话题，禁推进/长篇。 */
    val PERFUNCTORY = EmotionRoute(active = true,
        key = "perfunctory",
        label = "敷衍",
        bannedNote = "推进、长篇回复",
        guidance = "降温处理：轻话题或主动优雅收尾，必要时切换话题",
    )

    /** 失望/委屈（强度≥3）：认错+补偿，禁讲道理/辩解。 */
    val DISAPPOINTED = EmotionRoute(active = true,
        key = "disappointment",
        label = "失望/委屈",
        approaches = listOf(APPROACH_EMPATHY),
        bannedNote = "讲道理、辩解",
        guidance = "先认错，再给出具体的补偿行动",
    )

    /** 试探（任意强度，高危类）：真诚保守，禁侥幸话术（D2：问主人或保守稿）。 */
    val PROBING = EmotionRoute(active = true,
        key = "probing",
        label = "试探",
        bannedNote = "侥幸话术（不赌对方不追问、不打擦边球）",
        guidance = "真诚、保守作答；拿不准就问主人或出保守稿",
    )

    /** 低落（任意强度）：陪伴倾听，禁说教/推进。 */
    val LOW = EmotionRoute(active = true,
        key = "low",
        label = "低落",
        approaches = listOf(APPROACH_EMPATHY),
        bannedNote = "说教、推进",
        guidance = "陪伴式回应、多倾听；登记跟进话题",
    )

    /** 映射表外负面兜底（口径 4）：同低落行的保守方向，标签不冒充具体情绪。 */
    val NEGATIVE_FALLBACK = EmotionRoute(active = true,
        key = "negative_fallback",
        label = "负面情绪",
        approaches = listOf(APPROACH_EMPATHY),
        bannedNote = "说教、推进",
        guidance = "先接住对方的情绪，陪伴式回应、多倾听",
    )
}
