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
 * A PARTICULAR PURPOSE. See the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.director

import com.verlintas.baic2.floatball.advisor.HeatRules
import com.verlintas.baic2.floatball.advisor.StyleVocab
import com.verlintas.baic2.floatball.advisor.data.ProactivePolicyEntity

/**
 * 主动维持频率的按风格分档（v10 方案 §4.5 表，V-C）：无显式 ProactivePolicy 行
 * 时按对方画像推导缺省策略——严肃/领导类低频（1 条/48h），亲熟热情类高频
 * （3 条/8h），其余标准（2 条/24h）；反馈冷淡（heat&lt;30）只降一档（方案 §8
 * V-C 验收：冷淡后 dailyCap 降档）。
 *
 * 边界口径：
 * - 分档是**身份上限**语义，只降不升——heat 高不把低档抬回去（热度放开的是
 *   选题深度 TopicStyleRules，不是联系频率）；
 * - 用户显式建过 policy 行（含 enabled=false 的显式关闭）一律优先，本对象
 *   只在**无行**时生效（用户覆盖优先，ProactiveCheckpoint 消费）。
 * 纯对象、零 Android 依赖，JVM 可直接单测。
 */
object ProactiveStyleTiers {

    /** 低频档：领导/严肃/浅亲疏。 */
    val CONSERVATIVE = Tier(dailyCap = 1, minGapHours = 48)

    /** 标准档：未显式分流的默认。 */
    val STANDARD = Tier(dailyCap = 2, minGapHours = 24)

    /** 高频档：热情/幽默/亲熟。 */
    val FREQUENT = Tier(dailyCap = 3, minGapHours = 8)

    data class Tier(val dailyCap: Int, val minGapHours: Int)

    /** 频率档裁决：身份/风格/亲疏分流 + 冷却降档（只降不升）。 */
    fun tierOf(identity: String?, chatStyle: String?, closeness: Int?, heatScore: Int?): Tier {
        var tier = when {
            identity == StyleVocab.IDENTITY_LEADER ||
                chatStyle == StyleVocab.STYLE_SERIOUS ||
                (closeness != null && closeness <= 1) -> CONSERVATIVE
            chatStyle == StyleVocab.STYLE_WARM ||
                chatStyle == StyleVocab.STYLE_HUMOR ||
                (closeness != null && closeness >= 2) -> FREQUENT
            else -> STANDARD
        }
        // 反馈冷淡：降一档（§4.5 表是上限；CONSERVATIVE 已最低不再降）。
        if ((heatScore ?: HeatRules.NEUTRAL) < 30 && tier !== CONSERVATIVE) {
            tier = when (tier) {
                FREQUENT -> STANDARD
                else -> CONSERVATIVE
            }
        }
        return tier
    }

    /** 无显式策略行时的缺省 policy：启用保温、默认静默时段、只出草稿不自动发。 */
    fun defaultPolicy(
        contactId: Long,
        identity: String?,
        chatStyle: String?,
        closeness: Int?,
        heatScore: Int?,
        nowMs: Long,
    ): ProactivePolicyEntity {
        val tier = tierOf(identity, chatStyle, closeness, heatScore)
        return ProactivePolicyEntity(
            contactId = contactId,
            enabled = true,
            dailyCap = tier.dailyCap,
            minGapHours = tier.minGapHours,
            quietStart = "22:30",
            quietEnd = "08:30",
            preferSmalltalk = true,
            updatedAt = nowMs,
        )
    }
}
