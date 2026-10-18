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

import com.verlintas.baic2.floatball.advisor.ScenarioPackBundle
import com.verlintas.baic2.floatball.advisor.data.ACTION_KIND_MEETUP
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行动建议家纯函数全分支（M1 方案 §10 M1-B 验收：库资产红线——12 条 / risk 必填 /
 * 阶段码与场景包口径一致；阶段白名单过滤；模型输出宽松解析与硬闸丢弃）。
 */
class ActionPlannerTest {

    // ── 测试夹具 ─────────────────────────────────────────────────────────

    private val bank: ActionStratagemBank by lazy {
        ActionStratagemEngine.decodeBank(readAsset("src/main/assets/advisor/action_stratagems.json"))
    }

    private fun readAsset(path: String): String {
        val file = File(path)
        assertTrue("asset missing: $path (cwd=${File(".").absolutePath})", file.exists())
        return file.readText()
    }

    /** 场景包 stage code 全集（资产口径一致性校验的基准）。 */
    private val allPackStageCodes: Set<String> by lazy {
        val bundle = strategyJson.decodeFromString(
            ScenarioPackBundle.serializer(),
            readAsset("src/main/assets/advisor/scenario_packs.json"),
        )
        bundle.packs.flatMap { it.stages }.map { it.code }.toSet()
    }

    private fun stratagem(
        code: String = "S01",
        name: String = "投石问路",
        kind: String = ACTION_KIND_MEETUP,
        paths: List<String> = listOf("pursue"),
        stages: List<String> = listOf("ice", "inquiry"),
        steps: List<String> = listOf("找由头接触{name}", "不谈报价"),
        risk: String = "库内风险",
    ) = ActionStratagem(
        code = code,
        name = name,
        category = "business",
        kind = kind,
        paths = paths,
        stages = stages,
        route = "路径说明",
        steps = steps,
        prereq = "前提",
        risk = risk,
    )

    // ── 资产红线（方案 §6.1 / §5.3 验收） ─────────────────────────────────

    @Test
    fun asset_hasTwelveEntriesWithUniqueCodes() {
        assertEquals(12, bank.stratagems.size)
        assertEquals(bank.stratagems.size, bank.stratagems.map { it.code }.distinct().size)
        bank.stratagems.forEach { s ->
            assertTrue("bad code ${s.code}", Regex("S\\d{2}").matches(s.code))
        }
    }

    @Test
    fun asset_riskAndRouteAndStepsMandatory() {
        bank.stratagems.forEach { s ->
            assertTrue("risk blank: ${s.code}", s.risk.isNotBlank())
            assertTrue("route blank: ${s.code}", s.route.isNotBlank())
            assertTrue("prereq blank: ${s.code}", s.prereq.isNotBlank())
            assertTrue("steps too few: ${s.code}", s.steps.size in 2..6)
            s.steps.forEach { step -> assertTrue("blank step in ${s.code}", step.isNotBlank()) }
        }
    }

    @Test
    fun asset_kindCategoryAndPathsLegal() {
        bank.stratagems.forEach { s ->
            assertTrue("bad kind ${s.kind}", s.kind in ActionStratagemEngine.KINDS)
            assertTrue("bad category ${s.category}", s.category in ActionStratagemEngine.CATEGORIES)
            assertTrue("bad paths ${s.paths}", s.paths.all { it in setOf("pursue", "maintain", "defend") })
            assertTrue("empty stages: ${s.code}", s.stages.isNotEmpty())
        }
    }

    @Test
    fun asset_stageCodesAlignedWithScenarioPacks() {
        bank.stratagems.forEach { s ->
            s.stages.forEach { code ->
                assertTrue("stage code $code (${s.code}) not in any scenario pack", code in allPackStageCodes)
            }
        }
    }

    // ── 阶段白名单过滤 ───────────────────────────────────────────────────

    @Test
    fun filter_keepsOnlyWhitelistedStage() {
        val bank = ActionStratagemBank(listOf(stratagem("S01", stages = listOf("ice")), stratagem("S02", stages = listOf("probe"))))
        val picked = ActionStratagemEngine.filter(bank, "probe", null)
        assertEquals(listOf("S02"), picked.map { it.code })
    }

    @Test
    fun filter_blankOrUnknownStageMeansNoCandidates() {
        val bank = ActionStratagemBank(listOf(stratagem()))
        assertTrue(ActionStratagemEngine.filter(bank, null, null).isEmpty())
        assertTrue(ActionStratagemEngine.filter(bank, "  ", null).isEmpty())
        assertTrue(ActionStratagemEngine.filter(bank, "no_such_stage", null).isEmpty())
    }

    @Test
    fun filter_pathKindNarrowsButUnlimitedPathsPass() {
        val bank = ActionStratagemBank(
            listOf(
                stratagem("S01", paths = listOf("pursue")),
                stratagem("S02", paths = listOf("defend")),
                stratagem("S03", paths = emptyList()),
            ),
        )
        assertEquals(listOf("S01", "S03"), ActionStratagemEngine.filter(bank, "ice", "pursue").map { it.code })
        assertEquals(listOf("S02", "S03"), ActionStratagemEngine.filter(bank, "ice", "defend").map { it.code })
        // 路径未知（无包上下文）时不做路径收窄，只按阶段白名单。
        assertEquals(listOf("S01", "S02", "S03"), ActionStratagemEngine.filter(bank, "ice", null).map { it.code })
    }

    // ── 占位填充 ────────────────────────────────────────────────────────

    @Test
    fun fillParams_replacesKnownAndKeepsUnknown() {
        val out = ActionStratagemEngine.fillParams("找{name}聊聊{topic}", mapOf("name" to "张三"))
        assertEquals("找张三聊聊{topic}", out)
    }

    // ── 模型输出解析（宽松 + 硬闸） ──────────────────────────────────────

    private val allowed = listOf(
        stratagem("S01", name = "投石问路", kind = ACTION_KIND_MEETUP, steps = listOf("模板1->{name}", "模板2")),
        stratagem("S02", name = "借力打力", kind = "custom", steps = listOf("模板A")),
    )

    @Test
    fun parse_acceptsBareArray() {
        val raw = """[{"stratagemCode":"S01","title":"展会偶遇","risk":"时间成本","steps":["步骤一"]}]"""
        val drafts = ActionStratagemEngine.parse(raw, allowed, mapOf("name" to "张三"))
        assertEquals(1, drafts.size)
        assertEquals("S01", drafts[0].stratagemCode)
        assertEquals("展会偶遇", drafts[0].title)
        assertEquals(listOf("步骤一"), drafts[0].steps)
    }

    @Test
    fun parse_acceptsWrapperAndFences() {
        val raw = "好的，如下：\n```json\n{\"plays\":[{\"stratagemCode\":\"S02\",\"risk\":\"人情消耗\"}]}\n```\n请参考"
        val drafts = ActionStratagemEngine.parse(raw, allowed)
        assertEquals(1, drafts.size)
        assertEquals("借力打力", drafts[0].title)
    }

    @Test
    fun parse_acceptsSingleObject() {
        val drafts = ActionStratagemEngine.parse(
            "{\"stratagemCode\":\"S01\",\"title\":\"活动搭话\",\"risk\":\"r\"}",
            allowed,
        )
        assertEquals(1, drafts.size)
        assertEquals("活动搭话", drafts[0].title)
    }

    @Test
    fun parse_dropsStratagemOutsideWhitelist() {
        val raw = """[
            {"stratagemCode":"S99","title":"库外","risk":"r"},
            {"stratagemCode":"s01","title":"小写编号","risk":"r"}
        ]"""
        val drafts = ActionStratagemEngine.parse(raw, allowed)
        assertEquals(1, drafts.size)
        assertEquals("S01", drafts[0].stratagemCode)
        assertEquals("小写编号", drafts[0].title)
    }

    @Test
    fun parse_riskFallsBackToLibraryThenDropsWhenBothBlank() {
        val fallback = ActionStratagemEngine.parse(
            """[{"stratagemCode":"S01","title":"无风险"}]""",
            allowed,
        )
        assertEquals("库内风险", fallback.single().risk)

        val blankRiskLib = listOf(stratagem("S01", risk = " "))
        val dropped = ActionStratagemEngine.parse(
            """[{"stratagemCode":"S01","title":"都没填"}]""",
            blankRiskLib,
        )
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun parse_titleAndStepsFallBackToLibrary() {
        val drafts = ActionStratagemEngine.parse(
            """[{"stratagemCode":"S01","risk":"r"}]""",
            allowed,
            mapOf("name" to "李四"),
        )
        assertEquals(1, drafts.size)
        assertEquals("投石问路", drafts[0].title)
        assertEquals(listOf("模板1->李四", "模板2"), drafts[0].steps)
        assertEquals("前提", drafts[0].prereq)
    }

    @Test
    fun parse_invalidKindFallsBackToStratagemKind() {
        val drafts = ActionStratagemEngine.parse(
            """[{"stratagemCode":"S01","title":"t","risk":"r","kind":"hack"}]""",
            allowed,
        )
        assertEquals(ACTION_KIND_MEETUP, drafts.single().kind)
    }

    @Test
    fun parse_deduplicatesAndCapsAtPlaysMax() {
        val raw = """[
            {"stratagemCode":"S01","title":"同题","risk":"r"},
            {"stratagemCode":"S01","title":"同题","risk":"r"},
            {"stratagemCode":"S01","title":"乙","risk":"r"},
            {"stratagemCode":"S02","title":"丙","risk":"r"},
            {"stratagemCode":"S01","title":"丁","risk":"r"}
        ]"""
        val drafts = ActionStratagemEngine.parse(raw, allowed)
        assertEquals(ActionStratagemEngine.PLAYS_MAX, drafts.size)
        assertEquals(1, drafts.count { it.title == "同题" })
    }

    @Test
    fun parse_malformedOutputYieldsEmpty() {
        assertTrue(ActionStratagemEngine.parse("", allowed).isEmpty())
        assertTrue(ActionStratagemEngine.parse("今天不方便输出 JSON", allowed).isEmpty())
        assertTrue(ActionStratagemEngine.parse("{broken", allowed).isEmpty())
        assertTrue(ActionStratagemEngine.parse("""[{"stratagemCode":"","title":"t","risk":"r"}]""", allowed).isEmpty())
    }

    // ── 提示词协议 ──────────────────────────────────────────────────────

    @Test
    fun systemPrompt_containsProtocolAndRedLines() {
        val system = ActionPlannerPrompts.systemPrompt()
        listOf("stratagemCode", "\"plays\"", "risk", "禁止", "欺骗").forEach { key ->
            assertTrue("missing $key", system.contains(key))
        }
    }

    @Test
    fun userPrompt_containsFactsAndWhitelistedCandidates() {
        val facts = PlanFacts(
            contactName = "张三",
            scenarioLabel = "B2B销售",
            pathKind = "pursue",
            stageCode = "inquiry",
            stageLabel = "询盘",
            goalTitles = listOf("两周内约到当面聊"),
        )
        val prompt = ActionPlannerPrompts.userPrompt(facts, allowed)
        assertTrue(prompt.contains("联系人：张三"))
        assertTrue(prompt.contains("询盘（inquiry）"))
        assertTrue(prompt.contains("两周内约到当面聊"))
        assertTrue(prompt.contains("[S01] 投石问路"))
        assertTrue(prompt.contains("[S02] 借力打力"))
        assertTrue(prompt.contains("模板1->{name}"))
        assertNotNull(ActionPlannerPrompts.pathLabel("maintain"))
        assertEquals("推进", ActionPlannerPrompts.pathLabel("pursue"))
    }
}
