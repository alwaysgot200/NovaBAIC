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

package com.verlintas.baic2.floatball.strategy

import com.verlintas.baic2.floatball.advisor.GROUP_CONFLICT
import com.verlintas.baic2.floatball.advisor.PATH_DEFEND
import com.verlintas.baic2.floatball.advisor.PATH_MAINTAIN
import com.verlintas.baic2.floatball.advisor.PATH_PURSUE
import com.verlintas.baic2.floatball.advisor.ScenarioPack
import com.verlintas.baic2.floatball.advisor.strategy.StageGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageGateTest {

    private val datingPack = ScenarioPack(
        code = "dating",
        label = "恋爱",
        pathKind = PATH_PURSUE,
        taboos = listOf("查户口式连续提问", "提前任"),
    )

    private val adversarialPack = ScenarioPack(
        code = "adversarial",
        label = "人际冲突",
        pathKind = PATH_DEFEND,
        group = GROUP_CONFLICT,
        taboos = listOf("情绪对骂", "翻旧账"),
    )

    @Test
    fun actionConstraint_packStageOverridesThenPathDefaults() {
        assertTrue(StageGate.actionConstraint("dating", PATH_PURSUE, "weak")!!.contains("不发起邀约"))
        // pursue 包未配置的站无默认动作约束（交给意图门与红线门）。
        assertNull(StageGate.actionConstraint("job_hunt", PATH_PURSUE, "offer"))
        // defend/maintain 走 pathKind 默认。
        assertTrue(StageGate.actionConstraint("adversarial", PATH_DEFEND, "hold")!!.contains("不拉近关系"))
        assertTrue(StageGate.actionConstraint("colleague", PATH_MAINTAIN, "familiar")!!.contains("越界承诺"))
        // vendor contract 前禁露预算上限。
        assertTrue(StageGate.actionConstraint("vendor", PATH_MAINTAIN, "compare")!!.contains("预算上限"))
    }

    @Test
    fun redLines_mergePackTheirMyAndDedup() {
        val lines = StageGate.redLines(
            datingPack,
            myTabooLines = listOf("不聊工资", " 不聊工资 ", ""),
            theirTabooLines = listOf("别提体重"),
        )
        assertEquals(listOf("查户口式连续提问", "提前任", "别提体重", "不聊工资"), lines)
    }

    @Test
    fun redLines_defendPackItemsPrefixedAndFirst() {
        val lines = StageGate.redLines(
            adversarialPack,
            myTabooLines = listOf("不签空白协议"),
            theirTabooLines = emptyList(),
        )
        assertEquals("冲突期·情绪对骂", lines.first())
        assertTrue(lines.contains("冲突期·翻旧账"))
        assertTrue(lines.contains("不签空白协议"))
    }

    @Test
    fun retreatSection_defendDeescalateVsPursueCooling() {
        val defendText = StageGate.retreatSection(listOf("威胁"), adversarialPack)!!
        assertTrue(defendText.contains("不升级"))
        val coolText = StageGate.retreatSection(listOf("算了"), datingPack)!!
        assertTrue(coolText.contains("降温"))
        assertNull(StageGate.retreatSection(emptyList(), datingPack))
    }
}
