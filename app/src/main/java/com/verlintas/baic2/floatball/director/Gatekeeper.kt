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

import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.floatball.FrameQuality
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import com.verlintas.baic2.floatball.experts.strategist.EmotionRoute
import com.verlintas.baic2.floatball.experts.strategist.RuledPermit
import com.verlintas.baic2.floatball.experts.strategist.StrategistAssets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/**
 * 拦截原因码（REASON_* 口径）：Gatekeeper 只产码，用户文案由展示层映射
 * （strings.xml `float_gate_reason_*`），与本包其他角色的 reason 纪律一致。
 */
object GateReasons {
    /** a) 硬红线：违法/欺诈钱财/隐私索取/人身威胁（确定性黑名单命中）。 */
    const val BLACKLIST = "blacklist"

    /** b) 与近期已发内容高度雷同（确定性相似度判定）。 */
    const val REPETITIVE = "repetitive"

    /** b) AI 腔（仅轻 LLM 审可判，确定性手段判不了）。 */
    const val AI_TONE = "ai_tone"

    /** c) 超长（超过长度上限）。 */
    const val TOO_LONG = "too_long"

    // ── M3-a：自动档四类硬审（方案 §8.2，命中一律降级起草档而非丢弃） ──

    /** 自动档：涉及钱（报价/转账/付款话题）。 */
    const val AUTO_MONEY = "auto_money"

    /** 自动档：替用户答应事项/约定期限等承诺。 */
    const val AUTO_PROMISE = "auto_promise"

    /** 自动档：敏感隐私（询问或透露）。 */
    const val AUTO_PRIVACY = "auto_privacy"

    /** 自动档：超阶段动作（见面/送礼/上门等）。 */
    const val AUTO_OVERSTEP = "auto_overstep"

    // ── T1-c：越许可检测（裁量层方案 §五，军师许可单 × 稿件词表对账） ──

    /** 稿件命中 D3 红线死清单（任何档位恒拦，泄露锁）。 */
    const val TACTIC_REDLINE = "tactic_veto_redline"

    /** 承诺类词表命中而 promising 未获许可（或已判超授权问主人）。 */
    const val TACTIC_PROMISING = "tactic_veto_promising"

    /** 我方隐私自曝语命中而 disclosure 未获许可。 */
    const val TACTIC_DISCLOSURE = "tactic_veto_disclosure"

    // ── E-f：情绪红线（情感分析师方案 §4.5 行 11，盛怒禁玩笑/推进） ──

    /** 盛怒情绪红线：草稿带玩笑/梗/推进词表命中，降级重写为安抚稿（不丢稿）。 */
    const val EMOTION_VETO = "emotion_veto"
}

/**
 * 一次拒绝的产物。[replacement] 为替换建议或截断版本；null = 无可用替换
 * （保留原稿、只挂原因提示——M1 分屏预览的发送决策位是用户本人，绝不丢弃草稿）。
 */
data class GateRejection(val reason: String, val replacement: String?)

/** 轻 LLM 审的输出协议（输出永远不可信：缺省走 pass，绝不抛异常）。 */
@Serializable
internal data class LlmGateVerdict(
    val verdict: String = "pass",
    val reason: String = "",
    val rewrite: String = "",
)

/**
 * Gatekeeper 判定纯函数（M1 方案 §5.3/§8.2，零 LLM、零 Android 依赖，JVM 可单测）。
 *
 * **只拦三类，批准偏置（方案铁律）**：借鉴 momus「80% 清晰即通过，只拦阻断项」——
 * 硬红线（a）、AI 腔/重复上轮（b）、超长（c）之外的任何瑕疵一律放行，不挑刺文风、
 * 不判真实性、不评策略。词表口径：**漏报可由轻 LLM 审与用户预览兜底，误报零容忍**，
 * 因此黑名单只收「几乎不可能出现在正当参谋话术里」的索取/威胁/违法句式。
 */
object GateRules {

    /** 重复判定的最短稿长：更短的稿（如单句应答）2-gram 样本不足，不参与判定。 */
    const val REPEAT_MIN_CHARS = 12

    /**
     * 「高度雷同」阈值（[FrameQuality.textSimilarity] 字符 2-gram Jaccard）。
     * 取高不取低：宁放过相似段落，不误伤正常重复用语（批准偏置）。
     */
    const val REPEAT_SIMILARITY = 0.60

    /** LLM 审随稿附带的近期回复单条截断长度（输入预算控制）。 */
    const val PRIOR_CLIP_CHARS = 220

    /** 送审草稿截断长度（极端长稿只审前段，超长本身已由 c 类拦截）。 */
    const val DRAFT_CLIP_CHARS = 1_200

    /**
     * 硬红线词表（M1 代码常量；需要运营调词时再迁 assets）。四组方向：
     * 违法物品与行为 / 索取敏感信息（典型诈骗话术）/ 钱财欺诈诱导 / 人身威胁。
     */
    internal val REDLINE_REGEXES: List<Regex> = listOf(
        Regex("枪支|弹药|炸药|雷管|冰毒|毒品|摇头丸|迷药|迷魂药|假币|假钞|买凶|绑架|灭口"),
        Regex("(验证码|支付密码|银行卡密码|登录密码|身份证号|银行卡号)[^。！？!?…\\n]{0,12}" +
            "(发给我|发我|告诉我|发一下|是多少|提供给我|给我一下)"),
        Regex("(把|将)[^。！？!?…\\n]{0,6}(验证码|支付密码|银行卡密码|登录密码|身份证号|银行卡号)" +
            "[^。！？!?…\\n]{0,6}(发|告诉)"),
        Regex("刷单|博彩|赌球|外围下注|稳赚不赔|保本高收益|高额返利|全额返还本金"),
        Regex("弄死你|砍死你|宰了你|废了你|打断你的腿|不留活口|让他消失"),
    )

    /** 句界字符（截断/剔除都按自然句收口，不腰斩词语）。 */
    private val SENTENCE_BREAKS = charArrayOf('。', '！', '？', '!', '?', '…', '\n', '；', ';')

    /** 硬红线命中：返回命中的词表片段（供截断定位），无命中返回 null。 */
    fun findRedline(draft: String): String? =
        REDLINE_REGEXES.firstNotNullOfOrNull { it.find(draft)?.value }

    /**
     * 红线降级 = 截断版本：丢弃命中句及其后内容（命中后的展开大概率同源），
     * 保留之前的完整句子。命中在首句时返回空串（展示层改挂「整体拦截」提示）。
     */
    fun cutBeforeRedline(text: String, hit: String): String {
        val index = text.indexOf(hit)
        if (index < 0) return text
        val head = text.substring(0, index)
        val cutAt = head.lastIndexOfAny(SENTENCE_BREAKS)
        return (if (cutAt >= 0) head.substring(0, cutAt + 1) else "").trim()
    }

    /** 超长降级 = 截断版本：在 [maxChars] 窗口内取最后一个句界收口，无句界则硬切。 */
    fun truncateToLength(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val window = text.take(maxChars)
        val cutAt = window.lastIndexOfAny(SENTENCE_BREAKS)
        return (if (cutAt > 0) window.substring(0, cutAt + 1) else window).trim()
    }

    /** b) 重复判定：与任一近期回复的 2-gram 相似度达 [REPEAT_SIMILARITY] 即视为雷同。 */
    fun findRepetition(draft: String, recentReplies: List<String>): String? {
        if (draft.length < REPEAT_MIN_CHARS) return null
        return recentReplies.firstOrNull { prior ->
            prior.length >= REPEAT_MIN_CHARS &&
                FrameQuality.textSimilarity(draft, prior) >= REPEAT_SIMILARITY
        }
    }

    /**
     * 第一道：确定性三查（纯函数，零成本）。顺序 = 红线 → 超长 → 重复
     * （黑名单先行，方案 §8.2）；全部未命中返回 null = 放行。
     * [maxChars] 由调用方按用户长度档换算（目标字数 ×2），本对象不做偏好读取。
     */
    fun deterministicReview(
        draft: String,
        recentReplies: List<String>,
        maxChars: Int,
    ): GateRejection? = deterministicReview(draft, recentReplies, maxChars, auto = false)

    /**
     * M3-a 自动档入口：在三查之后追加四类硬审词表（钱/承诺/敏感隐私/超阶段）。
     * [auto]=false 时行为与旧签名完全一致（既有调用方零破坏）；[auto]=true 且
     * 命中时 replacement=null（自动档拒绝 = 降级起草档，保留原稿交人肉档，
     * 绝不截断改写——方案 §8.2「命中一律降级起草档」）。
     *
     * T1-c 追加 [tactics]（军师许可单，null=未触发/失败）：四查之后、auto 四类
     * 之前执行越许可检测（裁量层方案 §五）——许可单在场时，红线死清单与
     * 未批敏感词一律拦截降级。
     *
     * E-f 追加 [emotion]（情绪映射行，[EmotionRoute.INACTIVE]=不干预）：越许可
     * 检测之后、auto 四类之前执行情绪红线（方案 §4.5 行 11）——仅 [EmotionRoute.redline]
     * 行（盛怒）生效，玩笑/推进命中降级重写为安抚稿。
     */
    fun deterministicReview(
        draft: String,
        recentReplies: List<String>,
        maxChars: Int,
        auto: Boolean,
        tactics: RuledPermit? = null,
        emotion: EmotionRoute = EmotionRoute.INACTIVE,
    ): GateRejection? {
        val hit = findRedline(draft)
        if (hit != null) {
            return GateRejection(GateReasons.BLACKLIST, cutBeforeRedline(draft, hit))
        }
        if (draft.length > maxChars) {
            return GateRejection(GateReasons.TOO_LONG, truncateToLength(draft, maxChars))
        }
        if (findRepetition(draft, recentReplies) != null) {
            return GateRejection(GateReasons.REPETITIVE, null)
        }
        tacticReview(draft, tactics)?.let { return it }
        emotionReview(draft, emotion)?.let { return it }
        if (auto) {
            val blocked = findAutoBlock(draft)
            if (blocked != null) {
                return GateRejection(blocked.first, null)
            }
        }
        return null
    }

    // ── T1-c：越许可检测（裁量层方案 §五 四行表，纯函数 JVM 可单测） ─────

    /**
     * 军师许可单 × 稿件词表的确定性对账：代言者违反许可、或未批维度被稿件
     * 夹带时拦截。命中一律 replacement=null（降级起草档交人复核，不截断不丢稿）。
     *
     * 三行表（顺序即优先级）：
     * 1. D3 红线死清单：与许可单无关恒拦（[tactics]=null 也查——降级不放松红线）；
     * 2. D4 承诺词命中且 promising 未批（或 needsHuman 超授权）→ 拦；
     * 3. 我方隐私自曝语命中且 disclosure 未批 → 拦。
     *
     * [tactics]=null 时跳过 2/3 行：军师未触发即用户未开裁量档，该场景由
     * auto 四类硬审兜底；不会出现「无许可单反而更严」。
     */
    fun tacticReview(draft: String, tactics: RuledPermit?): GateRejection? {
        if (StrategistAssets.redlineHit(draft) != null) {
            return GateRejection(GateReasons.TACTIC_REDLINE, null)
        }
        if (tactics == null) return null
        val promising = tactics.permit.promising
        if ((!promising.use || tactics.needsHuman) &&
            StrategistAssets.PROMISE_WORDS.any { draft.contains(it) }
        ) {
            return GateRejection(GateReasons.TACTIC_PROMISING, null)
        }
        if (!tactics.permit.disclosure.use &&
            StrategistAssets.DISCLOSURE_MARKERS.any { draft.contains(it) }
        ) {
            return GateRejection(GateReasons.TACTIC_DISCLOSURE, null)
        }
        return null
    }

    // ── E-f：情绪红线（情感分析师方案 §4.5 行 11，纯函数 JVM 可单测） ─────

    /**
     * 玩笑/梗词表：盛怒时的玩笑是关系杀手（方案 §三.2 防翻车），命中即拦。
     * 只收「几乎不可能出现在正经安抚/解释稿里」的玩笑标记（误报保守：漏报可由
     * 提示词约束与用户预览兜底，误报会把正常稿错换成安抚稿）。
     */
    internal val EMOTION_JOKES: Regex = Regex(
        "开个玩笑|开玩笑|别当真|逗你玩|逗您玩|讲个笑话|笑话一则|笑死|笑不活|笑不活了|" +
            "搞笑|幽默一下|调侃|损你|损您|戏精|惊不惊喜|意不意外|玩梗|梗图|老梗|烂梗|" +
            "狗头|滑稽|233+|666+|yyds|绝绝子|lol|😂|🤣|😅|😏|😜|😝|🙃",
    )

    /**
     * 推进词表（不逼单、不营销下一步）：催促决定 / 逼单 / 约下一步的营销句式。
     * 与 AUTO_MONEY 等自动档词表互补（那边管钱与超阶段动作，这边管「催」）。
     */
    internal val EMOTION_PUSHES: Regex = Regex(
        "考虑(得|的)?怎么样|考虑好了吗|什么时候(下单|付款|购买|买|定)|" +
            "今天(就|之内)(下单|定|买|付款)|名额(有限|不多)|抓紧(时间|下单|报名|决定)|" +
            "机不可失|时不再来|错过(就|再)[^，。！？]{0,6}(没有|无)|优惠(今天|马上|即将)(结束|截止)|" +
            "要不要(来|试试|办|买)|约(个|一下)?(时间|见面|出来)|赶紧(决定|下单|定)|" +
            "快点(决定|下单|定)|定下来吧|别犹豫了|先(付|转)(定金|款)",
    )

    /**
     * 盛怒降级安抚稿模板（确定性零 LLM，方案 §4.5 行 11「降级重写为安抚稿」）：
     * 纯认错安抚、零业务主张、零玩笑零推进，任何场景都不至于说错话；
     * 原稿不丢（gateOverlays 只换渲染副本，落库原文不动）。
     */
    const val EMOTION_SOOTHING_DRAFT: String =
        "看得出这件事让你很生气，是我没处理好。你先消消气，把最在意的地方直接告诉我，" +
            "我在认真听，也一定会认真对待。"

    /**
     * E-f 情绪红线：仅 [EmotionRoute.redline] 行（盛怒）生效——玩笑/梗或推进
     * 词表命中即拦，替换为 [EMOTION_SOOTHING_DRAFT] 安抚稿；其余行（不满/低落/
     * 试探等）与 [EmotionRoute.INACTIVE] 一律放行（范围纪律：红线只锁盛怒，
     * 误报保守）。无命中返回 null = 放行。
     */
    fun emotionReview(draft: String, emotion: EmotionRoute): GateRejection? {
        if (!emotion.redline) return null
        val joke = EMOTION_JOKES.find(draft)?.value
        val push = EMOTION_PUSHES.find(draft)?.value
        if (joke == null && push == null) return null
        return GateRejection(GateReasons.EMOTION_VETO, EMOTION_SOOTHING_DRAFT)
    }

    // ── M3-a：自动档四类词表（方案 §8.2，纯函数 JVM 可单测） ─────────────

    /**
     * 自动档四类词表（类别码 → 正则）。口径与硬红线相反：自动档误报的代价只是
     * 「降级起草档交人复核」，而漏报的代价是无人值守错发，因此**宁严勿松**——
     * 钱话题零容忍（含正当销售话术里的「转账/报价」），与 [REDLINE_REGEXES]
     * 的「误报零容忍」互补。命中一律降级，不试图改写。
     */
    internal val AUTO_REGEXES: List<Pair<String, Regex>> = listOf(
        GateReasons.AUTO_MONEY to Regex(
            "报价|报个价|多少钱|什么价|价格多少|怎么卖|转账|转给你|转给我|转过去|打款|付款|收款|" +
                "发.?红包|收.?红包|定金|尾款|货款|佣金|返点|折扣价|优惠价|最低价",
        ),
        GateReasons.AUTO_PROMISE to Regex(
            "我答应|答应你|答应您|我保证|向你保证|向您保证|我承诺|说定了|就这么定|说好了|" +
                "包在我身上|一定给你办|一定给您办|我替你|我帮您搞定|帮你搞定|" +
                "明天[之]?前(给你|给您|完成|办好|搞定)|周[一二三四五六日天][之]?前(给你|给您|交付|完成|办好)",
        ),
        GateReasons.AUTO_PRIVACY to Regex(
            "你家住哪|住址在哪|家庭住址|身份证号|工资多少|月薪多少|收入多少|存款多少|" +
                "身高体重|你多大了|今年多大|属什么的|离婚了吗|结婚了吗|单身吗",
        ),
        GateReasons.AUTO_OVERSTEP to Regex(
            "约你出来|约您出来|见一面|见个面|见面吧|见面聊|请你吃饭|请你喝茶|请你喝|" +
                "送你礼物|送你一份|给你买|带你去|去看电影|去你家|来我家|来找你玩|一起旅游",
        ),
    )

    /** 自动档四类命中：返回（类别码, 命中片段），无命中返回 null。 */
    fun findAutoBlock(draft: String): Pair<String, String>? =
        AUTO_REGEXES.firstNotNullOfOrNull { (reason, regex) ->
            regex.find(draft)?.value?.let { reason to it }
        }

    /**
     * 自动档生成端规约追加段（方案 §8.2「红线继承：forbidden 追加」）：总闸开启
     * 且联系人在白名单内时，由调用方拼进系统提示词，让模型从源头少产高危内容；
     * 与 [deterministicReview] 的四类硬审形成「事前约束 + 事后拦截」双保险。
     */
    fun autoForbiddenSection(): String =
        "【自动档红线】本条草稿将由系统自动发送、无人逐字复核，因此额外约束：" +
            "不报价、不谈转账/付款/钱数话题；不替用户答应任何事项、不承诺具体时间或期限；" +
            "不询问、不透露敏感隐私（住址、证件、收入、年龄、婚恋状况等）；" +
            "不提出见面、约会、送礼、上门等超阶段动作。" +
            "只写自然、轻松、低风险的日常回应。"

    /**
     * `<reply>` 标记协议规约段（托管方案 §四.A.1 主路径）：与 [autoForbiddenSection]
     * 同为自动档激活时由调用方拼进系统提示词的生成端约束。模型在回复末尾用
     * <reply>…</reply> 包住一条可直接发送的纯回复；标记之外禁止自评展开
     * （「这样回复会…」式），从源头减少需要修剪的稿子——与发送侧
     * SendableDraftExtractor 的确定性抽取构成双保险。
     */
    fun replyProtocolSection(): String =
        "【发送格式约定】本条回复可能由系统自动发送。请在回复正文的最末尾，" +
            "另起一行用 <reply> 和 </reply> 标记包住一条可直接发送给对方的纯回复：" +
            "只写回复本身（一到三句自然口语），不带编号、不带称呼客套、不带分析解释、" +
            "不带 Markdown；<reply> 标记之外不要写任何「这样回复会…」式的自我点评。"

    /** 自动档 forbidden 台账追加项（落 talk_missions.forbiddenJson 前合并，供分屏红线展示）。 */
    val AUTO_FORBIDDEN_ITEMS: List<String> = listOf(
        "不报价、不谈转账与付款话题",
        "不代用户做决定",
        "不替用户答应任何事项",
        // F1 自问自答防线（隐私闸门方案 §3.4）：主动保温/自动档不主动掏隐私。
        "不主动询问或透露机主隐私（证件/财产/住址/立场等）",
    )

    /** 轻 LLM 审输出的宽松解析：去 ```json 围栏并截取首个 {…}；畸形输出返回 null（放行）。 */
    internal fun parseReview(raw: String): LlmGateVerdict? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            strategyJson.decodeFromString(LlmGateVerdict.serializer(), text.substring(start, end + 1))
        }.getOrNull()
    }

    /**
     * 第二道判定的纯函数化：只有显式 `reject` 且理由码落在两类阻断项内才拦，
     * 模型自由发挥的任何其他理由一律视为 pass（**LLM 审不得扩大拦截范围**，
     * 方案 §5.3 边界）。rewrite 为空时 replacement=null（保留原稿只挂提示）。
     */
    internal fun llmRejection(verdict: LlmGateVerdict): GateRejection? {
        if (!verdict.verdict.trim().equals(REJECT, ignoreCase = true)) return null
        return when (verdict.reason.trim().lowercase()) {
            GateReasons.AI_TONE ->
                GateRejection(GateReasons.AI_TONE, verdict.rewrite.trim().takeIf { it.isNotEmpty() })
            GateReasons.REPETITIVE ->
                GateRejection(GateReasons.REPETITIVE, verdict.rewrite.trim().takeIf { it.isNotEmpty() })
            else -> null
        }
    }

    internal const val REJECT = "reject"

    /** 轻 LLM 审的数据段：近期已发（供改写重复判定）+ 草稿，逐段确定性截断。 */
    internal fun reviewUserPrompt(draft: String, recentReplies: List<String>): String = buildString {
        if (recentReplies.isNotEmpty()) {
            append("近期已发：\n")
            recentReplies.take(2).forEach { prior ->
                append("- ").append(prior.replace(Regex("\\s+"), " ").take(PRIOR_CLIP_CHARS)).append('\n')
            }
        }
        append("草稿：\n").append(draft.take(DRAFT_CLIP_CHARS))
    }
}

/**
 * Gatekeeper（多代理方案 §5.3 critic·事前 行 / §10 M1-C）：发送前硬审的
 * 双重实现。M1 挂在连续模式分屏预览（§13.2 矛盾#4：起草档 M2 才存在），
 * M3 起同一判定迁去自动档发送前，判定逻辑不变。
 *
 * 双重审核：确定性三查先行（纯函数零成本，见 [GateRules]）→ 未命中再走
 * 轻 LLM 审（≤120 tok、低温度、短超时，只看 AI 腔/换皮重复）。**批准偏置**：
 * LLM 审任何失败（无配置/超时/畸形输出/理由码超出两类）一律放行，绝不扩大
 * 拦截范围；拒绝必带替换建议或截断版本（降级不丢弃）。
 */
@Singleton
class Gatekeeper @Inject constructor(
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {

    /** 第一道：确定性审（转发纯函数；调用方选线程，本方法不做 IO）。
     * [auto]=true 时追加四类自动档硬审（钱/承诺/敏感隐私/超阶段，M3-a）；
     * [tactics] 非空时追加军师许可单越许可检测（T1-c，裁量层方案 §五）；
     * [emotion] 为盛怒行时追加情绪红线（E-f，方案 §4.5 行 11）。 */
    fun deterministicReview(
        draft: String,
        recentReplies: List<String>,
        maxChars: Int,
        auto: Boolean = false,
        tactics: RuledPermit? = null,
        emotion: EmotionRoute = EmotionRoute.INACTIVE,
    ): GateRejection? =
        GateRules.deterministicReview(draft, recentReplies, maxChars, auto, tactics, emotion)

    /**
     * 第二道：轻 LLM 审。返回 null = 放行（含一切失败路径）。
     * [recentReplies] 供模型识别换皮重复，新→旧，最多消费 2 条。
     */
    suspend fun llmReview(draft: String, recentReplies: List<String>): GateRejection? =
        withContext(Dispatchers.Default) {
            val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
                ?: return@withContext null
            val raw = withTimeoutOrNull(REVIEW_TIMEOUT_MS) {
                runCatching {
                    auxiliaryTasks.complete(
                        config = config,
                        systemPrompt = REVIEW_SYSTEM_PROMPT,
                        userPrompt = GateRules.reviewUserPrompt(draft, recentReplies),
                        maxTokens = REVIEW_MAX_TOKENS,
                        temperature = REVIEW_TEMPERATURE,
                    )
                }.getOrNull()
            }
            val verdict = raw?.let { GateRules.parseReview(it) } ?: return@withContext null
            // 批准偏置：只有显式 reject 且理由码在两类内才拦（纯函数裁决，见 GateRules）。
            GateRules.llmRejection(verdict)
        }

    private companion object {
        /** 慢车道口径（方案 §9 预算表：Gatekeeper ≤120 tok、低温度、超时短）。 */
        const val REVIEW_MAX_TOKENS = 120
        const val REVIEW_TEMPERATURE = 0.2
        const val REVIEW_TIMEOUT_MS = 15_000L

        /**
         * 守门员系统提示词：只审两类阻断项 + 批准偏置写死在协议里
         * （禁止以真实性/策略/语法等任何其他理由拒绝）。
         */
        const val REVIEW_SYSTEM_PROMPT =
            "你是聊天参谋的守门员。只判断给定草稿是否属于以下两类阻断项：\n" +
                "1. ai_tone：明显 AI 腔——空洞套话、模板腔、堆砌客套，让对方一眼识破是 AI 代写；\n" +
                "2. repetitive：与「近期已发」内容实质雷同（换皮复读）。\n" +
                "批准偏置：拿不准、只是文风差异、轻微瑕疵，一律放行；" +
                "禁止以真实性、策略、事实性、语法等任何其他理由拒绝。\n" +
                "只输出一个 JSON 对象，不要解释、不要 Markdown：\n" +
                "通过：{\"verdict\":\"pass\"}\n" +
                "拒绝：{\"verdict\":\"reject\",\"reason\":\"ai_tone或repetitive\"," +
                "\"rewrite\":\"重写后的自然口语版本，≤80字，可直接发送\"}\n" +
                "拒绝时必须给出 rewrite。"
    }
}
