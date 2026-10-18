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

package com.verlintas.baic2.floatball

/**
 * 实体判别（v10，UltraAdvisor v2.2 方案 §2.1）：SceneGate 判 CHAT 后回答
 * 「对方是谁」，输出三态——唯一对方名 → [Entity.Person]（命中查档案、未命中
 * 自动建档双建）；多发言人 → [Entity.Group]（建群壳）；无法确定 → [Entity.None]
 * （孤立聊天会话，不建任何卡）。
 *
 * 判定完全复用 [IncomingCue] 的内容级发言人提取（L121 起的对话结构启发，
 * 含 URL/时间行噪声剔除），不重复造轮子：标题级（L1/L2 窗口标题带）识别
 * 仍由 ScreenAdvisor.resolveContact 的既有两级标题路径承担，本对象只负责
 * 正文级证据。纯函数、无 Android 依赖，JVM 可直接单测。
 */
internal object EntityResolver {

    /** 实体三态。 */
    sealed interface Entity {
        /** 唯一对话对象，[name] 为正文里出现 ≥2 行的发言人名。 */
        data class Person(val name: String) : Entity

        /** 多发言人：按群壳处理（群名走标题路径，本态不做成员级画像）。 */
        data object Group : Entity

        /** 无法确定：孤立聊天会话，可稍后手动关联（分屏人设区仅 CHAT 屏显示）。 */
        data object None : Entity
    }

    fun judge(screenBody: String?): Entity {
        if (screenBody.isNullOrBlank()) return Entity.None
        val (others, mine) = IncomingCue.speakerStatsOf(screenBody)
        // 多发言人 = 群聊形态：即使「我」没插上话也按群处理（群名由标题带补）。
        if (others.size >= 2) return Entity.Group
        val (name, count) = others.entries.firstOrNull() ?: return Entity.None
        // 单人 + 双方都有话（对方 ≥2 行且「我」现身）才判成「人」，单行冒名不足以建档。
        return if (mine && count >= 2) Entity.Person(name) else Entity.None
    }
}
