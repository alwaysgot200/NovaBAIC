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
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.rpa.store

import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaStepCondition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable

/**
 * `.rpa` 完整包（§八）：市场交换格式。
 *
 * ```
 * manifest.json        # format / schemaVersion / exportedAt / name / sha256 / scriptCode
 * script.json|xml      # 脚本本体（导出默认 script.json，导入两者皆认）
 * templates/<id>.png   # 模板按 templateId 命名，条件与定位共享同一模板池
 * ```
 */
object RpaScriptPackage {

    const val FORMAT = "rpa-package"
    private const val MANIFEST_NAME = "manifest.json"
    private const val SCRIPT_JSON = "script.json"
    private const val SCRIPT_XML = "script.xml"
    private const val TEMPLATE_PREFIX = "templates/"

    @Serializable
    data class Manifest(
        val format: String = FORMAT,
        val schemaVersion: Int = RpaScript.CURRENT_SCHEMA_VERSION,
        val exportedAt: Long = 0,
        val name: String = "",
        val sha256: String = "",
        /** 是否含 P2 占位能力（点击颜色 / 多指手势等）：导入时 UI 标「需升级」徽标。 */
        val scriptCode: Boolean = false,
    )

    /**
     * 打包。[templateBytes] 的 key 是 templateId；缺模板不阻断打包（单文件导入
     * 允许引用缺失模板并警告，§八）。
     */
    fun pack(script: RpaScript, templateBytes: Map<String, ByteArray>): ByteArray {
        val scriptBytes = RpaJsonCodec.encode(script)
        val manifest = Manifest(
            exportedAt = System.currentTimeMillis(),
            name = script.name,
            sha256 = sha256(scriptBytes),
            scriptCode = containsP2Capability(script),
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(
                rpaJson.encodeToString(Manifest.serializer(), manifest)
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(SCRIPT_JSON))
            zip.write(scriptBytes)
            zip.closeEntry()
            templateBytes.forEach { (templateId, png) ->
                if (png.isEmpty()) return@forEach
                zip.putNextEntry(ZipEntry(TEMPLATE_PREFIX + safeSegment(templateId) + ".png"))
                zip.write(png)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * 解包。sha256 不符视为损坏拒绝导入（防截包改脚本）；模板 id 从文件名还原。
     */
    fun unpack(bytes: ByteArray): Unpacked {
        var manifest: Manifest? = null
        var scriptBytes: ByteArray? = null
        var scriptIsXml = false
        val templates = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val data = zip.readBytes()
                when {
                    entry.name == MANIFEST_NAME -> manifest = runCatching {
                        rpaJson.decodeFromString(Manifest.serializer(), String(data, Charsets.UTF_8))
                    }.getOrNull()
                    entry.name == SCRIPT_JSON -> {
                        scriptBytes = data
                        scriptIsXml = false
                    }
                    entry.name == SCRIPT_XML -> {
                        if (scriptBytes == null) {
                            scriptBytes = data
                            scriptIsXml = true
                        }
                    }
                    entry.name.startsWith(TEMPLATE_PREFIX) && entry.name.endsWith(".png") -> {
                        val templateId = entry.name
                            .removePrefix(TEMPLATE_PREFIX)
                            .removeSuffix(".png")
                        if (templateId.isNotBlank() && data.isNotEmpty()) {
                            templates[templateId] = data
                        }
                    }
                }
            }
        }
        val body = scriptBytes ?: throw IllegalArgumentException("package_script_missing")
        val manifestValue = manifest ?: throw IllegalArgumentException("package_manifest_missing")
        if (manifestValue.format != FORMAT) {
            throw IllegalArgumentException("package_format_invalid")
        }
        if (manifestValue.sha256.isNotBlank() && manifestValue.sha256 != sha256(body)) {
            throw IllegalArgumentException("package_sha256_mismatch")
        }
        val script = if (scriptIsXml) RpaXmlCodec.decode(body) else RpaJsonCodec.decode(body)
        return Unpacked(script = script, manifest = manifestValue, templates = templates)
    }

    data class Unpacked(
        val script: RpaScript,
        val manifest: Manifest,
        val templates: Map<String, ByteArray>,
    )

    /** 含 P2 占位能力（裁定③）：运行到这些步骤会显式报 unsupported_action。 */
    fun containsP2Capability(script: RpaScript): Boolean {
        val steps = script.steps + script.customActions.flatMap { it.steps }
        return steps.any { step ->
            step.action in setOf(RpaAction.TAP_COLOR, RpaAction.GESTURE_MULTI) ||
                step.runCondition is RpaStepCondition.Expr
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 包内条目名白名单化：只留字母数字与 `._-`，防路径穿越。 */
    private fun safeSegment(raw: String): String {
        val safe = raw.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")
        return safe.trim('.').ifBlank { "unnamed" }.take(96)
    }
}
