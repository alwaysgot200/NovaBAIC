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

import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaIds
import java.io.File
import kotlinx.serialization.json.Json

/**
 * RPA 文档式存储（与数据库解耦，脚本可导出 / 导入 / 版本管理）：
 *
 * ```
 * filesDir/rpa/
 *   scripts/<scriptId>.json        # 一次脚本 = 一个 JSON 文档（原子写）
 *   templates/<scriptId>/<id>.png  # 以图找图的裁剪模板
 * ```
 *
 * - JSON 是唯一事实源：动作顺序即 `steps` 数组顺序，拖拽只改数组；
 * - 模板与脚本按 `scriptId` 分区：删脚本即删模板目录，不会误伤其他脚本；
 * - id 全部做文件名白名单化（防导入文档路径穿越）。
 */
class RpaScriptStore(root: File) {

    val scriptsDir: File = File(root, "scripts")
    val templatesDir: File = File(root, "templates")

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    init {
        runCatching { scriptsDir.mkdirs() }
        runCatching { templatesDir.mkdirs() }
    }

    fun list(): List<RpaScript> {
        val files = runCatching {
            scriptsDir.listFiles { file -> file.isFile && file.extension == "json" }
        }.getOrDefault(emptyArray()) ?: emptyArray()
        return files.mapNotNull { file ->
            runCatching { json.decodeFromString(RpaScript.serializer(), file.readText()) }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): RpaScript? {
        val file = scriptFile(id)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(RpaScript.serializer(), file.readText())
        }.getOrNull()
    }

    /** 保存（新建 / 覆盖）。返回带最新时间戳的脚本。 */
    fun save(script: RpaScript): Result<RpaScript> = runCatching {
        require(script.id.isNotBlank()) { "script_id_blank" }
        val now = System.currentTimeMillis()
        val stamped = script.copy(
            createdAt = script.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
        val text = json.encodeToString(RpaScript.serializer(), stamped)
        atomicWrite(scriptFile(stamped.id), text.toByteArray(Charsets.UTF_8))
        stamped
    }

    fun delete(id: String): Boolean {
        val removedDoc = runCatching { scriptFile(id).delete() }.getOrDefault(false)
        runCatching { templateDir(id).deleteRecursively() }
        return removedDoc
    }

    fun duplicate(script: RpaScript, newName: String = script.name + " copy"): RpaScript {
        val newId = RpaIds.newId("script")
        val copy = script.copy(
            id = newId,
            name = newName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            steps = script.steps.map { step ->
                step.copy(id = RpaIds.newId("step"))
            },
        )
        copyTemplates(script.id, newId)
        save(copy)
        return copy
    }

    fun newScript(
        name: String,
        mode: RpaMode,
        appPackage: String = "",
        steps: List<RpaStep> = emptyList(),
    ): RpaScript {
        val now = System.currentTimeMillis()
        return RpaScript(
            id = RpaIds.newId("script"),
            name = name,
            mode = mode,
            appPackage = appPackage,
            steps = steps,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun exportJson(script: RpaScript): String =
        json.encodeToString(RpaScript.serializer(), script)

    /** 导入：一律分配新 id（避免覆盖本机脚本），模板不随 JSON 迁移。 */
    fun importJson(text: String): Result<RpaScript> = runCatching {
        val parsed = json.decodeFromString(RpaScript.serializer(), text)
        require(parsed.name.isNotBlank()) { "script_name_missing" }
        val imported = parsed.copy(
            id = RpaIds.newId("script"),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        save(imported).getOrThrow()
        imported
    }

    // ------------------------------ 模板 ------------------------------

    fun templateDir(scriptId: String): File =
        File(templatesDir, safeSegment(scriptId))

    fun templateFile(scriptId: String, templateId: String): File =
        File(templateDir(scriptId), "${safeSegment(templateId)}.png")

    fun templateExists(scriptId: String, templateId: String): Boolean =
        templateFile(scriptId, templateId).isFile

    fun listTemplateIds(scriptId: String): List<String> =
        runCatching {
            templateDir(scriptId).listFiles { file -> file.isFile && file.extension == "png" }
                ?.map { it.nameWithoutExtension }
                ?.sorted()
        }.getOrNull() ?: emptyList()

    fun saveTemplate(scriptId: String, templateId: String, png: ByteArray): Result<File> =
        runCatching {
            require(png.isNotEmpty()) { "template_empty" }
            val dir = templateDir(scriptId)
            require(dir.exists() || dir.mkdirs()) { "template_dir_unavailable" }
            val file = templateFile(scriptId, templateId)
            atomicWrite(file, png)
            file
        }

    fun deleteTemplate(scriptId: String, templateId: String): Boolean =
        runCatching { templateFile(scriptId, templateId).delete() }.getOrDefault(false)

    private fun copyTemplates(fromScriptId: String, toScriptId: String) {
        val from = templateDir(fromScriptId)
        if (!from.isDirectory) return
        val to = templateDir(toScriptId)
        runCatching { to.mkdirs() }
        runCatching {
            from.listFiles { file -> file.isFile }?.forEach { file ->
                file.copyTo(File(to, file.name), overwrite = true)
            }
        }
    }

    // ------------------------------ 内部 ------------------------------

    private fun scriptFile(id: String): File =
        File(scriptsDir, "${safeSegment(id)}.json")

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val parent = target.parentFile
        require(parent != null && (parent.exists() || parent.mkdirs())) {
            "parent_dir_unavailable: ${target.absolutePath}"
        }
        val temp = File(parent, target.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
    }

    /** 文件系统段白名单：只留字母数字与 `._-`，避免 `../` 路径穿越。 */
    private fun safeSegment(raw: String): String {
        val safe = raw.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")
        return safe.trim('.').ifBlank { "unnamed" }.take(96)
    }
}
