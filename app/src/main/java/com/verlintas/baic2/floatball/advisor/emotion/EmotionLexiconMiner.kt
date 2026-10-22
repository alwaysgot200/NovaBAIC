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

package com.verlintas.baic2.floatball.advisor.emotion

import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.floatball.advisor.data.EMOTION_MINING_ADOPTED
import com.verlintas.baic2.floatball.advisor.data.EMOTION_MINING_PENDING
import com.verlintas.baic2.floatball.advisor.data.EMOTION_MINING_REJECTED
import com.verlintas.baic2.floatball.advisor.data.EmotionMiningCandidateEntity
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.encodeEmotionLabels
import com.verlintas.baic2.floatball.advisor.data.decodeEmotionLabels
import com.verlintas.baic2.floatball.advisor.data.toEntity
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** 挖词建议（模型提案，纯数据）：词 + 建议标签 + 理由，人审通过才入库。 */
data class MinedTermSuggestion(
    val term: String,
    val labels: List<String>,
    val reason: String,
)

@Serializable
private data class RawMinedTerm(
    val term: String = "",
    val labels: List<String> = emptyList(),
    val reason: String = "",
)

@Serializable
private data class RawMinedTerms(val terms: List<RawMinedTerm> = emptyList())

/**
 * 挖词产出解析（纯函数，JVM 可单测）：宽松容错（{"terms":[…]} / 裸数组 / 单对象），
 * 标签只认 [EmotionLabels.ALL]，term 超长截断、批内去重；畸形输出返回空表。
 */
object EmotionMinedTermParser {

    /** 候选词长度上限（词条粒度 = 词/短语，超长多为整句，不入库）。 */
    const val TERM_MAX = 12

    /** 理由行上限。 */
    const val REASON_MAX = 60

    /** 单批候选上限（少而准，别把待审列表刷成建议墙）。 */
    const val BATCH_MAX = 5

    fun parse(raw: String): List<MinedTermSuggestion> {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<MinedTermSuggestion>(BATCH_MAX)
        val seen = HashSet<String>()
        for (r in decodeTerms(text)) {
            val term = r.term.trim().take(TERM_MAX)
            if (term.isEmpty() || !seen.add(term)) continue
            val labels = r.labels.map { it.trim() }.filter { it in EmotionLabels.ALL }.distinct()
            out += MinedTermSuggestion(
                term = term,
                labels = labels,
                reason = r.reason.trim().take(REASON_MAX),
            )
            if (out.size >= BATCH_MAX) break
        }
        return out
    }

    /** 三形态宽松解码：{"terms":[…]} → 裸数组 → 单对象。全失败返回空。 */
    private fun decodeTerms(text: String): List<RawMinedTerm> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { strategyJson.decodeFromString(RawMinedTerms.serializer(), text.substring(start, end + 1)).terms }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        val arrayStart = text.indexOf('[')
        val arrayEnd = text.lastIndexOf(']')
        if (arrayStart in 0 until arrayEnd) {
            val arr = runCatching {
                strategyJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(RawMinedTerm.serializer()),
                    text.substring(arrayStart, arrayEnd + 1),
                )
            }.getOrNull()
            // 空数组不当成解码成功：单对象形态里 labels:[] 会误命中本路径，
            // 留给下面的单对象路径接住。
            if (!arr.isNullOrEmpty()) return arr
        }
        if (start >= 0 && end > start) {
            runCatching { return listOf(strategyJson.decodeFromString(RawMinedTerm.serializer(), text.substring(start, end + 1))) }
        }
        return emptyList()
    }
}

/**
 * 词库挖掘人审流（E-g 慢车道，方案 §4.7 运营闭环）：档案官慢车道用
 * [AuxiliaryTasks] 从近期对话里挖「词库没接住但明显带情绪」的口语表达
 * （新词/黑话/方言/反讽），**只落待审候选表**——模型只能提案，主人在词库
 * 管理页点采纳才入 emotion_lexicon（source=ai_mined、weight 初始 0.8），
 * 拒绝行保留供同类建议不再提。
 *
 * 节流（方案预算表）：每联系人每周 ≤1 次 + 全局节流；慢车道不计入回合预算。
 * 全链路失败静默（返回 0/空表），绝不阻断参谋回合。
 */
@Singleton
class EmotionLexiconMiner @Inject constructor(
    private val db: AdvisorDatabase,
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {

    /**
     * 机会性挖词：过节流门 + 语料非空才发起一次慢车道轻调用，产出待审候选。
     * @return 本次新落的候选条数（节流/失败/无产出 = 0）。
     */
    suspend fun maybeMine(
        contactId: Long?,
        texts: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): Int = withContext(Dispatchers.Default) {
        if (!withinThrottle(contactId, nowMs)) return@withContext 0
        val corpus = texts.map { it.trim() }.filter { it.isNotEmpty() }
            .distinct()
            .takeLast(CORPUS_LINES)
            .joinToString("\n") { it.take(CORPUS_LINE_CHARS) }
        if (corpus.isBlank()) return@withContext 0
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
            ?: return@withContext 0
        val raw = withTimeoutOrNull(MINE_TIMEOUT_MS) {
            runCatching {
                auxiliaryTasks.complete(
                    config = config,
                    systemPrompt = systemPrompt(),
                    userPrompt = "近期对话片段：\n$corpus",
                    maxTokens = MINE_MAX_TOKENS,
                    temperature = MINE_TEMPERATURE,
                )
            }.getOrNull()
        }
        if (raw.isNullOrBlank()) return@withContext 0
        val suggestions = EmotionMinedTermParser.parse(raw)
        if (suggestions.isEmpty()) return@withContext 0
        val knownTerms = runCatching { db.emotionLexiconDao().all().map { it.term.trim() } }
            .getOrDefault(emptyList())
            .toHashSet()
        val rows = suggestions
            .filter { it.term !in knownTerms }
            .map { suggestion ->
                EmotionMiningCandidateEntity(
                    term = suggestion.term,
                    labelsJson = encodeEmotionLabels(suggestion.labels),
                    reason = suggestion.reason,
                    contactId = contactId,
                    status = EMOTION_MINING_PENDING,
                    createdAt = nowMs,
                )
            }
        if (rows.isEmpty()) return@withContext 0
        // 已提过的词（含被拒的）不再提：IGNORE + 事前 find 双保险。
        val fresh = rows.filter { runCatching { db.emotionMiningDao().find(it.term) }.getOrNull() == null }
        if (fresh.isEmpty()) return@withContext 0
        runCatching { db.emotionMiningDao().insertAll(fresh) }.getOrDefault(Unit)
        fresh.size
    }

    /** 待审候选（词库管理页"AI 挖掘待审"分区）；读取失败静默返回空表。 */
    suspend fun pending(): List<EmotionMiningCandidateEntity> =
        runCatching { db.emotionMiningDao().pending() }.getOrDefault(emptyList())

    /**
     * 采纳候选：入 emotion_lexicon（source=[EmotionLexiconEntry.SOURCE_AI_MINED]、
     * weight 初始 [AI_MINED_WEIGHT]、作用域限定来源联系人防跨人污染），候选行
     * 标记 adopted。同身份词条已存在时跳过插入（IGNORE 保用户行）。
     */
    suspend fun adopt(id: Long): Boolean {
        val row = runCatching { db.emotionMiningDao().get(id) }.getOrNull() ?: return false
        if (row.status != EMOTION_MINING_PENDING) return false
        val labels = decodeEmotionLabels(row.labelsJson)
        val entry = EmotionLexiconEntry(
            term = row.term,
            library = EmotionLibraries.TRIGGER,
            labels = labels,
            polarity = polarityOf(labels),
            weight = AI_MINED_WEIGHT,
            source = EmotionLexiconEntry.SOURCE_AI_MINED,
            scopeContactId = row.contactId,
            note = row.reason,
        )
        runCatching { db.emotionLexiconDao().insertAll(listOf(entry.toEntity())) }
        runCatching {
            db.emotionMiningDao().update(row.copy(status = EMOTION_MINING_ADOPTED))
        }.isSuccess
        return true
    }

    /** 拒绝候选：标记 rejected 并保留（拒绝记忆，同类建议不再提）。 */
    suspend fun reject(id: Long): Boolean {
        val row = runCatching { db.emotionMiningDao().get(id) }.getOrNull() ?: return false
        if (row.status != EMOTION_MINING_PENDING) return false
        return runCatching {
            db.emotionMiningDao().update(row.copy(status = EMOTION_MINING_REJECTED))
        }.isSuccess
    }

    /** 节流门：每联系人每周 ≤1 次 + 全局节流（方案 §五 预算表）。 */
    private suspend fun withinThrottle(contactId: Long?, nowMs: Long): Boolean {
        val global = runCatching { db.emotionMiningDao().latestCreatedAt() }.getOrNull()
        if (global != null && nowMs - global < GLOBAL_INTERVAL_MS) return false
        val perContact = runCatching { db.emotionMiningDao().latestFor(contactId) }.getOrNull()
        return perContact == null || nowMs - perContact >= PER_CONTACT_INTERVAL_MS
    }

    /** 标签 → 入库极性（喜 +1 / 负面 −1 / 其余 0，与判定引擎同口径）。 */
    private fun polarityOf(labels: List<String>): Int = when {
        labels.any { it == EmotionLabels.JOY } -> 1
        labels.any { it in EmotionLabels.NEGATIVE } -> -1
        else -> 0
    }

    private fun systemPrompt(): String = """
        你是情绪词库的挖掘员。从对话片段里找出**容易被常规情绪词库漏掉**的口语表达
        （新词、网络黑话、方言、反讽、缩写），只挑确实带情绪色彩的，宁缺毋滥。
        只输出一个 JSON 对象，不要解释、不要 Markdown：
        {"terms":[{"term":"词条（词/短语，≤8字）","labels":["从 ${EmotionLabels.ALL.joinToString(",")} 中选 0-2 个"],
         "reason":"为什么它带情绪，≤30字"}]}
        最多 5 条；整句客套话、无情绪色彩的词不要提。
    """.trimIndent()

    companion object {
        /** AI 挖掘词条初始权重（低于内置 1.0，判错反馈可再降，方案 §4.7）。 */
        const val AI_MINED_WEIGHT = 0.8f

        /** 全局挖词最小间隔（相邻两次慢车道挖掘）。 */
        val GLOBAL_INTERVAL_MS = TimeUnit.DAYS.toMillis(3)

        /** 每联系人挖词最小间隔（方案：每周 ≤1 次）。 */
        val PER_CONTACT_INTERVAL_MS = TimeUnit.DAYS.toMillis(7)

        /** 语料预算（近期对话片段条数/单条字数，防提示词撑爆）。 */
        const val CORPUS_LINES = 20
        const val CORPUS_LINE_CHARS = 60

        const val MINE_MAX_TOKENS = 260
        const val MINE_TEMPERATURE = 0.3
        const val MINE_TIMEOUT_MS = 30_000L
    }
}
