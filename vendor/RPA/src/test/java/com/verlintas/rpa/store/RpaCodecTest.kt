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

package com.verlintas.rpa.store

import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaCompareOp
import com.verlintas.rpa.model.RpaCustomAction
import com.verlintas.rpa.model.RpaCustomParam
import com.verlintas.rpa.model.RpaDirection
import com.verlintas.rpa.model.RpaExpect
import com.verlintas.rpa.model.RpaFailurePolicy
import com.verlintas.rpa.model.RpaFlowKind
import com.verlintas.rpa.model.RpaGlobalSetting
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaParams
import com.verlintas.rpa.model.RpaRect
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaSelector
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaStepCondition
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaVarAssign
import com.verlintas.rpa.model.RpaVarSource
import com.verlintas.rpa.model.RpaVarType
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 存储层单测（§八）：JSON ↔ XML ↔ `.rpa` 包往返、v1 兼容、篡改拒绝、
 * 导入导出自动识别与 id 冲突处置。纯 JVM 直跑。
 */
class RpaCodecTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ------------------------------ 编解码往返 ------------------------------

    @Test
    fun jsonRoundTripKeepsEveryField() {
        val script = richScript()
        assertEquals(script, RpaJsonCodec.decode(RpaJsonCodec.encode(script)))
    }

    @Test
    fun xmlRoundTripKeepsEveryField() {
        val script = richScript()
        assertEquals(script, RpaXmlCodec.decode(RpaXmlCodec.encode(script)))
    }

    @Test
    fun xmlMatchesJsonSemantics() {
        val script = richScript()
        val viaXml = RpaXmlCodec.decode(RpaXmlCodec.encode(script))
        val viaJson = RpaJsonCodec.decode(RpaJsonCodec.encode(viaXml))
        assertEquals(viaJson, viaXml)
    }

    @Test
    fun v1JsonDecodesWithV2Defaults() {
        val script = RpaJsonCodec.decode(V1_JSON.toByteArray(Charsets.UTF_8))
        assertEquals(1, script.schemaVersion)
        assertEquals(2, script.steps.size)
        assertEquals(1_000L, script.steps[0].params.waitMs)
        // v1 没有的字段取 v2 默认值，不炸、不丢步骤。
        assertEquals(RpaGlobalSetting(), script.global)
        assertTrue(script.customActions.isEmpty())
        assertEquals(RpaStepCondition.None, script.steps[0].runCondition)
    }

    // ------------------------------ `.rpa` 包 ------------------------------

    @Test
    fun packageRoundTripKeepsScriptAndTemplates() {
        val script = richScript()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val packed = RpaScriptPackage.pack(script, mapOf("tpl1" to png))
        val unpacked = RpaScriptPackage.unpack(packed)
        assertEquals(script, unpacked.script)
        // ByteArray 引用比较恒不等：按内容逐项比。
        assertEquals(setOf("tpl1"), unpacked.templates.keys)
        assertArrayEquals(png, unpacked.templates.getValue("tpl1"))
        assertEquals(script.name, unpacked.manifest.name)
        // 夹具 s5 带 Expr 条件（裁定② 高级兼容层，后续批次）：按 P2 占位打「需升级」标。
        assertTrue(unpacked.manifest.scriptCode)
    }

    @Test
    fun packageRejectsTamperedScript() {
        val script = richScript()
        val packed = RpaScriptPackage.pack(script, emptyMap())
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(packed)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        // 只改脚本不改 manifest：sha256 必须拦下（防截包改脚本）。
        entries["script.json"] = RpaJsonCodec.encode(script.copy(name = "篡改"))
        val tampered = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                entries.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RpaScriptPackage.unpack(tampered)
        }
        assertEquals("package_sha256_mismatch", failure.message)
    }

    @Test
    fun packageMarksP2Capability() {
        val p2 = richScript().copy(
            steps = listOf(
                RpaStep(id = "p", action = RpaAction.TAP_COLOR),
            ),
        )
        assertTrue(RpaScriptPackage.containsP2Capability(p2))
        val unpacked = RpaScriptPackage.unpack(RpaScriptPackage.pack(p2, emptyMap()))
        assertTrue(unpacked.manifest.scriptCode)
        // 无 P2 动作、无 Expr 条件的脚本不打标。
        val plain = p2.copy(steps = listOf(RpaStep(id = "p", action = RpaAction.TAP)))
        assertTrue(!RpaScriptPackage.containsP2Capability(plain))
    }

    // ------------------------------ 存储导入导出 ------------------------------

    @Test
    fun storeExportImportAutoDetectsAllFormats() {
        val script = richScript()
        listOf(RpaExportFormat.JSON, RpaExportFormat.XML, RpaExportFormat.PACKAGE).forEach { format ->
            // 每种格式用独立库：同 id 二次入库按冲突处置取新 id（storeImportAssignsNewIdOnConflict 覆盖）。
            val store = RpaScriptStore(folder.newFolder("scripts-${format.name.lowercase()}"))
            val imported = store.importBytes(store.exportBytes(script, format)).getOrThrow()
            assertEquals("format=$format", script.id, imported.id)
            assertEquals("format=$format", script.steps, imported.steps)
            assertEquals("format=$format", script.global, imported.global)
            assertEquals("format=$format", script.customActions, imported.customActions)
            assertEquals("format=$format", script.variables, imported.variables)
        }
    }

    @Test
    fun storeImportAssignsNewIdOnConflict() {
        val store = RpaScriptStore(folder.newFolder("scripts"))
        val bytes = RpaJsonCodec.encode(richScript())
        val first = store.importBytes(bytes).getOrThrow()
        val second = store.importBytes(bytes).getOrThrow()
        assertEquals("script-1", first.id)
        assertNotEquals(first.id, second.id)
        assertEquals(2, store.list().size)
    }

    // ------------------------------ 夹具 ------------------------------

    private fun richScript() = RpaScript(
        id = "script-1",
        name = "往返脚本",
        mode = RpaMode.DUAL,
        appPackage = "com.example.app",
        description = "codec 往返",
        variables = mapOf("counter" to "5", "name" to "张三"),
        steps = listOf(
            RpaStep(
                id = "s1",
                action = RpaAction.LAUNCH_APP,
                label = "打开应用",
                params = RpaParams(
                    packageName = "com.example.app",
                    restart = true,
                    closeOnly = false,
                ),
                timeoutMs = 15_000,
                retries = 1,
                delayBeforeMs = 100,
                delayAfterMs = 200,
                onFailure = RpaFailurePolicy.CONTINUE,
                note = "备注",
                repeat = 2,
                runCondition = RpaStepCondition.Variable(
                    lhs = "\${counter}",
                    op = RpaCompareOp.GE,
                    rhs = "5",
                ),
            ),
            RpaStep(
                id = "s2",
                action = RpaAction.SET_VARIABLE,
                params = RpaParams(
                    assignments = listOf(
                        RpaVarAssign(
                            variableName = "greet",
                            type = RpaVarType.STRING,
                            source = RpaVarSource.LITERAL,
                            value = "你好\${name}",
                        ),
                        RpaVarAssign(
                            variableName = "counter",
                            type = RpaVarType.NUMBER,
                            source = RpaVarSource.INCREMENT,
                            value = "3",
                        ),
                    ),
                ),
            ),
            RpaStep(
                id = "s3",
                action = RpaAction.CONTROL_FLOW,
                params = RpaParams(flowKind = RpaFlowKind.GOTO, gotoStepId = "s5"),
            ),
            RpaStep(
                id = "s4",
                action = RpaAction.TAP,
                target = RpaTarget(
                    a11y = RpaA11ySpec(
                        selectors = listOf(RpaSelector(text = "按钮", clickableOnly = true)),
                    ),
                    vision = RpaVisionSpec(
                        primary = RpaVisionLocator(
                            kind = RpaVisionKind.TEMPLATE,
                            templateId = "tpl1",
                            matchIndex = 2,
                            quickSearch = true,
                            region = RpaRect(0.1f, 0.1f, 0.9f, 0.9f),
                        ),
                        fallback = RpaVisionLocator(
                            kind = RpaVisionKind.OCR_TEXT,
                            ocrText = "确定",
                        ),
                    ),
                ),
                expect = RpaExpect(text = "完成", match = RpaMatchMode.EXACT),
            ),
            RpaStep(
                id = "s5",
                action = RpaAction.COMPOSITE,
                params = RpaParams(
                    customActionId = "ca1",
                    customArgValues = mapOf("who" to "张三"),
                ),
                runCondition = RpaStepCondition.Expr(expression = "var(\"counter\") >= 5"),
            ),
            RpaStep(
                id = "s6",
                action = RpaAction.SWIPE,
                params = RpaParams(
                    direction = RpaDirection.UP,
                    distanceRatio = 0.5f,
                    durationMs = 300,
                    holdMs = 100,
                ),
            ),
        ),
        global = RpaGlobalSetting(
            defaultWaitSec = 0.5,
            repeat = 2,
            stopOnFailure = false,
            globalListener = "监听",
            gestureTransform = "变形",
        ),
        customActions = listOf(
            RpaCustomAction(
                id = "ca1",
                name = "打招呼",
                params = listOf(RpaCustomParam(name = "who", default = "朋友")),
                steps = listOf(
                    RpaStep(
                        id = "c1",
                        action = RpaAction.INPUT_TEXT,
                        params = RpaParams(text = "你好\${who}"),
                        runCondition = RpaStepCondition.TextVisible(
                            text = "聊天",
                            match = RpaMatchMode.CONTAINS,
                        ),
                    ),
                ),
            ),
        ),
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_001_000,
    )

    private companion object {
        val V1_JSON = """
            {
              "schemaVersion": 1,
              "id": "legacy-1",
              "name": "旧版脚本",
              "mode": "A11Y",
              "steps": [
                { "id": "s1", "action": "WAIT", "params": { "waitMs": 1000 } },
                { "id": "s2", "action": "COMMENT", "unknownField": "ignored" }
              ],
              "legacyTopLevel": { "anything": true }
            }
        """.trimIndent()
    }
}
