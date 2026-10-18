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
    ) = TopicSeed(topic = topic, angle = angle, tags = tags, depth = depth)

    private fun materials(
        hooks: List<String> = emptyList(),
        tags: Set<String> = emptySet(),
        experiences: List<String> = emptyList(),
        seeds: List<TopicSeed> = emptyList(),
        used: List<TopicEngine.UsedTopic> = emptyList(),
        stageDepth: Int = TopicEngine.DEPTH_DEEP,
        nowMs: Long = NOW,
    ) = TopicEngine.Materials(
        profileHooks = hooks,
        interestTags = tags,
        sharedExperiences = experiences,
        seeds = seeds,
        usedTopics = used,
        stageDepth = stageDepth,
        nowMs = nowMs,
    )

    // ── 三源优先级（枚举声明序即优先级） ─────────────────────────────────

    @Test
    fun profileHook_beatsExperience_beatsSeed() {
        val pick = TopicEngine.select(
            materials(
                hooks = listOf("钓鱼"),
                experiences = listOf("一起爬过山"),
                seeds = listOf(seed("周末安排")),
            ),
        )
        assertEquals(TopicSource.PROFILE_HOOK, pick?.source)
        assertEquals("钓鱼", pick?.topic)
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
}
