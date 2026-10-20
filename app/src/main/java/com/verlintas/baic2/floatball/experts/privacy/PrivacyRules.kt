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
 * 隐私专员的强模式词表（纯函数零 Android 依赖，JVM 可单测）。三重角色：
 * 1. **直判一票定案**：[strongHit] 命中即判隐私，不受 LLM 结论反向覆盖
 *    （宁严勿松，与 HandoffRules 的 strong-override 同构）；
 * 2. **省钱**：与 handoff 强模式共现时合议直接短路，省掉共享分类器调用；
 * 3. **降级备胎**：LLM 超时/无配置/畸形时词表是最后一道防线。
 *
 * 词表口径（方案 §3.1/§3.3）：误报代价=绕行一句/轻通报一次（小），漏报代价=
 * 隐私泄露（大）——故「直接问敏感项」类强共现才入表；歧义表达（如买家问
 * 「最低多少钱」是正常议价不是套底价）宁可放行给 LLM，由其泛化兜底。
 * 与 HandoffRules.strongHit 的私人事实条目有意重叠——重叠是双保险不是冗余。
 */
object PrivacyRules {

    private val STRONG_REGEXES: List<Pair<PrivacyDomain, Regex>> = listOf(
        // P1 身份凭证：证件/卡号/密码/验证码的直接提及。
        PrivacyDomain.IDENTITY to
            Regex("身份证|护照号?|驾照|驾驶证|行驶证|银行卡号|支付密码|登录密码|验证码|证件号|卡号"),
        // P2 健康身体：疾病/体检/身体状态（「病」单字太泛不收）。
        PrivacyDomain.HEALTH to
            Regex("体检|住院|手术|确诊|病历|过敏原?|血压|血糖|心脏病|怀孕|备孕|流产|抑郁(?:症)?|" +
                "身体(?:怎么样|状况|不太好|不好|虚)"),
        // P3 财产金融：存款/收入/负债/我方底价（买家问「最低多少钱」是正常议价，不收）。
        PrivacyDomain.FINANCE to
            Regex("存款|月薪|年薪|工资多少|收入多少|房贷|车贷|负债|公积金|分红|你的底价|" +
                "报价底线|底价多少|成本价?多少|利润(?:多少|率)|赚多少|挣多少|一个月(?:赚|挣)"),
        // P4 精确行踪：住址/定位（「你在哪」是约见面不是行踪，不收）。
        PrivacyDomain.WHEREABOUTS to
            Regex("家庭住址|住址|你家(?:住哪|住哪儿|在哪|地址|哪个小区)|小区(?:叫啥|叫什么|名字)|" +
                "发个?定位|定位[^。！？!?\\n]{0,4}(?:发|传|给)|实时位置|门牌号"),
        // P5 信仰政治。
        PrivacyDomain.BELIEF to
            Regex("宗教|信佛|信教|信仰什么|党员|党派|政治(?:观点|立场|倾向)|选举|支持哪个党"),
        // P6 未成年人：孩子的一切（对方提及即设防）。
        PrivacyDomain.MINOR to
            Regex("(?:儿子|女儿|孩子|小孩|宝宝|娃)[^。！？!?\\n]{0,8}(?:几岁|多大|几年级|" +
                "哪个学校|什么学校|生日|身份证)"),
        // P7 亲密私生活。
        PrivacyDomain.INTIMATE to
            Regex("恋爱史|感情史|前任|前男友|前女友|性生活|夫妻生活|离过婚|二婚|分手原因|同居"),
        // P8 身份属性：年龄/婚姻/职业/学历（与 HandoffRules 私人事实条目有意重叠）。
        PrivacyDomain.DEMOGRAPHIC to
            Regex("今年多大|多大了|属什么|什么星座|结婚了吗|结婚没有|离婚了吗|单身吗|单身么|" +
                "有对象吗|有男朋友吗|有女朋友吗|有孩子吗|有小孩吗|学历是什么|什么学历"),
        // P9 联系方式：索要我方电话/微信/单位（对方自报联系方式是正常交换，不收）。
        PrivacyDomain.CONTACT_INFO to
            Regex("你的?(?:电话|手机号?|微信号?|vx|微信)是多少?|(?:电话|手机号?|微信)[^。！？!?\\n]{0,4}" +
                "(?:多少|发我|给我)|住哪个(?:城市|区|省)|在哪个(?:单位|公司)上班"),
        // P10 社会关系：家庭构成/父母职业。
        PrivacyDomain.SOCIAL to
            Regex("你(?:爸妈|父母|家里)[^。！？!?\\n]{0,6}(?:做什么|干嘛|干啥|情况|知道吗)|" +
                "家庭情况|家里几口|和谁(?:住|一起住)"),
        // P11 商业机密：内部价格/成本/客户资料/未公开计划。
        PrivacyDomain.TRADE_SECRET to
            Regex("客户名单|内部价|返点|回扣|标底|未公开|内部消息"),
        // P12 立场表态：对真实事件/人物的态度（「你怎么看」单用是业务求证，不收）。
        PrivacyDomain.STANCE to
            Regex("你站哪边|支持还是反对|怎么看(?:这件事|这个事|这新闻|这个新闻|这个政策)|" +
                "你的(?:态度|立场)是|评价一下[^。！？!?\\n]{0,6}(?:领导|老板|同事|明星)"),
    )

    /** 强模式命中：返回（域, 命中片段），无命中返回 null。 */
    fun strongHit(text: String): Pair<PrivacyDomain, String>? =
        STRONG_REGEXES.firstNotNullOfOrNull { (domain, regex) ->
            regex.find(text)?.value?.let { domain to it }
        }

    /**
     * 合并判定：词表直判 ∪ LLM 结论（OR）。
     * - 词表命中 → 一票定案（[PrivacyVerdict.fromRules]=true）；
     * - LLM 判中 → 按其域码归域，未知码归 [PrivacyDomain.OTHER]（开放兜底）；
     * - 都未命中/LLM 不可用（[llm]=null）→ 不判隐私（宽松处置；严格档由编排器
     *   依 handoff 的 uncertain 口径转通报，与 E1 同款，不在此重复）。
     */
    fun judge(text: String, llm: PrivacyLlmHit?): PrivacyVerdict {
        val strong = strongHit(text)
        if (strong != null) {
            return PrivacyVerdict(hit = true, domain = strong.first, fromRules = true)
        }
        if (llm?.hit == true) {
            return PrivacyVerdict(
                hit = true,
                domain = PrivacyDomain.fromCode(llm.domain) ?: PrivacyDomain.OTHER,
            )
        }
        return PrivacyVerdict(hit = false)
    }
}
