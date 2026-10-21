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
 * 军师岗位静态资产（裁量层方案 §四/§八）：approach 手段库文案（代言者
 * 作战指令段渲染）、D4 许愿超范围判据词表（确定性、不烧 LLM）、D3 红线
 * 死清单词表（Gatekeeper 恒拦段与军师校验共用，泄露锁单测锁死）。
 * 纯常量，JVM 可直接单测。
 */
internal object StrategistAssets {

    /** approach → 用户可读文案（作战指令段「本回合重点用 …」）。 */
    val APPROACH_LABELS: Map<String, String> = mapOf(
        APPROACH_VALUE_SHOW to "价值展示",
        APPROACH_CHARM to "人格魅力吸引",
        APPROACH_BENEFIT_LIST to "利弊陈述",
        APPROACH_EMPATHY to "共情回应",
        APPROACH_HUMOR to "幽默化解",
        APPROACH_STORY to "举例类比",
        APPROACH_MIXED to "自然综合",
    )

    /** 未知 approach 归 mixed（D8）。 */
    fun approachLabel(code: String?): String =
        APPROACH_LABELS[code?.trim()] ?: APPROACH_LABELS.getValue(APPROACH_MIXED)

    /**
     * 手段库段骨架（永远全量注入，无需许可，方案 §四.1）：正向手段清单 +
     * 「正文绝不出现目标/策略/方针字样」的表达约束。
     */
    const val TOOLKIT_SECTION =
        "你可以自然运用这些正向聊天手段：真诚的价值展示、人格魅力吸引、利弊陈述、" +
            "举例类比、幽默与共情。按对话氛围自由取用，不必每一种都提到。" +
            "表达时保持自然口语，不要出现「目标」「策略」「方针」这类词。"

    /**
     * D4 许愿超范围判据词表（总方案 §三 D4：确定性词表，V1 不烧 LLM）——
     * promising.claim 命中即视为「金额/期限/身份类硬承诺」，assess 档下转
     * 问主人（needsHuman）。金额/期限锚点词 + 身份类承诺动词。
     */
    val PROMISE_WORDS: List<String> = listOf(
        // 金额类
        "元", "块钱", "万", "千块", "折", "定金", "货款", "报价", "优惠", "免费",
        // 期限类
        "今天", "明天", "后天", "本周", "下周", "周末", "月底", "小时", "分钟",
        "之前发", "之前给", "之内", "以内",
        // 身份/后果类承诺动词
        "保证", "承诺", "答应你", "一定给", "一定办", "绝对", "肯定给", "包",
        "签合同", "盖章", "书面",
    )

    /**
     * D3 红线死清单（裁量层方案 §八，任何档位绝对禁止，free 也不行）：
     * 五类判据词表，Gatekeeper 恒拦段与 [StrategistRules] 红线闸共用同一
     * 单一来源（词表锁：UI/测试直引本常量，防双表漂移）。
     */
    val REDLINE_WORDS: List<String> = listOf(
        // 金钱交易事实性欺骗（谎称已付款/假凭证）
        "已经付款", "已付款", "已经转账", "已转账", "款已", "已打款", "钱已发",
        "假凭证", "付款截图", "发票已开", "已汇款",
        // 法律后果承诺（代签/担保）
        "代签", "我担保", "担保你", "包赢", "稳赢", "官司", "诉讼", "法律责任我",
        // 医疗人身安全谎言
        "停药", "不用去医院", "别去医院", "包治", "根治", "药到病除", "不用吃药",
        // 冒充真实机构/职务背书
        "我是警察", "我是医生", "我是律师", "官方客服", "银行工作", "我是公务员",
        "派出所", "我是领导",
        // 泄露第三方隐私
        "他的身份证", "她的身份证", "他的住址", "她的住址", "他的电话", "她的电话",
        "别人身份证", "他人住址",
    )

    /** 红线命中判定：claim/稿件文本包含任一死清单词条（子串匹配，宁严勿松）。 */
    fun redlineHit(text: String): String? =
        REDLINE_WORDS.firstOrNull { text.contains(it) }

    /**
     * 我方隐私自曝语标记（Gatekeeper 越许可检测第三行，裁量层方案 §五）：
     * disclosure 未批而稿件出现「我住在/我工资…」式自曝 → 拦截降级起草。
     * 只收第一人称 + 强隐私字段组合，正向闲聊（「我最近在忙」）不误伤。
     */
    val DISCLOSURE_MARKERS: List<String> = listOf(
        "我住在", "我家住在", "我家在", "我的身份证", "我身份证", "我工资", "我月薪",
        "我月收入", "我收入", "我存款", "我的银行卡", "我银行卡", "我卡里", "我的定位",
        "我家的地址", "我家庭住址",
    )
}
