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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import com.verlintas.rpa.engine.RpaDevice
import com.verlintas.rpa.engine.RpaRunner
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.store.RpaExportFormat
import com.verlintas.rpa.store.RpaScriptPackage
import com.verlintas.rpa.store.RpaScriptStore
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import com.verlintas.rpa.model.RpaA11yLineParams
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaHit
import com.verlintas.rpa.model.RpaInputChannel
import com.verlintas.rpa.model.RpaIssueSeverity
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaRunEvent
import com.verlintas.rpa.model.RpaSelector
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaValidator
import com.verlintas.rpa.model.RpaVisionLineParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RpaWechatHelloFixtureTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun fixtureIsSevenStepDualWithBothLines() {
        val script = RpaWechatHelloFixture.build(launchDelayMs = 0)
        assertEquals(7, script.steps.size)
        assertTrue(script.steps.all { it.enabled })

        val defaultScript = RpaWechatHelloFixture.build()
        assertEquals(5_000L, defaultScript.steps.first().delayAfterMs)
        assertEquals(0L, script.steps.first().delayAfterMs)

        val errors = RpaValidator.validate(script)
            .filter { it.severity == RpaIssueSeverity.ERROR }
        assertTrue(errors.toString(), errors.isEmpty())

        listOf(1, 3, 4, 6).forEach { index ->
            val target = script.steps[index].target
            assertNotNull(target?.a11y)
            assertNotNull(target?.vision)
            assertTrue(target!!.a11y!!.isConfigured())
            assertTrue(target.vision!!.isConfigured())
        }
    }

    @Test
    fun a11yLineParamsAreSelfSufficient() {
        // a11y 侧自足（§〇.2 独立运行）：锁 a11y 线只消费节点参数即完成 7 步，
        // 每步的 a11y 专有字段逐项断言，不引用 vision 侧任何字段。
        val steps = RpaWechatHelloFixture.build(launchDelayMs = 0).steps

        // 步骤1 启动微信：readySelector = 文本含「微信」的节点（2026-10-31 真机
        // 取证：微信 8.x 树文本节点均不可点击，与拾取回填同语义只设 enabledOnly）。
        assertEquals(
            RpaSelector(text = "微信", textMatch = RpaMatchMode.CONTAINS, enabledOnly = true),
            steps[0].lineParams.a11y.readySelector,
        )
        // 步骤3/6 输入：inputSelector=空（沿用焦点）、appendMode=替换（SET_TEXT）。
        listOf(2, 5).forEach { index ->
            assertNull(steps[index].lineParams.a11y.inputSelector)
            assertFalse(steps[index].lineParams.a11y.appendMode)
        }
        // 步骤2/4/5/7 点击走 C 类定位，a11y 通道参数全默认。
        listOf(1, 3, 4, 6).forEach { index ->
            assertEquals("step=$index", RpaA11yLineParams(), steps[index].lineParams.a11y)
        }
    }

    @Test
    fun visionLineParamsAreSelfSufficient() {
        // 无 a11y 侧自足：锁无 a11y 线只消费视觉 / 注入参数即完成 7 步，
        // 每步的 vision 专有字段逐项断言，不引用 a11y 侧任何字段。
        val steps = RpaWechatHelloFixture.build(launchDelayMs = 0).steps

        // 步骤1 启动微信：readyText = 「微信」（OCR 等待启动完成）。
        assertEquals("微信", steps[0].lineParams.vision.readyText)
        // 步骤3/6 输入：剪贴板粘贴 + 先清空，不预点焦点。
        listOf(2, 5).forEach { index ->
            assertEquals(
                RpaInputChannel.CLIPBOARD_PASTE,
                steps[index].lineParams.vision.inputChannel,
            )
            assertTrue(steps[index].lineParams.vision.clearBeforeInput)
            assertNull(steps[index].lineParams.vision.focusPoint)
        }
        // 步骤2/4/5/7 点击走 C 类定位，vision 通道参数全默认。
        listOf(1, 3, 4, 6).forEach { index ->
            assertEquals("step=$index", RpaVisionLineParams(), steps[index].lineParams.vision)
        }
    }

    @Test
    fun fakeDeviceRunsAllSevenSteps() = runBlocking {
        val script = RpaWechatHelloFixture.build(launchDelayMs = 0)
        val events = mutableListOf<RpaRunEvent>()
        val result = RpaRunner(FakeRpaDevice())
            .run(script) { event -> events += event }

        assertTrue(result.detail, result.ok)
        assertEquals(7, events.count { it is RpaRunEvent.StepStarted })
        assertEquals(7, events.count { it is RpaRunEvent.StepFinished })
        assertTrue(
            events.filterIsInstance<RpaRunEvent.StepFinished>().all { it.ok },
        )
        val finished = events.last() as RpaRunEvent.ScriptFinished
        assertTrue(finished.ok)
    }

    @Test
    fun fixtureRpaPackageRoundTripKeepsDualLineParamsAndTemplates() {
        // T8 文件往返（spec §7.5）：夹具导出 `.rpa` → 重导入，双线参数 + 模板
        // 往返零丢失，文件契约（manifest / sha256 / 条目）逐一抽查。
        val script = RpaWechatHelloFixture.build(launchDelayMs = 0)
        val exportLib = RpaScriptStore(folder.newFolder("export-lib"))
        exportLib.save(script).getOrThrow()
        // 夹具的以图找图模板随脚本入库：导出包必须带出、重导入零丢失。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        exportLib.saveTemplate(script.id, "tpl-wechat", png).getOrThrow()

        val packed = exportLib.exportBytes(script, RpaExportFormat.PACKAGE)

        // 文件契约（spec §7.5）：条目 = manifest + script.json + templates/<id>.png。
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(packed)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(
            setOf("manifest.json", "script.json", "templates/tpl-wechat.png"),
            entries.keys,
        )
        val unpacked = RpaScriptPackage.unpack(packed)
        assertEquals(RpaScriptPackage.FORMAT, unpacked.manifest.format)
        assertEquals(RpaScript.CURRENT_SCHEMA_VERSION, unpacked.manifest.schemaVersion)
        assertEquals(script.name, unpacked.manifest.name)
        assertTrue(unpacked.manifest.exportedAt > 0)
        // 夹具无 P2 占位动作：不打「需升级」标。
        assertFalse(unpacked.manifest.scriptCode)
        // sha256 必须是 script.json 的（防截包改脚本，§7.5）。
        assertEquals(
            RpaScriptPackage.sha256(entries.getValue("script.json")),
            unpacked.manifest.sha256,
        )

        // 重导入独立库（新文档语义）：双线参数 + 模板往返零丢失。
        val importLib = RpaScriptStore(folder.newFolder("import-lib"))
        val imported = importLib.importBytes(packed).getOrThrow()
        assertEquals(script.id, imported.id)
        assertEquals(script.name, imported.name)
        assertEquals(script.mode, imported.mode)
        assertEquals(script.appPackage, imported.appPackage)
        assertEquals(script.description, imported.description)
        assertEquals(script.variables, imported.variables)
        // 7 步全字段逐项零丢失：含 C 类双线定位（a11y 选择器组 / vision 主兜底）
        // 与 D 类分线通道参数（readySelector/readyText、inputChannel……）。
        assertEquals(script.steps, imported.steps)
        // 模板按 templateId 原样回库，字节零丢失。
        assertEquals(listOf("tpl-wechat"), importLib.listTemplateIds(script.id))
        assertArrayEquals(png, importLib.templateFile(script.id, "tpl-wechat").readBytes())
    }

    private class FakeRpaDevice : RpaDevice {

        override val capabilities = RpaCapabilities(
            a11yReady = true,
            shizukuReady = true,
            visionReady = true,
            ocrReady = true,
            projectionReady = true,
            screenshotReady = true,
        )

        override suspend fun launchApp(
            packageName: String,
            line: RpaLine,
            restart: Boolean,
            closeOnly: Boolean,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun openChat(
            imPackage: String,
            contactName: String,
            line: RpaLine,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun tap(x: Int, y: Int, line: RpaLine, holdMs: Int): Result<Unit> =
            Result.success(Unit)

        override suspend fun longPress(x: Int, y: Int, line: RpaLine): Result<Unit> =
            Result.success(Unit)

        override suspend fun swipe(
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
            durationMs: Int,
            line: RpaLine,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun inputText(
            text: String,
            line: RpaLine,
            clearFirst: Boolean,
            inputSelector: RpaSelector?,
            appendMode: Boolean,
            inputChannel: RpaInputChannel,
            focusPoint: com.verlintas.rpa.model.RpaRect?,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun pressKey(key: String, line: RpaLine): Result<Unit> =
            Result.success(Unit)

        override suspend fun openLink(url: String, line: RpaLine): Result<Unit> =
            Result.success(Unit)

        override suspend fun locate(
            scriptId: String,
            target: RpaTarget,
            line: RpaLine,
        ): RpaHit = RpaHit(x = 540, y = 960, detail = "fake", line = line)

        override suspend fun textLines(
            line: RpaLine,
            readScope: RpaSelector?,
            ocrRegion: com.verlintas.rpa.model.RpaRect?,
        ): List<String> = listOf("你好")

        override suspend fun foregroundPackage(line: RpaLine): String =
            RpaWechatHelloFixture.DEFAULT_IM_PACKAGE

        override fun screenSize(): Pair<Int, Int> = 1080 to 1920
    }
}
