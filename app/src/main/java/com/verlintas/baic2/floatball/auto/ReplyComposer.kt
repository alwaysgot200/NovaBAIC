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
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.auto

import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「代言者修复调用」的抽象（托管方案 v8 §二十四.4）：主回合没有抽出可发送
 * 回复时，用一次极轻的独立调用写一条纯回复。接口化只为 JVM 单测可注入 fake，
 * 生产绑定见 [ReplyModule]。
 */
interface ReplyComposeSource {

    suspend fun compose(
        incoming: String?,
        analysis: String?,
        recentSent: List<String> = emptyList(),
        answerTargetChars: Int,
    ): SendableDraft?

    /**
     * 审查拦截后的合规改写（2026-10-27 真机补强，可选能力）：把 [draft] 中
     * 触发 [constraints] 的表述去掉/替换，保留原意与说话人语气，输出仍是
     * `<reply>` 契约。默认 null = 未实现改写，调用方按原拒绝路径处理。
     *
     * 存在的理由：军师未批承诺维时，主回合仍可能写「明天发您」这类期限
     * 表态，被 Gatekeeper 词表直接拦下（auto_gate_blocked）。自动档不能
     * 因此静默不发——给一次低成本的改写重试，仍不合规再降级。
     */
    suspend fun rewrite(
        draft: String,
        incoming: String?,
        constraints: String,
        answerTargetChars: Int,
    ): SendableDraft? = null
}

/**
 * 二次「代言者」调用（托管方案 v8 §二十四）：只做一件事——把「对方来信 +
 * 参谋分析」压成一条用户本人可直接发送的回复，并用 `<reply>` 契约收口。
 *
 * 定位与边界：
 * - 正常路径**零额外调用**（主回合已按契约产出 `<reply>` 时 [ReplyResolver]
 *   直接返回）；只有标记缺失/形态不合格时才走本类；
 * - 复用 [AuxiliaryTasks] 的无工具轻通道，不改 Provider 抽象、不占用主回合
 *   上下文；
 * - 输出永远不可信：解析失败/超时/无配置一律返回 null，由调用方降级起草或
 *   跳过——**绝不回落到分析稿**。
 */
@Singleton
class ReplyComposer @Inject constructor(
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) : ReplyComposeSource {

    override suspend fun compose(
        incoming: String?,
        analysis: String?,
        recentSent: List<String>,
        answerTargetChars: Int,
    ): SendableDraft? = withContext(Dispatchers.Default) {
        if (incoming.isNullOrBlank() && analysis.isNullOrBlank()) return@withContext null
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
            ?: return@withContext null
        val raw = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                auxiliaryTasks.complete(
                    config = config,
                    systemPrompt = SYSTEM_PROMPT,
                    userPrompt = userPrompt(incoming, analysis, recentSent, answerTargetChars),
                    maxTokens = MAX_TOKENS,
                    temperature = TEMPERATURE,
                )
            }.getOrNull()
        } ?: return@withContext null
        SendableDraftExtractor.extract(raw, answerTargetChars)
            ?.copy(source = SendableDraftExtractor.SOURCE_COMPOSED)
            ?: SendableDraftExtractor.extractPlain(raw, answerTargetChars)
                ?.copy(source = SendableDraftExtractor.SOURCE_COMPOSED)
    }

    override suspend fun rewrite(
        draft: String,
        incoming: String?,
        constraints: String,
        answerTargetChars: Int,
    ): SendableDraft? = withContext(Dispatchers.Default) {
        if (draft.isBlank()) return@withContext null
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
            ?: return@withContext null
        val raw = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                auxiliaryTasks.complete(
                    config = config,
                    systemPrompt = REWRITE_SYSTEM_PROMPT,
                    userPrompt = rewriteUserPrompt(draft, incoming, constraints, answerTargetChars),
                    maxTokens = MAX_TOKENS,
                    temperature = TEMPERATURE,
                )
            }.getOrNull()
        } ?: return@withContext null
        SendableDraftExtractor.extract(raw, answerTargetChars)
            ?.copy(source = SendableDraftExtractor.SOURCE_COMPOSED)
            ?: SendableDraftExtractor.extractPlain(raw, answerTargetChars)
                ?.copy(source = SendableDraftExtractor.SOURCE_COMPOSED)
    }

    companion object {

        /** 代言者输出预算：一条 1~3 句回复足够（含标记）。 */
        const val MAX_TOKENS = 220

        /** 修复调用要像本人说话，温度高于审查调用、低于自由创作。 */
        const val TEMPERATURE = 0.7

        /** 修复调用超时：超时即降级，绝不拖住自动托管队列。 */
        const val TIMEOUT_MS = 20_000L

        private const val INCOMING_CLIP = 400
        private const val ANALYSIS_CLIP = 1_200
        private const val RECENT_CLIP = 120

        /**
         * 代言者系统提示词：单一任务 + 唯一块格式 + 示例 + 红线。格式契约放在
         * 最前，避免主回合「参谋分析」人格残留。
         */
        const val SYSTEM_PROMPT =
            "你是用户的「代言者」，只做一件事：替用户本人写一条可以立刻发给对方的聊天回复。\n" +
                "输出必须严格采用下面的格式，整条输出只能有这一个块：\n" +
                "<reply>一条1~3句的中文口语回复</reply>\n" +
                "规则：\n" +
                "1. 只写用户本人会说的话，像微信随手发的：短句、自然、具体；\n" +
                "2. 不要分析、不要解释、不要编号、不要引号、不要 Markdown，不要称呼自己为 AI；\n" +
                "3. 不报价、不谈钱/转账、不替用户承诺时间或事项、不打听隐私、不提见面送礼；\n" +
                "4. 不编造用户没有说过的具体事实；信息不足时写稳妥的接话或追问；\n" +
                "5. 如果给了「参谋分析」，只把它当背景，不要照抄，也不要评价它。\n" +
                "示例：\n" +
                "<reply>刚看到消息，我这边还在忙，晚点认真回你。</reply>"

        /** 修复调用的用户段：来信 → 参谋分析（截断）→ 近期已发（防复读）。 */
        fun userPrompt(
            incoming: String?,
            analysis: String?,
            recentSent: List<String>,
            answerTargetChars: Int,
        ): String = buildString {
            append("对方最新来信：\n")
            append(
                incoming?.trim()?.take(INCOMING_CLIP)?.takeIf { it.isNotEmpty() }
                    ?: "（无来信：本轮是托管主动开场，请写一条自然的开场消息）",
            )
            analysis?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("\n\n参谋分析（仅作背景，不要照抄，不要评价）：\n")
                append(it.take(ANALYSIS_CLIP))
            }
            if (recentSent.isNotEmpty()) {
                append("\n\n近期已经发过（避免重复）：\n")
                recentSent.take(3).forEach { prior ->
                    append("- ").append(prior.replace(Regex("\\s+"), " ").take(RECENT_CLIP))
                        .append('\n')
                }
            }
            append("\n\n只输出 <reply> 块，回复控制在 ")
                .append(answerTargetChars.coerceAtLeast(SendableDraftExtractor.MIN_FALLBACK_CHARS))
                .append(" 字以内。")
        }

        /**
         * 合规改写系统提示词（[rewrite] 专用）：只做「去掉触发约束的表述」，
         * 不新增事实、不改变关系定位。与 [SYSTEM_PROMPT] 分离，避免改写时
         * 被「重新写一条」的自由度带偏。
         */
        const val REWRITE_SYSTEM_PROMPT =
            "你是用户的「代言者」，只做一件事：把一条准备发出的回复改写成合规版本。\n" +
                "输出必须严格采用下面的格式，整条输出只能有这一个块：\n" +
                "<reply>改写后的1~3句中文口语回复</reply>\n" +
                "规则：\n" +
                "1. 保留原稿想表达的意思、态度和说话人语气，只删掉或替换触发约束的表述；\n" +
                "2. 不新增原稿没有的事实、数字、时间点或承诺；\n" +
                "3. 不要分析、不要解释、不要编号、不要引号、不要 Markdown；\n" +
                "4. 删掉约束内容后若不成立，就写一句稳妥的接话，不补新承诺。"

        /** 合规改写的用户段：来信 → 待改写稿（截断）→ 必须避免的表述。 */
        fun rewriteUserPrompt(
            draft: String,
            incoming: String?,
            constraints: String,
            answerTargetChars: Int,
        ): String = buildString {
            append("对方最新来信：\n")
            append(
                incoming?.trim()?.take(INCOMING_CLIP)?.takeIf { it.isNotEmpty() }
                    ?: "（无来信：本轮是托管主动开场）",
            )
            append("\n\n待改写回复：\n")
            append(draft.trim().take(REWRITE_DRAFT_CLIP))
            append("\n\n必须避免：\n")
            append(constraints.trim())
            append("\n\n只输出 <reply> 块，回复控制在 ")
                .append(answerTargetChars.coerceAtLeast(SendableDraftExtractor.MIN_FALLBACK_CHARS))
                .append(" 字以内。")
        }

        /** 待改写稿截断：改写只做局部删改，超长稿交给 Gatekeeper 另行降级。 */
        private const val REWRITE_DRAFT_CLIP = 800
    }
}
