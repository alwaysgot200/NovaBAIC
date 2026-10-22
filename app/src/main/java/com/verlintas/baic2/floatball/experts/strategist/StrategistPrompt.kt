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

/**
 * 军师许可单提示词构建（裁量层方案 §3.2/§3.3，纯函数）：输入=阶段意图、
 * 来信段、近 2 轮缩写、人设摘要、四维档位表、F1 命中隐私域；输出=慢车道
 * LLM 的 system/user 双段。纯字符串拼装，JVM 可直接单测。
 */
internal object StrategistPrompt {

    /** 军师系统提示词：角色 + 四维定义 + approach 词表 + 红线禁令 + JSON 协议。 */
    fun systemPrompt(): String = buildString {
        append("你是聊天托管系统的军师。根据对话现场，为下一回合回复制定方针与敏感手段许可。\n")
        append("四维敏感手段（每维给 use/why/claim）：\n")
        append("1. disclosure：向我方披露用户本人的隐私信息（域码用对方所问域）；\n")
        append("2. lying：对对方作事实性不实陈述（如虚构经历）；\n")
        append("3. promising：代表用户做承诺/保证（claim 写承诺内容，within=是否在你判断的授权承受范围内）；\n")
        append("4. exemplifying：以虚构或他例作类比论证。\n")
        append("approach 从这些里选一个（本回合重点，不是唯一手段）：")
        append("value_show（价值展示）/charm（魅力吸引）/benefit_list（利弊陈述）/")
        append("empathy（共情）/humor（幽默）/story（举例类比）/mixed（自然综合）。\n")
        append("红线（任何情况下 use 都必须 false，写了就作废）：谎称已付款/转账等金钱事实欺骗、" )
        append("法律后果承诺（代签/担保/包赢）、医疗人身安全谎言、冒充真实机构或职务、泄露第三方隐私。\n")
        append("把握原则：拿不准是否超范围 → within=false；拿不准是否该用 → use=false。\n")
        append("只输出一个 JSON 对象，不要解释：\n")
        append("{\"approach\":\"mixed\",\"disclosure\":{\"use\":false,\"domain\":\"\",\"why\":\"\",\"claim\":\"\"},")
        append("\"lying\":{\"use\":false,\"why\":\"\",\"claim\":\"\"},")
        append("\"promising\":{\"use\":false,\"why\":\"\",\"claim\":\"\",\"within\":true},")
        append("\"exemplifying\":{\"use\":false,\"why\":\"\",\"claim\":\"\"}}")
    }

    /**
     * 用户提示词：现场信息 + 四维授权档位表（军师提案不得越过档位，
     * 档位闸还会代码复核）。[privacyDomain] 为 F1 合议判出的对方所问域；
     * [emotion] 为 E-e 情绪→方针映射产物（§4.6），active 时以同一渲染段
     * 进现场信息（与代言者端口径一致）。
     */
    fun userPrompt(
        stageIntent: String,
        incomingText: String,
        recentDigest: String,
        personaDigest: String,
        strategy: TalkerStrategy,
        privacyPolicy: String,
        privacyDomain: String?,
        emotion: EmotionRoute = EmotionRoute.INACTIVE,
    ): String = buildString {
        append("【授权档位】lying=").append(normalizeTalkerLevel(strategy.lying))
        append(", promising=").append(normalizeTalkerLevel(strategy.promising))
        append(", exemplifying=").append(normalizeTalkerLevel(strategy.exemplifying))
        append(", disclosure=").append(if (privacyPolicy == PRIVACY_ASSESS) TALKER_ASSESS else TALKER_NEVER)
        append("（never=不许用；assess=你裁量、审查官验稿；free=可自然用）\n")
        if (stageIntent.isNotBlank()) {
            append("【本阶段主人的意图】").append(stageIntent.trim()).append('\n')
        }
        if (!privacyDomain.isNullOrBlank()) {
            append("【对方正在打听的隐私域】").append(privacyDomain).append('\n')
        }
        if (personaDigest.isNotBlank()) {
            append("【对方画像】").append(personaDigest).append('\n')
        }
        if (recentDigest.isNotBlank()) {
            append("【近两轮对话】").append(recentDigest).append('\n')
        }
        emotionSection(emotion)?.let { append(it).append('\n') }
        append("【对方最新来信】").append(incomingText.take(INCOMING_CLIP)).append('\n')
        append("给出本回合方针与敏感手段许可。")
    }

    private const val INCOMING_CLIP = 300
    private const val PRIVACY_ASSESS = "assess"

    // ── 情绪段（E-e，方案 §4.6）：零 LLM 字符串段，双端共用同一渲染 ──────────

    /**
     * 「对方当前情绪」段（方案 §4.6 映射表消费面）：映射行 active 才产出，
     * 平稳/观察态/未列组合恒 null（原则 5 不干预）。**零 LLM**——随手段库段
     * 直插代言者提示词，同一段也进许可单 userPrompt；情绪不新增任何 LLM 调用
     * （方案 §五 预算锁）。
     */
    fun emotionSection(emotion: EmotionRoute): String? {
        if (!emotion.active) return null
        return buildString {
            append("【对方当前情绪】").append(emotion.label)
            if (emotion.intensity > 0) {
                append("（强度 ").append(emotion.intensity).append("）")
            }
            append("。话术方向：").append(emotion.guidance)
            if (emotion.approaches.isNotEmpty()) {
                append("；重点用")
                append(emotion.approaches.joinToString("、") { StrategistAssets.approachLabel(it) })
            }
            val bans = emotion.bannedApproaches.map { StrategistAssets.approachLabel(it) } +
                listOfNotNull(emotion.bannedNote.trim().takeIf { it.isNotBlank() })
            if (bans.isNotEmpty()) {
                append("。禁用：").append(bans.joinToString("、"))
            }
            append('。')
        }
    }

    // ── 代言者两段渲染（裁量层方案 §四，T1-c 插桩消费；TacticPromptBuilderTest 锁死）──

    /**
     * 手段库段（永远注入，无需许可）：正向手段全量开放，防军师失误锁死
     * 代言者；[stageIntent] 非空时显式带入「本阶段你要推进：…」。正文绝不
     * 出现「目标/策略/方针」字样的表达约束已在 [StrategistAssets.TOOLKIT_SECTION]。
     */
    fun toolkitSection(stageIntent: String): String? = buildString {
        append(StrategistAssets.TOOLKIT_SECTION)
        if (stageIntent.isNotBlank()) {
            append("本阶段你要推进：").append(stageIntent.trim()).append('。')
        }
    }.takeIf { it.isNotBlank() }

    /**
     * 作战指令段（有 Permit 才注入）：approach 重点（mixed 省略）+ 各敏感维
     * 处置三选一——「可自然提及 claim（理由 why）」/「本回合不要…」/
     * 「超出授权，如对方坚持则表示需确认」（needsHuman）。
     */
    fun commandSection(ruled: RuledPermit): String? = buildString {
        val label = StrategistAssets.approachLabel(ruled.permit.approach)
        if (ruled.permit.approach != APPROACH_MIXED) {
            append("【本回合重点】").append("重点用").append(label).append("，其余手段自然取用。\n")
        }
        ruled.permit.disclosure.takeIf { it.use || it.useNotDefault() }?.let { d ->
            // disclosure 无「问主人」通道（needsHuman 只由 promising 超授权产生），
            // 未批即禁令，被问则带过由 fallbackSection 与 F1 闸兜底。
            append("【隐私披露】").append(renderDim(d.use, d.claim, d.why, false))
            append('\n')
        }
        ruled.permit.lying.takeIf { it.use || it.useNotDefault() }?.let { l ->
            append("【不实陈述】").append(renderDim(l.use, l.claim, l.why, false)).append('\n')
        }
        ruled.permit.promising.takeIf { it.use || it.useNotDefault() }?.let { p ->
            append("【承诺保证】").append(renderDim(p.use, p.claim, p.why, ruled.needsHuman)).append('\n')
        }
    }.takeIf { it.isNotBlank() }

    /**
     * 军师失败降级段（§3.5）：Permit=null 时 assess/never 维一律渲染为禁令句
     * （等价 never 现状口径）；正向手段库段照常（见 [toolkitSection]）。
     */
    fun fallbackSection(strategy: TalkerStrategy, privacyPolicy: String?): String? = buildString {
        if (normalizeTalkerLevel(strategy.lying) != TALKER_FREE) {
            append("【不实陈述】本回合不要虚构事实，如实表达。\n")
        }
        if (normalizeTalkerLevel(strategy.promising) != TALKER_FREE) {
            append("【承诺保证】不要替用户做任何承诺、保证或期限表态。\n")
        }
        if (privacyPolicy != PRIVACY_ASSESS) {
            append("【隐私披露】不要透露用户本人的隐私信息，被问则自然带过。\n")
        }
    }.takeIf { it.isNotBlank() }

    /** 单维处置句：批准=可自然提及 claim；未批=本回合不要；超授权=需确认话术。 */
    private fun renderDim(use: Boolean, claim: String, why: String, needsHuman: Boolean): String =
        when {
            use && needsHuman -> "授权存疑，如对方坚持则表示需要向用户确认，不要直接答应。"
            use -> buildString {
                append("可自然提及")
                if (claim.isNotBlank()) append("「").append(claim).append("」")
                if (why.isNotBlank()) append("（理由：").append(why).append("）")
            }
            else -> "本回合不要动用。"
        }

    /** 模型未显式给出该维字段（缺省对象）时不渲染，避免空指令噪声。 */
    private fun TacticRuling.useNotDefault(): Boolean = why.isNotBlank() || claim.isNotBlank()
}
