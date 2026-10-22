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

import android.content.Context
import com.verlintas.baic2.core.data.repository.MemoryRepository
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.advisor.GoalRepository
import com.verlintas.baic2.floatball.advisor.PersonaFieldType
import com.verlintas.baic2.floatball.advisor.PersonaSchemas
import com.verlintas.baic2.floatball.advisor.PersonaValues
import com.verlintas.baic2.floatball.advisor.SelfFacets
import com.verlintas.baic2.floatball.advisor.StrategyBrief
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.PersonalMaterialEntity
import com.verlintas.baic2.floatball.advisor.emotion.EmotionAssessor
import com.verlintas.baic2.floatball.contact.ContactRef
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * [TopicEngine] 的素材源采集器（M1 方案 §7.2，Android/IO 侧）：种子库资产 +
 * 对方画像钩子（enum topicHook 选项与 tags 自由标签，口径同 CardCompiler）+
 * 共同经历（recallByEntity 交往脉络事实）+ 我的素材库（personal_materials
 * 用户投喂，F6）+ 已用话题指纹（talk_missions 台账解码 + 素材 lastUsedAt 并轨）。
 * 选题本身委托纯函数引擎；任一取数失败静默降级（缺哪源少哪源），
 * 绝不让保温回合开天窗。
 */
@Singleton
class TopicEngineStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AdvisorDatabase,
    private val contacts: ContactRepository,
    private val schemas: PersonaSchemas,
    private val goals: GoalRepository,
    private val strategyBrief: StrategyBrief,
    private val memoryRepository: MemoryRepository,
    private val emotionAssessor: EmotionAssessor,
) {

    @Volatile
    private var cachedBank: TopicSeedBank? = null

    /**
     * 采集 + 选题一步完成。[contact] 为 null（共享会话）时只剩种子库与我的
     * 素材库兜底（两者都不依赖联系人），已用指纹改由调用方从共享会话消息
     * 提取（[sharedUsedTopics]）。
     */
    suspend fun selectFor(
        contact: ContactRef?,
        sharedUsedTopics: List<TopicEngine.UsedTopic> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): TopicEngine.TopicPick? {
        val seeds = seedBank().seeds
        val materials = runCatching { db.personalMaterialDao().all() }.getOrDefault(emptyList())
        if (contact == null) {
            val pick = TopicEngine.select(
                TopicEngine.Materials(
                    personalMaterials = materials.toItems(),
                    seeds = seeds,
                    usedTopics = sharedUsedTopics + materials.usedFingerprints(),
                    stageDepth = TopicEngine.DEPTH_SHALLOW,
                    nowMs = nowMs,
                ),
            )
            markMaterialUsed(pick, nowMs)
            return pick
        }

        val pack = runCatching { strategyBrief.pack(contact.contactId) }.getOrNull()
        val stageCode = pack
            ?.let { p -> runCatching { goals.stage(contact.contactId, p.code) }.getOrNull()?.stageCode }

        // 画像钩子：enum 的 topicHook 选项 + tags 自由标签（后者兼作种子匹配的
        // 兴趣标签），只认已确认字段，口径与 CardCompiler.topicHooks 一致。
        val fields = runCatching { contacts.fields(contact.contactId).filter { it.confirmed } }
            .getOrDefault(emptyList())
        val defs = schemas.get().fields.associateBy { it.key }
        val hooks = ArrayList<String>()
        val interestTags = LinkedHashSet<String>()
        fields.forEach { entity ->
            val def = defs[entity.field] ?: return@forEach
            if (def.owner != OWNER_THEIR) return@forEach
            when (def.type) {
                PersonaFieldType.ENUM -> {
                    val option = def.optionByCode(entity.value.trim()) ?: def.optionByLabel(entity.value.trim())
                    if (option?.topicHook == true) hooks.add(option.label)
                }
                PersonaFieldType.TAGS -> {
                    PersonaValues.decodeTags(entity.value)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { tag ->
                            hooks.add(tag)
                            interestTags.add(tag)
                        }
                }
            }
        }

        // 风格三重过滤（V-C §4.2）：对方 identity/chatStyle/closeness + 我方 facet +
        // heatScore 推导禁区/降权/深度门；画像缺失时 filterFor 各维 null 不设限。
        val profile = runCatching { contacts.profile(contact.contactId) }.getOrNull()
        val filter = profile?.let {
            TopicStyleRules.filterFor(
                identity = it.identity,
                chatStyle = it.chatStyle,
                closeness = it.closeness,
                facetTags = SelfFacets.decode(it.selfFacetJson),
                heatScore = it.heatScore,
            )
        }

        // 共同经历：按主名+别名只读召回该联系人的交往事实（不 touch、不强化）。
        val names = (listOf(contact.displayName) + contact.aliases)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val sharedExperiences = if (names.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                memoryRepository.recallByEntity(entities = names, limit = EXPERIENCE_NOTES, feedback = false)
            }.getOrDefault(emptyList())
                .map { it.note.content.trim() }
                .filter { it.isNotEmpty() }
        }

        // 已用话题指纹：近 USED_MISSIONS 条任务令中 warm_chat 标记解码（新→旧）；
        // 素材 lastUsedAt 并轨（F6）：复用引擎既有的去重/冷却机制防止短期重聊
        // 同一素材——台账指纹在前（更新），素材指纹在后，同指纹时引擎取更近时间。
        val usedTopics = runCatching { goals.recentMissions(contact.contactId, USED_MISSIONS) }
            .getOrDefault(emptyList())
            .mapNotNull { mission ->
                TopicEngine.decodeWarmChatTopic(mission.mission)
                    ?.let { TopicEngine.UsedTopic(TopicEngine.fingerprintOf(it), mission.createdAt) }
            }

        // Planner 周方向（V-C §4.4）：latest 一条的方向列表交给引擎优先消费；
        // 方向在窗口内视为已聊，引擎自动落到四源。
        val planDirections = runCatching { db.topicPlanDao().latest(contact.contactId) }
            .getOrNull()
            ?.let { plan -> TopicPlanner.decodeDirections(plan.directionsJson) }
            .orEmpty()

        val pick = TopicEngine.select(
            TopicEngine.Materials(
                profileHooks = hooks,
                interestTags = interestTags,
                sharedExperiences = sharedExperiences,
                personalMaterials = materials.toItems(),
                seeds = seeds,
                usedTopics = usedTopics + materials.usedFingerprints(),
                stageDepth = TopicEngine.stageDepthOf(pack?.stages?.map { it.code }.orEmpty(), stageCode),
                nowMs = nowMs,
                planDirections = planDirections,
                filter = filter,
                // E-g §4.7：情绪跟进话题（低落登记到期的"最近好点没"），
                // emotion_events 即登记；无到期/无数据为 null，不影响其余源。
                emotionFollowUp = runCatching {
                    emotionAssessor.trend(contact.contactId, nowMs)?.followUpTopic
                }.getOrNull(),
            ),
        )
        markMaterialUsed(pick, nowMs)
        return pick
    }

    /** 素材行 → 引擎条目：风格词由用户自由 tag 归一派生（TopicStyleTags 同源）。 */
    private fun List<PersonalMaterialEntity>.toItems(): List<MaterialItem> =
        map { MaterialItem(content = it.content, styleTags = listOfNotNull(it.tag)) }

    /** 素材已用指纹（新→旧，按 lastUsedAt 降序）：并轨进 [TopicEngine.Materials.usedTopics]。 */
    private fun List<PersonalMaterialEntity>.usedFingerprints(): List<TopicEngine.UsedTopic> =
        mapNotNull { m -> m.lastUsedAt?.let { TopicEngine.UsedTopic(m.fingerprint, it) } }
            .sortedByDescending { it.usedAtMs }

    /**
     * 选中素材即标记 lastUsedAt（F6 防短期重选）：选中的话题经既有 mission 令
     * 编码（warm_chat|话题|…）携带素材内容进 WarmChatPrompt 与台账，此处只
     * 落 lastUsedAt；旁路失败只 Log 级降级，不影响已产出的选题。
     */
    private suspend fun markMaterialUsed(pick: TopicEngine.TopicPick?, nowMs: Long) {
        if (pick?.source != TopicSource.PERSONAL_MATERIAL) return
        runCatching {
            db.personalMaterialDao().markUsedByFingerprint(TopicEngine.fingerprintOf(pick.topic), nowMs)
        }
    }

    /** 种子库资产（照 StyleAssets 范式：assets JSON + 进程内缓存，不入库）。 */
    private fun seedBank(): TopicSeedBank =
        cachedBank ?: runCatching {
            context.assets.open(SEEDS_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                json.decodeFromString(TopicSeedBank.serializer(), reader.readText())
            }
        }.getOrElse { TopicSeedBank() }.also { cachedBank = it }

    private companion object {
        const val SEEDS_ASSET = "advisor/topic_seeds.json"
        const val OWNER_THEIR = "their"

        /** 台账回看条数（方案 §7.2：读近 60 条任务令的话题标记）。 */
        const val USED_MISSIONS = 60

        /** 共同经历召回上限（交往脉络只取少量近事，防提示词膨胀）。 */
        const val EXPERIENCE_NOTES = 6

        // 本地 new Json 属包内既定惯例（AdvisorAssetSeeder 等同款多处先例）：
        // 无状态解析器不进 Hilt 图，不为它单引注入链。
        val json = Json { ignoreUnknownKeys = true }
    }
}
