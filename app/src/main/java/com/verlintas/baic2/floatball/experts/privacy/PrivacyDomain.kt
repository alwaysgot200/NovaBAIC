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
 * 隐私专员（Privacy Expert）人设：「这话/这稿碰不碰隐私红线，怎么绕」。
 *
 * 岗位说明书：只判定来信/稿件是否触碰隐私域、给出域归属与回避策略（Verdict）；
 * 不执行任何动作（不通知、不写库、不改提示词）。执行面是 Liaison（轻通报）与
 * 生成端（回避段注入）；代言上下文的事实注入口（FactRenderer，F2）是其下游。
 * 本包词表/例句是判断依据，泛化判断由合议共享分类器（HandoffGate）承担。
 */

/**
 * 事实敏感三级（2026-10-20 隐私闸门与事实记忆方案 §3.1/§4.4）：域是内容分类、
 * 档是处理等级——域决定默认档，画像页可对单条事实调档。F1 仅承载域默认档映射，
 * F2 contact_facts.sensitivity 直接消费本常量。
 */
object FactSensitivity {
    /** 无敏感：可进代言上下文明文（记忆主战场）。 */
    const val NORMAL = "normal"

    /** 中敏：明文存储，代言上下文只注入掩码形态，参谋上下文明文。 */
    const val MASKED = "masked"

    /** 高敏：代言上下文永不注入（Prompt Firewall 硬规则），参谋上下文按需明文。 */
    const val SECRET = "secret"
}

/**
 * 隐私分类学十二域 + 中性域 + 开放兜底（2026-10-20 隐私闸门方案 §3.1）。
 *
 * 不以用户举例为界，以现行标准为底座：PIPL 第 28 条敏感个人信息、
 * GB/T 35273-2020 个人信息安全规范的信息类型划分、GDPR 第 9 条特殊类别数据，
 * 再加聊天代言场景特有的两类高危项（商业机密、立场表态）。[code] 同时是
 * 分类器输出协议与台账 reason 后缀（如 privacy_bypass_p3），新增域只加枚举
 * 不改协议。
 *
 * @param code 稳定协议码（p1~p12/n0/other），落库与台账用，永不重命名
 * @param label 中文域名（通知文案/画像页分组/周报分布用）
 * @param defaultSensitivity 该域事实的默认敏感档（F2 消费；域≠档，可单条调档）
 */
enum class PrivacyDomain(
    val code: String,
    val label: String,
    val defaultSensitivity: String,
) {
    /** 身份凭证（GB/T 35273 敏感个人信息）：身份证号/护照/驾照/银行卡号/密码/验证码。 */
    IDENTITY("p1", "身份凭证", FactSensitivity.SECRET),

    /** 健康身体（PIPL 医疗健康、GDPR Art.9 health）：疾病/体检/身体特征/生物识别。 */
    HEALTH("p2", "健康身体", FactSensitivity.SECRET),

    /** 财产金融（PIPL 金融账户、GB/T 个人财产信息）：存款/收入/资产负债/金融账户/报价底线。 */
    FINANCE("p3", "财产金融", FactSensitivity.SECRET),

    /** 精确行踪（PIPL 行踪轨迹）：实时位置/家庭住址/常去地点/出行计划。 */
    WHEREABOUTS("p4", "精确行踪", FactSensitivity.SECRET),

    /** 信仰政治（GDPR Art.9、PIPL 宗教信仰）：宗教信仰/政治观点/党派倾向。 */
    BELIEF("p5", "信仰政治", FactSensitivity.SECRET),

    /** 未成年人（PIPL 特别保护，不满十四周岁）：对方提及的未成年人的一切信息。 */
    MINOR("p6", "未成年人", FactSensitivity.SECRET),

    /** 亲密私生活（GDPR Art.9 sex life）：恋爱史/性生活/私密关系细节。 */
    INTIMATE("p7", "亲密私生活", FactSensitivity.SECRET),

    /** 身份属性（GB/T 个人基本信息）：姓名/生日/年龄/婚姻/子女/职业学历。 */
    DEMOGRAPHIC("p8", "身份属性", FactSensitivity.MASKED),

    /** 联系方式（GB/T 个人通讯信息）：电话/城市/工作单位。 */
    CONTACT_INFO("p9", "联系方式", FactSensitivity.MASKED),

    /** 社会关系（GB/T 联系人信息）：家庭构成/领导同事评价/社交圈恩怨。 */
    SOCIAL("p10", "社会关系", FactSensitivity.MASKED),

    /**
     * 商业机密（商业秘密三要件：秘密性/价值性/保密措施）：内部价格/成本/客户
     * 资料/未公开计划。双向设防——对方问报价底线（我方外泄）与主人说漏客户
     * 名单（对方记录）闸门两侧都拦。
     */
    TRADE_SECRET("p11", "商业机密", FactSensitivity.SECRET),

    /**
     * 立场表态（GDPR Art.9 political opinions 映射）：对真实事件与人物的态度
     * 评价/我方立场。泄露代价是名誉与关系而非财产，且「不能代用户表态」是
     * 绝对红线——bypass 回避段对该域最强硬（倾听、反问、转移，绝不代答）。
     */
    STANCE("p12", "立场表态", FactSensitivity.SECRET),

    /** 中性事实（非个人信息，记忆主战场，不设防）：爱好/习惯/需求/愿望/共同经历。 */
    NEUTRAL("n0", "中性事实", FactSensitivity.NORMAL),

    /** 开放兜底：LLM 判出十二域之外的敏感项（宁误报不漏报）。 */
    OTHER("other", "其他敏感", FactSensitivity.SECRET),
    ;

    companion object {

        /** 分类器输出/台账 reason 后缀 → 域；未知码归 [OTHER]（开放兜底不丢信号）。 */
        fun fromCode(code: String?): PrivacyDomain? {
            val trimmed = code?.trim()?.lowercase() ?: return null
            if (trimmed.isEmpty()) return null
            return entries.firstOrNull { it.code == trimmed } ?: OTHER
        }
    }
}
