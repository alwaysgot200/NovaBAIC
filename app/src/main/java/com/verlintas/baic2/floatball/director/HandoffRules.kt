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

package com.verlintas.baic2.floatball.director

/**
 * 接管型（H）判定的四类判据（方案 2026-10-20 §2.3）。判定语义是「正确答案
 * 只有用户本人持有」——答案依赖用户的真实日程、真实资产、真实事实、真实决策。
 */
enum class HandoffCategory(val label: String) {

    /** 索取实体物：照片/文件/定位/地址/验证码——参谋没有任何可发的东西。 */
    ENTITY("索取物品"),

    /** 日程与出席确认：几点回家/来不来吃饭——参谋不知道用户日程。 */
    SCHEDULE("日程出席"),

    /** 私人事实询问：住址/家庭/收入——编造即事故。 */
    PRIVACY("私人事实"),

    /** 决策请求：选哪个/要不要答应——决策权在用户本人。 */
    DECISION("决策请求"),
}

/**
 * 一次接管判定的结论（纯数据，合并逻辑见 [HandoffRules.judge]）：
 * [handoff] 最终是否接管；[uncertain] 判定器不可用且词表未命中（挂回避约束）；
 * [skipLlm] 白名单快筛命中（纯肯定/表情，无需 LLM）；[category] 命中类别
 * （通知文案用）。
 */
data class HandoffJudgment(
    val handoff: Boolean,
    val uncertain: Boolean = false,
    val skipLlm: Boolean = false,
    val category: HandoffCategory? = null,
)

/**
 * 接管判定规则（方案 2026-10-20 §2.2，v2 权重反转后词表的角色）：
 * 1. **快筛白名单**（[whitelistHit]，极保守）：纯肯定/纯表情/超短欢呼 → 直接
 *    normal 跳过 LLM，省掉自动域内每条来信的分类成本；
 * 2. **强模式直判**（[strongHit]）：共现结构强到几乎不可能误命中的模式
 *    （如「几点+回家」「照片+发给我」）→ 直接接管，不等 LLM，且 LLM 判
 *    normal 时一票否决——宁严勿松（误接管=主人多看一眼通知；漏判=无人值守
 *    编造事实，代价不对称）；
 * 3. **降级备胎**：LLM 分类超时/无配置/畸形输出时，词表是最后一道防线。
 *
 * few-shot 例句库（[EXAMPLES]）供 [HandoffGate] 系统提示词消费——用户明确
 * 要求枚举库要够大：中文表达灵活，词表穷举不完，泛化交给 LLM 例句学习。
 * 纯函数零 Android 依赖，JVM 可单测。
 */
object HandoffRules {

    // ── 1. 快筛白名单：命中即 normal，跳过 LLM ─────────────────────────────

    /**
     * 纯肯定/敷衍/表情（含纯符号）。核心词覆盖肯定单字与常见语气助词
     * （「好的」「嗯嗯」这类无标点收尾的纯应答必须命中——严格档下漏掉会
     * 误报接管），尾缀标点/表情可选。
     */
    private val WHITELIST_REGEX =
        Regex("^(?:(?:好|行|嗯|哦|噢|哈|嘿|诶|欸|的|呀|啦|咯|呗|嘛|了|啊|吧|吖|喔|ok|okk|okay|nice|哈哈|嘿嘿|嘻|呵)\\s*){1,8}[~！!。.～😂🤣😄😊👍👌🙏]*$",
            RegexOption.IGNORE_CASE,
        )

    /**
     * 快筛命中：[text] 去空白后非空、整体由核心应答词与可选尾缀标点覆盖，
     * 或纯表情符号。极保守——拿不准一律放行走 LLM。
     */
    fun whitelistHit(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (WHITELIST_REGEX.matches(trimmed)) return true
        // 纯符号/纯 emoji（无任何文字字符）。
        return trimmed.none { it.code in 0x4E00..0x9FFF || it.isLetterOrDigit() }
    }

    // ── 2. 强模式直判：共现结构，误报率≈0 ────────────────────────────────

    private val STRONG_REGEXES: List<Pair<HandoffCategory, Regex>> = listOf(
        // 日程出席：「几点回家/什么时候到家/来不来/能不能来/在哪等」
        HandoffCategory.SCHEDULE to
            Regex("(几点|啥时候|什么时候|好久)[^。！？!?\\n]{0,6}(回|到|下|来)|" +
                "(回来|到家|下班|回家)[^。！？!?\\n]{0,3}(了吗|么|吗|没)|" +
                "(来不来|能不能来|可以来吗|来吗|一起去|要不要来|出来不)|" +
                "(在哪|在哪里)[^。！？!?\\n]{0,4}(等|见|碰头|集合)"),
        // 实体索取：「照片发给我/发个定位/把文件传我一下」
        HandoffCategory.ENTITY to
            Regex("(照片|图片|截图|文件|资料|视频|定位|位置|地址|链接|号码)[^。！？!?\\n]{0,10}" +
                "(发给我|发我|发一下|发过来|传给我|传我|甩我|丢给我|给我)|" +
                "(把|将)[^。！？!?\\n]{0,6}(照片|图片|截图|文件|资料|视频|定位|位置|地址)[^。！？!?\\n]{0,6}(发|传|给)"),
        // 私人事实：与 Gatekeeper.AUTO_PRIVACY 同向（此处判对方来信）。
        HandoffCategory.PRIVACY to
            Regex("你家(住|在哪)|住址|家庭住址|你(工资|月薪|收入|存款)|你(多大了|今年多大|属什么)|" +
                "你(结婚|离婚|单身|有对象|有女朋友|有男朋友)"),
        // 决策请求：「选哪个/要不要答应他/能不能接」
        HandoffCategory.DECISION to
            Regex("(选|挑)哪个|选一个|(答应|拒绝|接|要|回)[^。！？!?\\n]{0,6}(不|吗|么)[？?]\\s*$|" +
                "你(说|看|觉得)(呢|怎么办|咋办)"),
    )

    /** 强模式命中：返回（类别, 命中片段），无命中返回 null。 */
    fun strongHit(text: String): Pair<HandoffCategory, String>? =
        STRONG_REGEXES.firstNotNullOfOrNull { (category, regex) ->
            regex.find(text)?.value?.let { category to it }
        }

    // ── 3. 合并判定：LLM 主判定 + 词表否决/备胎 ───────────────────────────

    /**
     * 合并判定（方案 §2.2 合并逻辑）：
     * - [llmHandoff] = LLM 分类结论；null = LLM 不可用（超时/无配置/畸形）。
     * - 白名单命中 → normal（skipLlm=true，调用方根本不该跑 LLM）。
     * - LLM 判 handoff 或强模式命中 → HANDOFF（OR，词表一票否决 LLM 的 normal）。
     * - LLM 不可用且词表未命中 → normal 但 uncertain=true（挂生成端回避约束，
     *   宽松档；严格档由调用方按设置转换）。
     */
    fun judge(text: String, llmHandoff: Boolean?): HandoffJudgment {
        if (whitelistHit(text)) return HandoffJudgment(handoff = false, skipLlm = true)
        val strong = strongHit(text)
        if (llmHandoff == true) {
            return HandoffJudgment(
                handoff = true,
                category = (strong?.first) ?: HandoffCategory.DECISION,
            )
        }
        if (strong != null) return HandoffJudgment(handoff = true, category = strong.first)
        return HandoffJudgment(handoff = false, uncertain = llmHandoff == null)
    }

    // ── 4. few-shot 例句库：枚举「大」落在 LLM 例句学习上 ──────────────────

    /** HandoffGate 系统提示词的例句段（四类 × 8+ 条多样表达，含口语变体）。 */
    val EXAMPLES: Map<HandoffCategory, List<String>> = mapOf(
        HandoffCategory.ENTITY to listOf(
            "把上次的照片发给我",
            "你朋友圈那张图发我一下呗",
            "上次那个文件传我一下",
            "发个定位过来",
            "你家小区叫啥，我把快递寄过去",
            "把你那个资料甩我一份",
            "视频也发我一份吧",
            "合同扫描件发过来下",
            "菜单链接发给我下",
            "你电话多少，我打给你",
        ),
        HandoffCategory.SCHEDULE to listOf(
            "你今天几点回家？",
            "啥时候到家呀",
            "下班了吗，一起吃个饭？",
            "晚上去老地方吃饭，你来么？",
            "明天有空吗，出来坐坐",
            "周末能不能来一趟",
            "周日回不回来",
            "到哪了，我们在等你",
            "在哪儿见面？我去接你",
            "下午三点方便吗",
        ),
        HandoffCategory.PRIVACY to listOf(
            "你家住哪呀",
            "你工资多少啊",
            "今年多大了，属什么的",
            "结婚了吗，有对象没",
            "你家里做什么的",
            "存款有多少呀",
            "你身份证号多少",
            "你住哪个区",
        ),
        HandoffCategory.DECISION to listOf(
            "A 家和 B 家，选哪个？",
            "这两个方案你倾向哪个",
            "他说的价格行不行，要不要答应他",
            "那批货接不接？",
            "你觉得咋办",
            "这个单子还要不要继续跟",
            "他说周三交货，行不行",
            "你拿个主意呗",
        ),
    )

    /** few-shot 例句段（HandoffGate 系统提示词用，一次性拼好避免每回合重建）。 */
    val EXAMPLES_SECTION: String = EXAMPLES.entries.joinToString("\n") { (category, samples) ->
        "【${category.label}】" + samples.joinToString(" / ")
    }
}
