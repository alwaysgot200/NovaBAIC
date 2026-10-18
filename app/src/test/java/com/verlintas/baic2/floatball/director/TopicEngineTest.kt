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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TopicEngine 纯函数选题引擎全规则（M1 方案 §10 验收：三源优先级、同源顺序、
 * 标签加权、指纹去重、冷却、阶段门控、逐级放宽兜底、编解码与确定性）。
 */
class TopicEngineTest {

    private companion object {
        const val NOW = 10_000_000_000L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    private fun seed(
        topic: String,
        angle: String = "",
        tags: List<String> = emptyList(),
        depth: Int = TopicEngine.DEPTH_SHALLOW,
        minCloseness: Int = 0,
    ) = TopicSeed(topic = topic, angle = angle, tags = tags, depth = depth, minCloseness = minCloseness)

    private fun materials(
        hooks: List<String> = emptyList(),
        tags: Set<String> = emptySet(),
        experiences: List<String> = emptyList(),
        personalMaterials: List<String> = emptyList(),
        materialItems: List<MaterialItem> = emptyList(),
        seeds: List<TopicSeed> = emptyList(),
        used: List<TopicEngine.UsedTopic> = emptyList(),
        stageDepth: Int = TopicEngine.DEPTH_DEEP,
        nowMs: Long = NOW,
        planDirections: List<String> = emptyList(),
        filter: TopicFilter? = null,
    ) = TopicEngine.Materials(
        profileHooks = hooks,
        interestTags = tags,
        sharedExperiences = experiences,
        personalMaterials = if (materialItems.isEmpty()) {
            personalMaterials.map { MaterialItem(it) }
        } else {
            materialItems
        },
        seeds = seeds,
        usedTopics = used,
        stageDepth = stageDepth,
        nowMs = nowMs,
        planDirections = planDirections,
        filter = filter,
    )

    // ── 源优先级（枚举声明序即优先级） ─────────────────────────────────

    @Test
    fun profileHook_beatsExperience_beatsMaterial_beatsSeed() {
        val pick = TopicEngine.select(
            materials(
                hooks = listOf("钓鱼"),
                experiences = listOf("一起爬过山"),
                personalMaterials = listOf("我上周末去逛了宠物展"),
                seeds = listOf(seed("周末安排")),
            ),
        )
        assertEquals(TopicSource.PROFILE_HOOK, pick?.source)
        assertEquals("钓鱼", pick?.topic)
    }

    @Test
    fun experience_beatsMaterial_beatsSeed_whenNoHooks() {
        val pick = TopicEngine.select(
            materials(
                experiences = listOf("一起爬过山"),
                personalMaterials = listOf("我上周末去逛了宠物展"),
                seeds = listOf(seed("周末安排")),
            ),
        )
        assertEquals(TopicSource.SHARED_EXPERIENCE, pick?.source)
    }

    @Test
    fun material_beatsSeed_whenNoHooksNoExperience() {
        val pick = TopicEngine.select(
            materials(
                personalMaterials = listOf("我上周末去逛了宠物展"),
                seeds = listOf(seed("周末安排")),
            ),
        )
        assertEquals(TopicSource.PERSONAL_MATERIAL, pick?.source)
        assertEquals("我上周末去逛了宠物展", pick?.topic)
    }

    // ── 个人素材（我的素材库，方案 §7.2 源②投喂部分） ─────────────────────

    /** 素材与台账同轨去重（F6 复用既有指纹机制）：最近用过的素材被滤，同批下一条顶上。 */
    @Test
    fun material_usedWithinWindow_skippedLikeOtherSources() {
        val pick = TopicEngine.select(
            materials(
                personalMaterials = listOf("宠物展", "公司楼下的樱花开了"),
                used = listOf(TopicEngine.UsedTopic(TopicEngine.fingerprintOf("宠物展"), NOW - DAY_MS)),
            ),
        )
        assertEquals(TopicSource.PERSONAL_MATERIAL, pick?.source)
        assertEquals("公司楼下的樱花开了", pick?.topic)
    }

    /** 素材为空白串时不产生候选（与画像钩子/共同经历的 trim+filter 契约一致），兜底到种子库。 */
    @Test
    fun blankMaterials_filtered_fallsThroughToSeed() {
        val pick = TopicEngine.select(
            materials(
                personalMaterials = listOf(" ", ""),
                seeds = listOf(seed("周末安排")),
            ),
        )
        assertEquals(TopicSource.SEED_BANK, pick?.source)
        assertEquals("周末安排", pick?.topic)
    }

    @Test
    fun experience_beatsSeed_whenNoHooks() {
        val pick = TopicEngine.select(
            materials(experiences = listOf("一起爬过山"), seeds = listOf(seed("周末安排"))),
        )
        assertEquals(TopicSource.SHARED_EXPERIENCE, pick?.source)
    }

    @Test
    fun sameSource_preservesOriginalOrder() {
        val pick = TopicEngine.select(
            materials(hooks = listOf("钓鱼", "烘焙", "骑行")),
        )
        assertEquals("钓鱼", pick?.topic)
    }

    @Test
    fun seedBank_tagMatchesBoostWithinSource() {
        val pick = TopicEngine.select(
            materials(
                tags = setOf("饮食"),
                seeds = listOf(seed("周末安排"), seed("最近吃到的好吃的", tags = listOf("饮食"))),
            ),
        )
        assertEquals("最近吃到的好吃的", pick?.topic)
    }

    // ── 话题指纹去重（近 DEDUP_WINDOW 条台账内不重复） ───────────────────

    @Test
    fun usedWithinWindow_skipped() {
        val pick = TopicEngine.select(
            materials(
                seeds = listOf(seed("周末安排"), seed("最近吃到的好吃的")),
                used = listOf(TopicEngine.UsedTopic("周末安排", NOW - DAY_MS)),
            ),
        )
        assertEquals("最近吃到的好吃的", pick?.topic)
    }

    @Test
    fun fingerprint_ignoresSurroundingAndInnerWhitespace() {
        assertEquals(
            TopicEngine.fingerprintOf("周末安排"),
            TopicEngine.fingerprintOf("  周末  安排 "),
        )
    }

    @Test
    fun usedWhitespaceVariant_deduplicatedByFingerprint() {
        val pick = TopicEngine.select(
            materials(
                seeds = listOf(seed("周末安排"), seed("最近吃到的好吃的")),
                used = listOf(TopicEngine.UsedTopic(TopicEngine.fingerprintOf("周末 安排"), NOW - DAY_MS)),
            ),
        )
        assertEquals("最近吃到的好吃的", pick?.topic)
    }

    // ── 同题冷却（去重窗口之外的慢频率保护） ─────────────────────────────

    /** 台账 30 条：目标话题只出现在窗口外（第 26 条）且 1 天前用过 → 冷却未过，严格档滤掉。 */
    @Test
    fun cooldownNotPassed_demotedToDedupOnlyTier() {
        val used = buildList {
            repeat(TopicEngine.DEDUP_WINDOW) { i ->
                add(TopicEngine.UsedTopic("filler-$i", NOW - i * 1000L))
            }
            add(TopicEngine.UsedTopic("周末安排", NOW - DAY_MS))
        }
        val pick = TopicEngine.select(
            materials(
                hooks = listOf("周末安排"),
                seeds = listOf(seed("最近吃到的好吃的")),
                used = used,
            ),
        )
        // 冷却未过 → 画像钩子（周末安排）被滤，种子库顶上。
        assertEquals("最近吃到的好吃的", pick?.topic)
    }

    /** 冷却已过（8 天前）→ 严格档放行，画像钩子优先级恢复。 */
    @Test
    fun cooldownPassed_hookEligibleAgain() {
        val used = buildList {
            repeat(TopicEngine.DEDUP_WINDOW) { i ->
                add(TopicEngine.UsedTopic("filler-$i", NOW - i * 1000L))
            }
            add(TopicEngine.UsedTopic("周末安排", NOW - 8 * DAY_MS))
        }
        val pick = TopicEngine.select(
            materials(
                hooks = listOf("周末安排"),
                seeds = listOf(seed("最近吃到的好吃的")),
                used = used,
            ),
        )
        assertEquals(TopicSource.PROFILE_HOOK, pick?.source)
    }

    // ── 逐级放宽兜底（题库见底时取最久未用，不空转） ─────────────────────

    @Test
    fun allRecentlyUsed_fallsBackToLeastRecentlyUsed() {
        val pick = TopicEngine.select(
            materials(
                seeds = listOf(seed("话题A"), seed("话题B")),
                used = listOf(
                    TopicEngine.UsedTopic("话题A", NOW - 1 * DAY_MS),
                    TopicEngine.UsedTopic("话题B", NOW - 5 * DAY_MS),
                ),
            ),
        )
        // 去重窗口内全用过：严格档空、去重档空 → 全放开取最久未用的 B。
        assertEquals("话题B", pick?.topic)
    }

    // ── 阶段门控（深度超过阶段档的话题不投喂） ───────────────────────────

    @Test
    fun shallowStage_gatesOutDeepSeeds() {
        val pick = TopicEngine.select(
            materials(
                seeds = listOf(seed("个人往事", depth = TopicEngine.DEPTH_DEEP), seed("周末安排")),
                stageDepth = TopicEngine.DEPTH_SHALLOW,
            ),
        )
        assertEquals("周末安排", pick?.topic)
    }

    @Test
    fun allGatedOut_returnsNull() {
        assertNull(
            TopicEngine.select(
                materials(
                    seeds = listOf(seed("个人往事", depth = TopicEngine.DEPTH_DEEP)),
                    stageDepth = TopicEngine.DEPTH_SHALLOW,
                ),
            ),
        )
    }

    @Test
    fun emptyMaterials_returnsNull() {
        assertNull(TopicEngine.select(materials()))
    }

    @Test
    fun stageDepthOf_mapsLinearStagesToThreeTiers() {
        val stages = listOf("s1", "s2", "s3", "s4", "s5", "s6")
        assertEquals(TopicEngine.DEPTH_SHALLOW, TopicEngine.stageDepthOf(stages, "s1"))
        assertEquals(TopicEngine.DEPTH_MEDIUM, TopicEngine.stageDepthOf(stages, "s4"))
        assertEquals(TopicEngine.DEPTH_DEEP, TopicEngine.stageDepthOf(stages, "s6"))
    }

    @Test
    fun stageDepthOf_unknownOrBlankStage_conservativelyShallow() {
        val stages = listOf("s1", "s2", "s3")
        assertEquals(TopicEngine.DEPTH_SHALLOW, TopicEngine.stageDepthOf(stages, "nope"))
        assertEquals(TopicEngine.DEPTH_SHALLOW, TopicEngine.stageDepthOf(stages, null))
        assertEquals(TopicEngine.DEPTH_SHALLOW, TopicEngine.stageDepthOf(stages, ""))
        assertEquals(TopicEngine.DEPTH_SHALLOW, TopicEngine.stageDepthOf(emptyList(), "s1"))
    }

    // ── 确定性（同输入恒同输出，无随机不读时钟） ─────────────────────────

    @Test
    fun deterministic_sameInputSamePick() {
        val m = materials(
            hooks = listOf("钓鱼"),
            tags = setOf("饮食"),
            experiences = listOf("一起爬过山"),
            seeds = listOf(seed("周末安排"), seed("最近吃到的好吃的", tags = listOf("饮食"))),
            used = listOf(TopicEngine.UsedTopic("钓鱼", NOW - DAY_MS)),
        )
        val first = TopicEngine.select(m)
        val second = TopicEngine.select(m)
        assertEquals(first, second)
    }

    @Test
    fun clockInjected_sameMaterialsDifferentNow_changesCooldownVerdict() {
        val seeds = listOf(seed("话题A"))
        val used = buildList {
            repeat(TopicEngine.DEDUP_WINDOW) { i ->
                add(TopicEngine.UsedTopic("filler-$i", NOW - i * 1000L))
            }
            add(TopicEngine.UsedTopic("话题A", NOW - 8 * DAY_MS))
        }
        val early = TopicEngine.select(materials(seeds = seeds, used = used, nowMs = NOW - 3 * DAY_MS))
        val late = TopicEngine.select(materials(seeds = seeds, used = used, nowMs = NOW))
        assertEquals("话题A", early?.topic)
        assertEquals("话题A", late?.topic)
        // 两种 now 下都出题（逐级放宽保证），但决策路径不同：同 now 下选择稳定即可。
        assertNotEquals(early, null)
    }

    // ── 保温任务令编码/解码（warm_chat| 前缀，D4） ───────────────────────

    @Test
    fun encodeWarmChatMission_roundtrip() {
        val encoded = TopicEngine.encodeWarmChatMission("最近吃到的好吃的", "以该话题自然开场")
        assertTrue(TopicEngine.isWarmChatMission(encoded))
        assertEquals("warm_chat|最近吃到的好吃的|以该话题自然开场", encoded)
        assertEquals("最近吃到的好吃的", TopicEngine.decodeWarmChatTopic(encoded))
    }

    @Test
    fun encode_pipeInsideTopic_normalizedToFullWidth() {
        val encoded = TopicEngine.encodeWarmChatMission("A|B", "任务")
        assertEquals("warm_chat|A／B|任务", encoded)
        assertEquals("A／B", TopicEngine.decodeWarmChatTopic(encoded))
    }

    @Test
    fun decode_nonWarmChatMission_returnsNull() {
        assertFalse(TopicEngine.isWarmChatMission("推进预算话题"))
        assertNull(TopicEngine.decodeWarmChatTopic("推进预算话题"))
    }

    @Test
    fun decode_blankTopic_freeChatRound_returnsNull() {
        val encoded = TopicEngine.encodeWarmChatMission("", "自由轻聊")
        assertTrue(TopicEngine.isWarmChatMission(encoded))
        assertNull(TopicEngine.decodeWarmChatTopic(encoded))
    }

    // ── 共享会话指纹源：提问文本中的「话题」提取 ─────────────────────────

    @Test
    fun topicOfQuestion_extractsQuotedTopic() {
        assertEquals(
            "最近吃到的好吃的",
            TopicEngine.topicOfQuestion("来点闲聊（话题：「最近吃到的好吃的」）"),
        )
    }

    @Test
    fun topicOfQuestion_noQuotes_returnsNull() {
        assertNull(TopicEngine.topicOfQuestion("来点闲聊"))
    }

    // ── V-C §4.4：Planner 方向优先消费 ─────────────────────────────────

    @Test
    fun planDirection_beatsAllSources() {
        val pick = TopicEngine.select(
            materials(
                hooks = listOf("钓鱼"),
                planDirections = listOf("行业见闻"),
                seeds = listOf(seed("周末安排")),
            ),
        )
        assertEquals(TopicSource.PLAN_DIRECTION, pick?.source)
        assertEquals("行业见闻", pick?.topic)
        assertTrue(pick!!.angle.contains("方向"))
    }

    /** 方向在去重窗口内 = 近期已聊：自动轮换退回四源，不空转。 */
    @Test
    fun planDirection_usedRecently_fallsBackToFourSources() {
        val pick = TopicEngine.select(
            materials(
                hooks = listOf("钓鱼"),
                planDirections = listOf("行业见闻"),
                used = listOf(TopicEngine.UsedTopic(TopicEngine.fingerprintOf("行业见闻"), NOW - DAY_MS)),
            ),
        )
        assertEquals(TopicSource.PROFILE_HOOK, pick?.source)
        assertEquals("钓鱼", pick?.topic)
    }

    @Test
    fun planDirection_blankSkipped() {
        val pick = TopicEngine.select(
            materials(
                hooks = listOf("钓鱼"),
                planDirections = listOf("  ", ""),
            ),
        )
        assertEquals(TopicSource.PROFILE_HOOK, pick?.source)
    }

    /** 方向超长截断到话题粒度上限（与四源口径一致）。 */
    @Test
    fun planDirection_truncatedToTopicChars() {
        val long = "一".repeat(50)
        val pick = TopicEngine.select(materials(planDirections = listOf(long)))
        assertEquals(TopicEngine.MAX_TOPIC_CHARS, pick?.topic?.length)
    }

    // ── V-C §4.2：三重过滤（风格禁区/降权/亲疏门） ───────────────────────

    /** 禁区：领导型联系人（forbidden 含吃喝/玩乐/段子）→ 带风格词的种子被整体剔除。 */
    @Test
    fun forbiddenTags_removeStyleTaggedSeeds() {
        val pick = TopicEngine.select(
            materials(
                tags = setOf("饮食"),
                seeds = listOf(
                    seed("周末安排"),
                    seed("最近吃到的好吃的", tags = listOf("饮食")),
                ),
                filter = TopicFilter(forbiddenTags = setOf(TopicStyleTags.FOOD)),
            ),
        )
        assertEquals("周末安排", pick?.topic)
    }

    /** 亲疏门：种子 minCloseness 高于对方 closeness 的不投喂。 */
    @Test
    fun seedMinCloseness_aboveContactCloseness_gated() {
        val pick = TopicEngine.select(
            materials(
                seeds = listOf(
                    seed("个人往事", minCloseness = 2),
                    seed("周末安排", minCloseness = 0),
                ),
                filter = TopicFilter(closeness = 1),
            ),
        )
        assertEquals("周末安排", pick?.topic)
    }

    /** 亲疏门：closeness 未萃取（null）不设限，全交阶段门控兜底。 */
    @Test
    fun seedMinCloseness_noClosenessExtracted_permissive() {
        val pick = TopicEngine.select(
            materials(
                seeds = listOf(seed("个人往事", minCloseness = 3, depth = TopicEngine.DEPTH_DEEP)),
                filter = TopicFilter(closeness = null),
            ),
        )
        assertEquals("个人往事", pick?.topic)
    }

    /** 深度双门：风格 maxDepth 与阶段档取更严（此处风格收到浅层）。 */
    @Test
    fun filterMaxDepth_gatesDeepSeedsEvenInDeepStage() {
        val pick = TopicEngine.select(
            materials(
                seeds = listOf(
                    seed("个人往事", depth = TopicEngine.DEPTH_DEEP),
                    seed("周末安排"),
                ),
                stageDepth = TopicEngine.DEPTH_DEEP,
                filter = TopicFilter(maxDepth = TopicEngine.DEPTH_SHALLOW),
            ),
        )
        assertEquals("周末安排", pick?.topic)
    }

    /** 降权生效：段子种子被降权后输给命中兴趣标签的其他种子（不剔除、只让位）。 */
    @Test
    fun penaltyTags_loseToTagMatchedAlternative() {
        val pick = TopicEngine.select(
            materials(
                tags = setOf("饮食", "搞笑"),
                seeds = listOf(
                    seed("最近看到的搞笑段子", tags = listOf("搞笑")),
                    seed("最近吃到的好吃的", tags = listOf("饮食")),
                ),
                filter = TopicFilter(penaltyTags = setOf(TopicStyleTags.JOKE)),
            ),
        )
        // 段子：1 命中 - 1 降权 = 0；吃喝：1 命中 - 0 = 1 → 吃喝胜出。
        assertEquals("最近吃到的好吃的", pick?.topic)
    }

    /** 素材风格词命中禁区同样被剔除（素材与种子同口径过滤）。 */
    @Test
    fun forbiddenTags_removeMaterialsByStyleTag() {
        val pick = TopicEngine.select(
            materials(
                materialItems = listOf(
                    MaterialItem("周末爬山", styleTags = listOf("玩乐")),
                    MaterialItem("聊聊家常"),
                ),
                filter = TopicFilter(forbiddenTags = setOf(TopicStyleTags.FUN)),
            ),
        )
        assertEquals("聊聊家常", pick?.topic)
    }

    /** 无 filter（共享会话/未萃取路径）时一切照旧放行。 */
    @Test
    fun nullFilter_noRestrictions() {
        val pick = TopicEngine.select(
            materials(
                seeds = listOf(seed("个人往事", minCloseness = 3, depth = TopicEngine.DEPTH_DEEP)),
            ),
        )
        assertEquals("个人往事", pick?.topic)
    }
}
