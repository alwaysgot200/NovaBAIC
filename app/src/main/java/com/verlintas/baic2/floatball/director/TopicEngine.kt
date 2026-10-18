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

import com.verlintas.baic2.floatball.advisor.data.WARM_CHAT_MISSION_PREFIX
import kotlinx.serialization.Serializable

/**
 * 素材源（方案 §7.2）。枚举声明顺序即选题优先级：方向（Planner 周编译的下周期
 * 废话方向，V-C 方案 §4.4——最贴目标阶段但易空转，靠既有指纹去重轮换）＞
 * 对方画像钩子（最贴合对方、最易被接住）＞ 共同经历（关系专属）＞ 个人素材
 * （用户投喂，鲜活但泛于画像/经历）＞ 种子库（中庸兜底）。个人素材插位推导：
 * 方案 §7.2 把「我的素材库」（种子+投喂）列为源②，但同图标注「权重=画像 tag
 * 匹配度」而非源序硬分——源序只是枚举。投喂素材由用户主动录入、比静态种子
 * 鲜活（具个人色彩、可含时效内容），但相关度弱于对方画像钩子与双方共同经历
 * （那两源天然贴着「这个人、这段关系」），故置于共同经历之后、种子库之前。
 */
enum class TopicSource { PLAN_DIRECTION, PROFILE_HOOK, SHARED_EXPERIENCE, PERSONAL_MATERIAL, SEED_BANK }

/** 保温话题种子（assets/advisor/topic_seeds.json 单条，方案 §6 静态资产）。 */
@Serializable
data class TopicSeed(
    val topic: String = "",
    /** 切入角度：给 LLM 的"怎么聊"提示；空 = 由模型按语境自定。 */
    val angle: String = "",
    /** 素材类型标签（饮食/兴趣/日常…），与对方画像 tags 的命中数作同源内加权。 */
    val tags: List<String> = emptyList(),
    /** 话题深度：1=浅层（兴趣/见闻）2=中层（经历/观点）3=深层（个人往事/情感）。 */
    val depth: Int = TopicEngine.DEPTH_SHALLOW,
    /** 亲疏门槛（V-C §4.2）：0~3，低于对方 closeness 的种子不投喂；缺省 0 不设限。 */
    val minCloseness: Int = 0,
)

/**
 * 个人素材的引擎内形态（V-C）：[content] 为素材正文（话题本体），[styleTags]
 * 为归一后的风格词（吃喝/玩乐/段子…，由 TopicStyleRules.styleTagsOf 从用户
 * 自由 tag 派生），供三重过滤的禁区/降权裁决。
 */
data class MaterialItem(val content: String, val styleTags: List<String> = emptyList())

/** 种子库文件根结构（照 scenario_packs 惯例带外壳，便于后续扩展元字段）。 */
@Serializable
data class TopicSeedBank(val seeds: List<TopicSeed> = emptyList())

/**
 * 保温选题纯函数引擎（M1 方案 §7.2，零 LLM、零 Android 依赖，JVM 可单测）。
 *
 * 约束：LLM 只负责"把选题聊活"，"选什么题"由本引擎确定性裁决——同输入恒同
 * 输出（无随机、不读时钟，时间一律经 [Materials.nowMs] 注入）。硬规则：
 * ①话题指纹去重（近 [DEDUP_WINDOW] 条已用不重复）；②同题冷却（[COOLDOWN_MS]
 * 内不复用，覆盖去重窗口之外的慢频率联系人）；③阶段门控（深度超过
 * [Materials.stageDepth] 的话题不投喂——弱联系只碰浅层）。全部候选被滤尽
 * 时逐级放宽（先放冷却、再放去重，兜底取"最久未用"），保证手动入口在题库
 * 见底时仍能出题而非空转；时事禁令不在此处（D3 属提示词约束，见调用方）。
 */
object TopicEngine {

    /** 浅层：兴趣/见闻/日常，任何阶段可用。 */
    const val DEPTH_SHALLOW = 1

    /** 中层：经历/观点，舒适度建立后才碰。 */
    const val DEPTH_MEDIUM = 2

    /** 深层：个人往事/情感话题，仅熟络期。 */
    const val DEPTH_DEEP = 3

    /** 指纹去重窗口（条）：近期 N 条任务令内用过的话题不再重复（方案：读近 60 条台账）。 */
    const val DEDUP_WINDOW = 20

    /** 同一话题冷却期：去重窗口之外的"同题最短间隔"（慢频率联系人场景）。 */
    const val COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000

    /** 画像钩子/共同经历参与候选的条数上限（防极端画像把提示词撑爆）。 */
    const val MAX_HOOKS = 6
    const val MAX_EXPERIENCES = 6

    /** 个人素材参与候选的条数上限（同上；素材库无上限，选题只取最近录入的若干条）。 */
    const val MAX_MATERIALS = 6

    /** 话题文本长度上限：钩子/经历源自自由文本，超长截断保持"话题"粒度。 */
    const val MAX_TOPIC_CHARS = 40

    /** 已用话题记录：[fingerprint] 为 [fingerprintOf] 规范化结果；列表契约 = 新→旧。 */
    data class UsedTopic(val fingerprint: String, val usedAtMs: Long)

    /**
     * 选题输入（方向 + 三源 + 门控原料）。[usedTopics] 由调用方从 talk_missions
     * 台账解码（新→旧，上限 60 条）；[stageDepth] 用 [stageDepthOf] 从站码换算；
     * [planDirections] 为 Planner 周编译的下周期方向（最前消费）；[filter] 为
     * 风格三重过滤（null = 不设限，共享会话/无画像路径）。
     */
    data class Materials(
        val profileHooks: List<String> = emptyList(),
        val interestTags: Set<String> = emptySet(),
        val sharedExperiences: List<String> = emptyList(),
        /** 我的素材库（用户投喂，方案 §7.2 源②）：条目含风格词，指纹与台账同轨去重。 */
        val personalMaterials: List<MaterialItem> = emptyList(),
        val seeds: List<TopicSeed> = emptyList(),
        val usedTopics: List<UsedTopic> = emptyList(),
        val stageDepth: Int = DEPTH_SHALLOW,
        val nowMs: Long = 0L,
        /** Planner 周方向（V-C §4.4）：指纹不在去重窗口内的第一个方向优先出题。 */
        val planDirections: List<String> = emptyList(),
        /** 风格三重过滤（V-C §4.2）；null = 不过滤。 */
        val filter: TopicFilter? = null,
    )

    /** 选题产物：拼进保温提示词段与 missionHint（"话题候选=X（角度=Y），可弃用"）。 */
    data class TopicPick(
        val source: TopicSource,
        val topic: String,
        val angle: String,
    )

    /** 候选（内部形态）：ordinal 为同源内原始顺序，保证排序全序、结果确定。 */
    private data class Candidate(
        val source: TopicSource,
        val topic: String,
        val angle: String,
        val tagMatches: Int,
        val depth: Int,
        val ordinal: Int,
        val fingerprint: String,
    )

    /**
     * 确定性选题。排序键（宽松档额外前置"最久未用"）：源优先级 → 标签命中数
     * 降序 → 同源原始顺序。返回 null 仅当阶段门控后无任何候选。
     * 方向优先（V-C §4.4）：指纹不在去重窗口内的第一个 [Materials.planDirections]
     * 直接出题（TopicSource.PLAN_DIRECTION）；方向全用过则照常走四源。
     */
    fun select(materials: Materials): TopicPick? {
        val window = materials.usedTopics.asSequence().take(DEDUP_WINDOW)
            .map { it.fingerprint }.toHashSet()
        // 方向优先消费：去过重（窗口内说明近期已聊），其余照旧。
        materials.planDirections.firstOrNull { it.isNotBlank() && fingerprintOf(it) !in window }
            ?.let { direction ->
                return TopicPick(
                    TopicSource.PLAN_DIRECTION,
                    direction.take(MAX_TOPIC_CHARS),
                    "围绕这个方向挑一个具体话头，自然开场，不必拘泥原文表述",
                )
            }
        val candidates = candidatesOf(materials)
        if (candidates.isEmpty()) return null
        val nowMs = materials.nowMs

        val lastUsedAt = HashMap<String, Long>()
        materials.usedTopics.forEach { used ->
            val prev = lastUsedAt[used.fingerprint]
            if (prev == null || used.usedAtMs > prev) lastUsedAt[used.fingerprint] = used.usedAtMs
        }

        // 逐级放宽：严格（去重+冷却）→ 仅去重 → 全放开（取最久未用）。
        val strict = candidates.filter { c ->
            c.fingerprint !in window && (lastUsedAt[c.fingerprint]?.let { nowMs - it >= COOLDOWN_MS } ?: true)
        }
        val dedupOnly = candidates.filter { c -> c.fingerprint !in window }
        val (pool, preferOldest) = when {
            strict.isNotEmpty() -> strict to false
            dedupOnly.isNotEmpty() -> dedupOnly to false
            else -> candidates to true
        }
        val pick = pool.sortedWith(
            compareBy(
                { if (preferOldest) lastUsedAt[it.fingerprint] ?: 0L else 0L },
                { it.source.ordinal },
                { -it.tagMatches },
                { it.ordinal },
            ),
        ).first()
        return TopicPick(pick.source, pick.topic, pick.angle)
    }

    /**
     * 三源候选构建 + 门控（V-C 三重过滤 + 阶段门控）。风格禁区/降权/深度双门
     * 只作用于种子库与个人素材（两源是**泛化**内容，风格词可判）；画像钩子与
     * 共同经历不过滤——它们天然贴着对方本人/双方关系，是过滤后仍要保住的
     * 兜底源（否则领导型联系人会开天窗无题可出）。
     */
    private fun candidatesOf(m: Materials): List<Candidate> {
        val stageDepth = m.stageDepth.coerceIn(DEPTH_SHALLOW, DEPTH_DEEP)
        // 深度双门：阶段档与风格 maxDepth 取更严（filter null = 不设限）。
        val styleMaxDepth = m.filter?.maxDepth?.coerceIn(DEPTH_SHALLOW, DEPTH_DEEP) ?: DEPTH_DEEP
        val gateDepth = minOf(stageDepth, styleMaxDepth)
        val forbidden = m.filter?.forbiddenTags.orEmpty()
        val penalty = m.filter?.penaltyTags.orEmpty()
        val out = ArrayList<Candidate>()
        m.profileHooks.asSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }
            .take(MAX_HOOKS)
            .forEachIndexed { i, text ->
                out += Candidate(
                    TopicSource.PROFILE_HOOK, text.take(MAX_TOPIC_CHARS), "", 0,
                    DEPTH_SHALLOW, i, fingerprintOf(text),
                )
            }
        m.sharedExperiences.asSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }
            .take(MAX_EXPERIENCES)
            .forEachIndexed { i, text ->
                out += Candidate(
                    TopicSource.SHARED_EXPERIENCE, text.take(MAX_TOPIC_CHARS), "", 0,
                    DEPTH_MEDIUM, i, fingerprintOf(text),
                )
            }
        // 个人素材：深度恒浅层——投喂素材以趣事/见闻/资源为主（方案 §7.2），
        // 弱联系期也可投喂；更私人的素材天然走共同经历源，不在此提级。
        m.personalMaterials.asSequence()
            .map { it.copy(content = it.content.trim()) }
            .filter { it.content.isNotEmpty() }
            .take(MAX_MATERIALS)
            .forEachIndexed { i, item ->
                val styleTags = TopicStyleTags.styleTagsOf(item.styleTags)
                if (styleTags.any { it in forbidden }) return@forEachIndexed
                // 素材无正加权（保持原语义恒 0）；风格词命中降权表只扣分。
                val matched = -styleTags.count { it in penalty }
                out += Candidate(
                    TopicSource.PERSONAL_MATERIAL, item.content.take(MAX_TOPIC_CHARS), "",
                    matched, DEPTH_SHALLOW, i, fingerprintOf(item.content),
                )
            }
        m.seeds.asSequence()
            .map { it.copy(topic = it.topic.trim(), angle = it.angle.trim()) }
            .filter { it.topic.isNotEmpty() }
            .forEachIndexed { i, seed ->
                // 亲疏门（V-C）：种子要求浅于对方亲疏才投喂（filter null = 未萃取不设限）。
                val filter = m.filter
                if (filter != null && !filter.seedAllowed(seed.minCloseness)) return@forEachIndexed
                val seedStyle = TopicStyleTags.styleTagsOf(seed.tags)
                if (seedStyle.any { it in forbidden }) return@forEachIndexed
                val matched = seed.tags.count { tag -> tag.trim() in m.interestTags } -
                    seedStyle.count { it in penalty }
                out += Candidate(
                    TopicSource.SEED_BANK,
                    seed.topic.take(MAX_TOPIC_CHARS),
                    seed.angle,
                    matched,
                    seed.depth.coerceIn(DEPTH_SHALLOW, DEPTH_DEEP),
                    i,
                    fingerprintOf(seed.topic),
                )
            }
        return out.filter { it.depth <= gateDepth }
    }

    /** 话题指纹：去首尾与内部空白（中文话题无大小写差异，保持最小规范化）。 */
    fun fingerprintOf(topic: String): String = topic.trim().replace(whitespace, "")

    /**
     * 站码 → 深度档（1..3）：按场景包站点列表近似均分三档；未知站/无包一律
     * 按最保守的浅层处理（弱联系只碰浅层话题，方案 §7.2 阶段门控）。
     */
    fun stageDepthOf(stageCodes: List<String>, stageCode: String?): Int {
        if (stageCode.isNullOrBlank() || stageCodes.isEmpty()) return DEPTH_SHALLOW
        val idx = stageCodes.indexOf(stageCode)
        if (idx < 0) return DEPTH_SHALLOW
        return DEPTH_SHALLOW + (idx * 3 / stageCodes.size).coerceIn(0, DEPTH_DEEP - DEPTH_SHALLOW)
    }

    /**
     * 保温任务令编码：`warm_chat|<话题>|<模型任务令>`。前缀是回放与 Judge 豁免
     * 的识别标记（D4）；话题内的 `|` 归一为全角，防止解码错位。话题为空 =
     * 选题耗尽的自由轻聊回合（无指纹可去重）。
     */
    fun encodeWarmChatMission(topic: String, mission: String): String =
        WARM_CHAT_MISSION_PREFIX + topic.trim().replace('|', '／') + "|" + mission.trim()

    /** 从任务令文本解码保温话题指纹源；非保温任务令或自由轻聊回合返回 null。 */
    fun decodeWarmChatTopic(mission: String): String? =
        mission.takeIf { isWarmChatMission(it) }
            ?.removePrefix(WARM_CHAT_MISSION_PREFIX)
            ?.substringBefore('|')
            ?.takeIf { it.isNotBlank() }

    fun isWarmChatMission(mission: String): Boolean = mission.startsWith(WARM_CHAT_MISSION_PREFIX)

    private val whitespace = Regex("\\s+")

    private val quotedTopicRegex = Regex("「(.{1,${MAX_TOPIC_CHARS * 2}}?)」")

    /**
     * 从保温回合的提问文本提取「话题」：共享会话（无联系人）没有任务令台账，
     * 调用方以消息文本近似指纹去重，避免手动连点重复同一题。
     */
    fun topicOfQuestion(question: String): String? =
        quotedTopicRegex.find(question)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
