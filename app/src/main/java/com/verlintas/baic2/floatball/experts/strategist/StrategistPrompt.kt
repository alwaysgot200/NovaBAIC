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
     * 档位闸还会代码复核）。[privacyDomain] 为 F1 合议判出的对方所问域。
     */
    fun userPrompt(
        stageIntent: String,
        incomingText: String,
        recentDigest: String,
        personaDigest: String,
        strategy: TalkerStrategy,
        privacyPolicy: String,
        privacyDomain: String?,
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
        append("【对方最新来信】").append(incomingText.take(INCOMING_CLIP)).append('\n')
        append("给出本回合方针与敏感手段许可。")
    }

    private const val INCOMING_CLIP = 300
    private const val PRIVACY_ASSESS = "assess"
}
