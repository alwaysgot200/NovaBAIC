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

package com.verlintas.baic2.floatball.advisor.strategy

import com.verlintas.baic2.floatball.advisor.GROUP_CONFLICT
import com.verlintas.baic2.floatball.advisor.PATH_DEFEND
import com.verlintas.baic2.floatball.advisor.PATH_MAINTAIN
import com.verlintas.baic2.floatball.advisor.ScenarioPack

/**
 * 五道门的确定性部分（§7.4）：动作门（包:站 硬约束）、红线门（聚合去重）、
 * 承诺一致性门（固定句）、回退门（降温段）。意图门在 StyleResolver（§7.5 跨包兼容）。
 */
object StageGate {

    /** 承诺一致性门：固定一句，防止模型随口改价格/时间承诺。 */
    const val COMMITMENT_RULE = "涉及价格、时间的承诺必须引用双方既有约定，不得自行加码或改口"

    /** 冲突期红线分组前缀（defend 包 pack.taboos 前置标记）。 */
    private const val DEFEND_PREFIX = "冲突期"

    /**
     * 动作门：精确到「包:站」的硬约束；未配置的站走 pathKind 默认约束；仍无则 null。
     * dating weak/rebuild 禁邀约；sales 两包 early 站禁报价逼单；
     * job_hunt offer 站前禁最后通牒；vendor contract 前禁露预算上限。
     */
    private val actionByPackStage: Map<String, String> = mapOf(
        "dating:weak" to "当前是弱联系阶段：不发起邀约、不要见面话术，只做无压力的轻互动",
        "dating:rebuild" to "当前在重建对话：不发起邀约，先把自然聊天节奏恢复起来",
        "sales_b2b:ice" to "破冰阶段：不报价、不逼单，先确认对方身份与场景",
        "sales_b2b:inquiry" to "询盘阶段：不直接报价、不催促成交，先摸清需求与预算区间",
        "sales_c:ice" to "刚加上好友：不发产品广告、不报价、不催单",
        "sales_c:need" to "需求唤醒阶段：不报价、不逼单，先让对方说出真实情况",
        "job_hunt:apply" to "未到 offer 阶段：不下最后通牒、不拿其他 offer 施压",
        "job_hunt:interview" to "面试阶段：不下最后通牒、不抢先亮薪资底牌",
        "vendor:inquiry" to "摸底阶段：不主动暴露预算上限",
        "vendor:compare" to "比价阶段：不主动暴露预算上限",
    )

    private val actionByPath: Map<String, String> = mapOf(
        PATH_DEFEND to "冲突处理期：不拉近关系、不套近乎，不使用情绪化或威胁性措辞",
        PATH_MAINTAIN to "存量关系经营：不替对方拍板、不做越界承诺",
    )

    fun actionConstraint(packCode: String, pathKind: String, stageCode: String): String? =
        actionByPackStage["$packCode:$stageCode"] ?: actionByPath[pathKind]

    /**
     * 红线门：pack.taboos（defend 包加「冲突期·」前缀并天然排最前）+ 对方雷区 + 我的忌口，
     * 按文本去空、trim、去重，保持来源顺序。
     */
    fun redLines(
        pack: ScenarioPack,
        myTabooLines: List<String>,
        theirTabooLines: List<String>,
    ): List<String> {
        val packLines = pack.taboos.map { line ->
            line.trim().let { if (pack.pathKind == PATH_DEFEND) "$DEFEND_PREFIX·$it" else it }
        }
        return (packLines + theirTabooLines + myTabooLines)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * 回退门：退缩/激化信号命中时生成【本轮态势】降温段；无命中返回 null。
     * defend（冲突组）口径是「对方在激化，本轮目标是不升级」，其余是「降温、给空间」。
     */
    fun retreatSection(hits: List<String>, pack: ScenarioPack): String? {
        if (hits.isEmpty()) return null
        val words = hits.joinToString("、")
        return if (pack.pathKind == PATH_DEFEND || pack.group == GROUP_CONFLICT) {
            "对方在激化（$words），本轮目标是不升级：不接情绪、不争输赢，只稳住事实与边界"
        } else {
            "对方出现退缩信号（$words），本轮降温：不追问、不施压、给对方空间，只做低压力回应"
        }
    }
}
