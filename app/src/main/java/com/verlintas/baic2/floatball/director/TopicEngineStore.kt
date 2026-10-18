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
import com.verlintas.baic2.floatball.advisor.StrategyBrief
import com.verlintas.baic2.floatball.contact.ContactRef
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * [TopicEngine] 的素材三源采集器（M1 方案 §7.2，Android/IO 侧）：种子库资产 +
 * 对方画像钩子（enum topicHook 选项与 tags 自由标签，口径同 CardCompiler）+
 * 共同经历（recallByEntity 交往脉络事实）+ 已用话题指纹（talk_missions 台账
 * 解码）。选题本身委托纯函数引擎；任一取数失败静默降级（缺哪源少哪源），
 * 绝不让保温回合开天窗。
 */
@Singleton
class TopicEngineStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contacts: ContactRepository,
    private val schemas: PersonaSchemas,
    private val goals: GoalRepository,
    private val strategyBrief: StrategyBrief,
    private val memoryRepository: MemoryRepository,
) {

    @Volatile
    private var cachedBank: TopicSeedBank? = null

    /**
     * 采集 + 选题一步完成。[contact] 为 null（共享会话）时只剩种子库兜底，
     * 已用指纹改由调用方从共享会话消息提取（[sharedUsedTopics]）。
     */
    suspend fun selectFor(
        contact: ContactRef?,
        sharedUsedTopics: List<TopicEngine.UsedTopic> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): TopicEngine.TopicPick? {
        val seeds = seedBank().seeds
        if (contact == null) {
            return TopicEngine.select(
                TopicEngine.Materials(
                    seeds = seeds,
                    usedTopics = sharedUsedTopics,
                    stageDepth = TopicEngine.DEPTH_SHALLOW,
                    nowMs = nowMs,
                ),
            )
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

        // 已用话题指纹：近 USED_MISSIONS 条任务令中 warm_chat 标记解码（新→旧）。
        val usedTopics = runCatching { goals.recentMissions(contact.contactId, USED_MISSIONS) }
            .getOrDefault(emptyList())
            .mapNotNull { mission ->
                TopicEngine.decodeWarmChatTopic(mission.mission)
                    ?.let { TopicEngine.UsedTopic(TopicEngine.fingerprintOf(it), mission.createdAt) }
            }

        return TopicEngine.select(
            TopicEngine.Materials(
                profileHooks = hooks,
                interestTags = interestTags,
                sharedExperiences = sharedExperiences,
                seeds = seeds,
                usedTopics = usedTopics,
                stageDepth = TopicEngine.stageDepthOf(pack?.stages?.map { it.code }.orEmpty(), stageCode),
                nowMs = nowMs,
            ),
        )
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

        val json = Json { ignoreUnknownKeys = true }
    }
}
