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

package com.verlintas.baic2.floatball.experts.privacy

/**
 * 隐私专员的资产库：十二域生成端回避文案 + 合议共享分类器的 few-shot 例句。
 * 纯常量无状态；枚举库「大」落在分类器例句学习上（用户拍板，同 HandoffRules）。
 *
 * 本对象属共享分类器的例句资产，被 director/HandoffGate 消费——依组织铁律
 * 「共享领域模型除外」条款跨包引用，是既定例外而非违规。
 */
object PrivacyAssets {

    /**
     * bypass 档生成端回避段（方案 §3.4，同 HANDOFF_AVOID_SECTION 通道，插在
     * autoDirectiveSection 之后）：按命中域给定制约束略——共同底线是「不提供、
     * 不确认、不表态，自然带过并反问对方」。
     */
    private val AVOID_TEXTS: Map<PrivacyDomain, String> = mapOf(
        PrivacyDomain.IDENTITY to
            "任何证件号码、银行卡号、密码、验证码都绝不能出现在回复里，也绝不确认或提示线索；" +
                "直接说明这类信息不便在聊天里发送，如对方确有需要建议对方联系机主本人。",
        PrivacyDomain.HEALTH to
            "不透露任何疾病、体检、用药、身体状况信息；表达关心但把话题引开，" +
                "例如「谢谢关心，没什么大事」，绝不展开细节。",
        PrivacyDomain.FINANCE to
            "不透露存款、收入、资产、负债等任何财务信息；涉及报价底线时只说" +
                "「这个需要再算算/回头给你个准数」，绝不亮出我方可接受的最低条件。",
        PrivacyDomain.WHEREABOUTS to
            "不透露家庭住址、实时位置、常去地点与出行安排；只说「到时候联系」，" +
                "具体行程留给机主本人安排。",
        PrivacyDomain.BELIEF to
            "不透露宗教信仰、政治观点与党派倾向；不评价任何党派与宗教话题，" +
                "用「这些我不太方便聊」轻轻带过并转回日常话题。",
        PrivacyDomain.MINOR to
            "对方提及的孩子（未成年人）信息一概不接话、不补充、不确认；" +
                "把话题从孩子身上移开。",
        PrivacyDomain.INTIMATE to
            "恋爱史、感情经历、私密关系细节一律不谈；不确认也不否认，" +
                "用玩笑或转移话题的方式化解。",
        PrivacyDomain.DEMOGRAPHIC to
            "不报年龄、生日、婚姻状况、职业学历等身份信息；可用「你猜呀」" +
                "这类轻松话术带过，绝不给具体答案。",
        PrivacyDomain.CONTACT_INFO to
            "不透露电话、微信、工作单位等联系方式；说明「联系方式回头由本人给你」，" +
                "由机主本人决定是否交换。",
        PrivacyDomain.SOCIAL to
            "不透露家庭构成、父母职业、领导同事评价等关系信息；" +
                "以「家里人都是普通人」这类模糊说法带过。",
        PrivacyDomain.TRADE_SECRET to
            "绝不透露内部价格、成本、客户名单、未公开计划等商业信息；" +
                "对方追问底价时只说「价格好商量，具体方案回头详谈」。",
        PrivacyDomain.STANCE to
            "对任何真实事件、人物、组织都不表态、不评价、不站队；" +
                "用「这个我还在了解」带过，多倾听、多反问对方的看法，绝不代机主亮立场。",
        PrivacyDomain.OTHER to
            "对方问到的内容属于机主本人的私密信息：不提供、不确认、不表态，" +
                "用自然的话术轻轻带过并把话题引回日常。",
    )

    /** 生成端回避段：域标题 + 定制约束（进 system prompt 的 custom 段）。 */
    fun avoidSection(domain: PrivacyDomain): String =
        "【隐私回避：${domain.label}】对方的话涉及机主本人的${domain.label}，" +
            (AVOID_TEXTS[domain] ?: AVOID_TEXTS.getValue(PrivacyDomain.OTHER)) +
            "除此之外的日常话题照常自然聊。"

    /**
     * 合议共享分类器的隐私 few-shot 例句库（十二域 × 3+ 条多样表达）。
     * 覆盖不限于：中文表达灵活，词表穷举不完，泛化交给例句学习。
     */
    val EXAMPLES: Map<PrivacyDomain, List<String>> = mapOf(
        PrivacyDomain.IDENTITY to listOf(
            "你身份证号多少",
            "把你银行卡号发我，我给你转钱",
            "手机验证码发我一下",
        ),
        PrivacyDomain.HEALTH to listOf(
            "你最近身体怎么样，听说你住院了？",
            "你那个病好点没",
            "你体检报告出来了吗，什么情况",
        ),
        PrivacyDomain.FINANCE to listOf(
            "你存款有多少呀",
            "你一个月挣多少钱啊",
            "你们报价底线到底是多少",
        ),
        PrivacyDomain.WHEREABOUTS to listOf(
            "你家住哪个小区呀",
            "把你的定位发一下",
            "你明天下午在家吗，我过去找你",
        ),
        PrivacyDomain.BELIEF to listOf(
            "你信佛吗",
            "这次投票你支持谁",
            "你是哪个党派的",
        ),
        PrivacyDomain.MINOR to listOf(
            "你儿子几年级了，在哪个学校",
            "你家孩子生日是哪天",
            "小姑娘多大了",
        ),
        PrivacyDomain.INTIMATE to listOf(
            "你跟你前任怎么分的",
            "你谈过几次恋爱呀",
            "你俩同居了吗",
        ),
        PrivacyDomain.DEMOGRAPHIC to listOf(
            "你今年多大了，属什么的",
            "结婚了吗，有对象没",
            "你是做什么工作的，什么学历",
        ),
        PrivacyDomain.CONTACT_INFO to listOf(
            "你手机号多少，我加一下",
            "你微信号是多少",
            "你在哪个单位上班呀",
        ),
        PrivacyDomain.SOCIAL to listOf(
            "你爸妈是做什么的",
            "你家里几口人呀",
            "你跟你们领导关系怎么样",
        ),
        PrivacyDomain.TRADE_SECRET to listOf(
            "你们这个成本多少",
            "把你客户名单给我参考下",
            "你们内部价是多少",
        ),
        PrivacyDomain.STANCE to listOf(
            "这件事你站哪边",
            "你觉得这个政策怎么样，支持还是反对",
            "你怎么看你们老板这个人",
        ),
    )

    /** few-shot 例句段（合议共享分类器系统提示词用，一次性拼好避免每回合重建）。 */
    val EXAMPLES_SECTION: String = EXAMPLES.entries.joinToString("\n") { (domain, samples) ->
        "【${domain.label}】" + samples.joinToString(" / ")
    }
}
