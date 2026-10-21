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

package com.verlintas.baic2.floatball.experts.strategist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 军师 Strategist 岗位包（2026-10-21 五岗子代理架构整合方案 §二）：快车道 LLM
 * 职责=Permit 许可单（本回合方针与敏感手段授权），门面 [Strategist] 于 T1-b 落地；
 * 本文件先落四维策略的存储模型（T1-a，总方案 §四）。
 *
 * 四维手段的三档授权（裁量层方案 §三）：[TALKER_NEVER] 禁用 / [TALKER_ASSESS]
 * 自评（军师逐回合裁量+审查官验稿）/ [TALKER_FREE] 放行。正向手段（举例类比等）
 * 的「手段库段」不受档位限制、永远全量开放——档位只管敏感维动用。
 */

/** 撒谎（对对方作事实性不实陈述）。 */
const val TALKER_DIM_LYING = "lying"

/** 许愿（承诺/保证类表态）。 */
const val TALKER_DIM_PROMISING = "promising"

/** 举例（以虚构或他例作类比论证）。 */
const val TALKER_DIM_EXEMPLIFYING = "exemplifying"

/** 档位：禁用——任何回合不得动用（红线死清单另恒拦）。 */
const val TALKER_NEVER = "never"

/** 档位：自评——军师逐回合给出许可，审查官按许可单执法（默认档，D1 拍板）。 */
const val TALKER_ASSESS = "assess"

/** 档位：放行——无需许可即可自然动用（红线死清单仍恒拦）。 */
const val TALKER_FREE = "free"

/** 档位归一：空/未知值按缺键处理回默认档 [TALKER_ASSESS]（D1 拍板「全部自评」）。 */
fun normalizeTalkerLevel(level: String?): String =
    when (level) {
        TALKER_NEVER -> TALKER_NEVER
        TALKER_FREE -> TALKER_FREE
        else -> TALKER_ASSESS
    }

/**
 * 联系人级聊天手段策略（v12，T1-a）：撒谎/许愿/举例三维的档位集，落库为
 * contact_profiles.talkerStrategyJson（缺键=默认档）。第四维「隐私披露」沿用
 * privacyPolicy 列（F1 单点真源，v12 扩第三档 assess），不重复入 JSON。
 * 纯数据类 + 纯函数编解码，JVM 可直接单测。
 */
@Serializable
data class TalkerStrategy(
    val lying: String = TALKER_ASSESS,
    val promising: String = TALKER_ASSESS,
    val exemplifying: String = TALKER_ASSESS,
) {
    /** 按维度键取档位（军师触发判定/渲染消费）。 */
    fun levelOf(dim: String): String =
        when (dim) {
            TALKER_DIM_LYING -> lying
            TALKER_DIM_PROMISING -> promising
            TALKER_DIM_EXEMPLIFYING -> exemplifying
            else -> TALKER_ASSESS
        }

    /** 按维度键改档位（画像页开关消费）：档位先归一，未知维度原样返回。 */
    fun withLevel(dim: String, level: String): TalkerStrategy =
        when (dim) {
            TALKER_DIM_LYING -> copy(lying = normalizeTalkerLevel(level))
            TALKER_DIM_PROMISING -> copy(promising = normalizeTalkerLevel(level))
            TALKER_DIM_EXEMPLIFYING -> copy(exemplifying = normalizeTalkerLevel(level))
            else -> this
        }
}

/** talkerStrategyJson 编解码：畸形输入回默认档，绝不抛（防御旧数据/手改库）。 */
object TalkerStrategyCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /** 落库形态（紧凑 JSON）；与默认档一致的空策略也存显式值，画像页回显所见即所存。 */
    fun encode(strategy: TalkerStrategy): String =
        json.encodeToString(
            TalkerStrategy(
                lying = normalizeTalkerLevel(strategy.lying),
                promising = normalizeTalkerLevel(strategy.promising),
                exemplifying = normalizeTalkerLevel(strategy.exemplifying),
            ),
        )

    /** 解析 + 档位归一：null/畸形/缺键一律回 D1 拍板默认档（全部自评）。 */
    fun decode(raw: String?): TalkerStrategy =
        try {
            val parsed = raw?.let { json.decodeFromString<TalkerStrategy>(it) } ?: TalkerStrategy()
            parsed.copy(
                lying = normalizeTalkerLevel(parsed.lying),
                promising = normalizeTalkerLevel(parsed.promising),
                exemplifying = normalizeTalkerLevel(parsed.exemplifying),
            )
        } catch (_: Exception) {
            TalkerStrategy()
        }
}
