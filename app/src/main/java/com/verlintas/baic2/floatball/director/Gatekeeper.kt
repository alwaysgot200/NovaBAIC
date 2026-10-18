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
        return null
    }

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

    /** 第一道：确定性审（转发纯函数；调用方选线程，本方法不做 IO）。 */
    fun deterministicReview(
        draft: String,
        recentReplies: List<String>,
        maxChars: Int,
    ): GateRejection? = GateRules.deterministicReview(draft, recentReplies, maxChars)

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
