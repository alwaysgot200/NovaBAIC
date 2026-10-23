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

package com.verlintas.baic2.floatball.advisor

import com.verlintas.baic2.floatball.advisor.data.ENGAGEMENT_MUTE
import com.verlintas.baic2.floatball.advisor.data.ENGAGEMENT_PROACTIVE
import com.verlintas.baic2.floatball.advisor.data.ENGAGEMENT_REACTIVE

/**
 * 双方人设 facet 的纯函数面（v10，UltraAdvisor v2.2 方案 §2.4/§2.5）：
 * 标签 JSON 数组的编解码复用 [PersonaValues]，合并差异计算独立成纯对象，
 * JVM 可直接单测（mergeContacts 的 DB 编排不进单测）。
 */
object SelfFacets {

    /**
     * 「我的人设」补丁（2026-10-23 方案 §3.1，继承+补丁）：默认继承全局人设标签库，
     * [extra] = 此关系专属补充标签，[masked] = 此关系里屏蔽的全局标签；
     * 生效人设 = (全局库 − masked) ∪ extra，由 [resolve] 计算。
     */
    @kotlinx.serialization.Serializable
    data class FacetPatch(
        val extra: List<String> = emptyList(),
        val masked: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = extra.isEmpty() && masked.isEmpty()
    }

    /** 标签集 → JSON 数组（去重保序；空集返回 null，落库保持列空语义）。 */
    fun encode(tags: List<String>): String? {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (clean.isEmpty()) null else PersonaValues.encodeTags(clean)
    }

    /** JSON 数组 → 标签集；null/畸形输入按空集处理（不抛，防御旧数据）。 */
    fun decode(json: String?): List<String> =
        json?.let { PersonaValues.decodeTags(it).filter { tag -> tag.isNotBlank() } } ?: emptyList()

    /** 补丁 → 列编码（全空返回 null = 纯继承全局，清列语义）。 */
    fun encodePatch(patch: FacetPatch): String? {
        val clean = FacetPatch(
            extra = patch.extra.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            masked = patch.masked.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
        return if (clean.isEmpty) null else pendingJson.encodeToString(FacetPatch.serializer(), clean)
    }

    /**
     * 补丁列解码（三形态防御，绝不抛）：`{…}` = [FacetPatch] 对象（继承+补丁起）；
     * `[…]` = 旧快照数组（建档复制时代）→ 一个不丢收进 extra；null/畸形 = 空补丁
     * （纯继承全局）。
     */
    fun decodePatch(json: String?): FacetPatch {
        val text = json?.trim().takeUnless { it.isNullOrEmpty() } ?: return FacetPatch()
        return when (text.firstOrNull()) {
            '{' -> runCatching { pendingJson.decodeFromString(FacetPatch.serializer(), text) }
                .getOrElse { FacetPatch() }
                .let { FacetPatch(extra = it.extra.filter(::valid), masked = it.masked.filter(::valid)) }
            else -> FacetPatch(extra = decode(text))
        }
    }

    /**
     * 生效人设（2026-10-23 方案 §3.1）：(全局库 − masked) ∪ extra——全局序在前、
     * 专属补充在后，去重保序。全局标签由调用方从 self_personas 注入，本函数纯计算。
     */
    fun resolve(patchJson: String?, globalTags: List<String>): List<String> {
        val patch = decodePatch(patchJson)
        val masked = patch.masked.toSet()
        return (globalTags.filter { it !in masked } + patch.extra).distinct()
    }

    private fun valid(tag: String): Boolean = tag.isNotBlank()

    /**
     * 合并协议第 2 步（方案 §2.5）：主档案补丁为准（extra/masked 都不覆写）；
     * 被并档案的专属差异标签（other.extra 有而 main.extra 没有）连同主档案原有
     * pending 并入待确认区，不静默丢弃（masked 属否定语义，合并以主档案为准）。
     * 返回 (主档案补丁编码, 新 pending JSON)；pending 为空返回 null 清列。
     * pending 输出统一为 [StylePending] 对象形态（V-C 通用待确认通道），主档案
     * pending 里的 style 待定值原样保留。
     */
    fun mergePending(mainJson: String?, otherJson: String?, mainPendingJson: String?): Pair<String?, String?> {
        val old = decodePending(mainPendingJson) ?: StylePending()
        val main = decodePatch(mainJson)
        val other = decodePatch(otherJson)
        val pending = StylePending(
            facet = (old.facet + (other.extra - main.extra.toSet())).distinct(),
            identity = old.identity,
            chatStyle = old.chatStyle,
            closeness = old.closeness,
        )
        return encodePatch(main) to encodePending(pending)
    }

    /**
     * 通用待确认通道（V-C）：style 三标签 + 我方 facet 的合并暂存体。
     * 落 [ContactRepository.proposeStylePending] 的 pending 与人工核阅出口；
     * 全空 = 无待确认内容。
     */
    @kotlinx.serialization.Serializable
    data class StylePending(
        val facet: List<String> = emptyList(),
        val identity: String? = null,
        val chatStyle: String? = null,
        val closeness: Int? = null,
    ) {
        val isEmpty: Boolean
            get() = facet.isEmpty() && identity.isNullOrBlank() &&
                chatStyle.isNullOrBlank() && closeness == null
    }

    /**
     * 解码待确认列（向后兼容三种历史形态）：`{…}` = [StylePending] 对象
     * （V-C 起的通用通道）；`[…]` = 旧 facet 数组（VA-2 合并协议产物）；null/
     * 畸形 = 空。绝不抛出（模型/迁移数据不可信）。
     */
    fun decodePending(json: String?): StylePending? {
        val text = json?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return when (text.firstOrNull()) {
            '{' -> runCatching { pendingJson.decodeFromString(StylePending.serializer(), text) }
                .getOrElse { StylePending(facet = decode(text)) }
                .takeUnless { it.isEmpty }
            // 旧 facet 数组（VA-2）与一切畸形输入：按防御口径走 decode。
            else -> decode(text).takeIf { it.isNotEmpty() }?.let { StylePending(facet = it) }
        }
    }

    /** 编码待确认列：全空返回 null（清列语义），否则输出对象形态 JSON。 */
    fun encodePending(pending: StylePending): String? =
        if (pending.isEmpty) null else pendingJson.encodeToString(StylePending.serializer(), pending)

    /**
     * 待确认全量转正（V-C 核阅出口）：提案标签并入补丁 **extra**（每关系专属，
     * 2026-10-23 §3.2）并解除同名屏蔽；style 三值仅在对应列**为空**时给出新值
     * （手动设置绝不静默覆盖），调用方对 null 保持原列。
     */
    fun promoteAll(
        facetJson: String?,
        pendingJson: String?,
    ): StylePromotion {
        val pending = decodePending(pendingJson) ?: StylePending()
        val patch = decodePatch(facetJson)
        val add = pending.facet.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return StylePromotion(
            facetJson = encodePatch(
                FacetPatch(
                    extra = (patch.extra + add).distinct(),
                    masked = patch.masked - add.toSet(),
                ),
            ),
            identity = pending.identity?.takeIf { it.isNotBlank() },
            chatStyle = pending.chatStyle?.takeIf { it.isNotBlank() },
            closeness = pending.closeness,
        )
    }

    /** [promoteAll] 的产物：null = 该维度无待定值，调用方保持原列。 */
    data class StylePromotion(
        val facetJson: String?,
        val identity: String?,
        val chatStyle: String?,
        val closeness: Int?,
    )

    private val pendingJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}

/**
 * 维持度三档的判定纯函数（v10，方案 §2.6）：通知链与 PROACTIVE 检点共用同一裁决，
 * 未知/空值按 reactive 处理（默认「问了才答」不臆测）。JVM 可直接单测。
 */
object EngagementRules {

    /** 来信是否允许进参谋链：mute 静默忽略，reactive/proactive 照常。 */
    fun incomingAllowed(engagement: String?): Boolean =
        normalize(engagement) != ENGAGEMENT_MUTE

    /** PROACTIVE 检点是否对该联系人执行：只有 proactive 档参与主动维持。 */
    fun proactiveEligible(engagement: String?): Boolean =
        normalize(engagement) == ENGAGEMENT_PROACTIVE

    /** 非法/空档位一律归一为 reactive（迁移行默认值与运行时兜底同口径）。 */
    fun normalize(engagement: String?): String = when (engagement) {
        ENGAGEMENT_PROACTIVE, ENGAGEMENT_REACTIVE, ENGAGEMENT_MUTE -> engagement
        else -> ENGAGEMENT_REACTIVE
    }
}
