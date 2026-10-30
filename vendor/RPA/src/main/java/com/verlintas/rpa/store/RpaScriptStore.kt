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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.rpa.store

import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaStep
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/** 一次列表扫描的结果：可用脚本 + 解析失败的文件名（损坏的文档不静默消失）。 */
data class ScriptListReport(
    val scripts: List<RpaScript>,
    val failed: List<String> = emptyList(),
)

/** 导出三选一（决策①）：`.json` / `.xml` / `.rpa`（完整包）。 */
enum class RpaExportFormat(val fileExtension: String) {
    JSON("json"),
    XML("xml"),
    PACKAGE("rpa"),
    ;
}

/**
 * RPA 文档式存储（与数据库解耦，脚本可备份 / 版本管理）：
 *
 * ```
 * filesDir/rpa/
 *   scripts/<scriptId>.json        # 一次脚本 = 一个 JSON 文档（原子写）
 *   templates/<scriptId>/<id>.png  # 以图找图的裁剪模板
 * ```
 *
 * - JSON 是唯一事实源：动作顺序即 `steps` 数组顺序，拖拽只改数组；
 * - 模板与脚本按 `scriptId` 分区：删脚本即删模板目录，不会误伤其他脚本；
 * - id 全部做文件名白名单化（防路径穿越）；
 * - 所有读写串行化：编辑器保存与运行服务读盘可能并发，绝不允许读到写了一半的文件。
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

    /**
     * 扫描脚本目录。解析失败的文档单独汇报给 UI，避免「脚本凭空消失」。
     */
    @Synchronized
    fun listReport(): ScriptListReport {
        val files = runCatching {
            scriptsDir.listFiles { file -> file.isFile && file.extension == "json" }
        }.getOrDefault(emptyArray()) ?: emptyArray()
        val failed = mutableListOf<String>()
        val scripts = files.mapNotNull { file ->
            runCatching {
                json.decodeFromString(RpaScript.serializer(), file.readText())
            }.getOrElse {
                failed += file.name
                null
            }
        }.sortedByDescending { it.updatedAt }
        return ScriptListReport(scripts = scripts, failed = failed.sorted())
    }

    fun list(): List<RpaScript> = listReport().scripts

    @Synchronized
    fun load(id: String): RpaScript? {
        val file = scriptFile(id)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(RpaScript.serializer(), file.readText())
        }.getOrNull()
    }

    /** 保存（新建 / 覆盖）。返回带最新时间戳的脚本。 */
    @Synchronized
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

    @Synchronized
    fun delete(id: String): Boolean {
        val removedDoc = runCatching { scriptFile(id).delete() }.getOrDefault(false)
        runCatching { templateDir(id).deleteRecursively() }
        return removedDoc
    }

    /**
     * 复制脚本 + 模板。模板先复制、再落 JSON；JSON 落盘失败时回收新模板目录，
     * 绝不留下「有模板没有脚本」的孤儿数据。
     */
    @Synchronized
    fun duplicate(script: RpaScript, newName: String = script.name + " copy"): Result<RpaScript> =
        runCatching {
            val newId = RpaIds.newId("script")
            val now = System.currentTimeMillis()
            val copy = script.copy(
                id = newId,
                name = newName,
                createdAt = now,
                updatedAt = now,
                steps = script.steps.map { step -> step.copy(id = RpaIds.newId("step")) },
            )
            copyTemplates(script.id, newId)
            runCatching { save(copy).getOrThrow() }.getOrElse { failure ->
                runCatching { templateDir(newId).deleteRecursively() }
                throw failure
            }
            copy
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

    // ------------------------------ 导入导出 ------------------------------

    /**
     * 导出（决策① 三选一）。PACKAGE 连模板一起打包；单文件 JSON / XML 不含模板，
     * 导入时引用模板的步骤给警告不阻断（§八）。
     */
    @Synchronized
    fun exportBytes(script: RpaScript, format: RpaExportFormat): ByteArray = when (format) {
        RpaExportFormat.JSON -> RpaJsonCodec.encode(script)
        RpaExportFormat.XML -> RpaXmlCodec.encode(script)
        RpaExportFormat.PACKAGE -> RpaScriptPackage.pack(script, readTemplates(script.id))
    }

    /**
     * 导入：按内容自动识别 `.json` / `.xml` / `.rpa`（§八）。
     * 语义 = **新建脚本文档**（id 冲突自动取新 id，不做合并）；模板先落、脚本后落、
     * 失败回收，复用 [duplicate] 的防孤儿策略。返回带最新时间戳的入库脚本。
     */
    @Synchronized
    fun importBytes(bytes: ByteArray): Result<RpaScript> = runCatching {
        require(bytes.isNotEmpty()) { "import_empty" }
        val isZip = bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val imported: Pair<RpaScript, Map<String, ByteArray>> = when {
            isZip -> RpaScriptPackage.unpack(bytes).let { it.script to it.templates }
            looksLikeXml(bytes) -> RpaXmlCodec.decode(bytes) to emptyMap()
            else -> RpaJsonCodec.decode(bytes) to emptyMap()
        }
        var script = imported.first
        if (script.id.isBlank() || scriptFile(script.id).isFile) {
            script = script.copy(id = RpaIds.newId("script"))
        }
        val templates = imported.second
        try {
            templates.forEach { (templateId, png) ->
                saveTemplate(script.id, templateId, png).getOrThrow()
            }
            save(script).getOrThrow()
        } catch (failure: Throwable) {
            runCatching { templateDir(script.id).deleteRecursively() }
            throw failure
        }
    }

    private fun readTemplates(scriptId: String): Map<String, ByteArray> =
        listTemplateIds(scriptId).mapNotNull { templateId ->
            val file = templateFile(scriptId, templateId)
            if (file.isFile) runCatching { templateId to file.readBytes() }.getOrNull() else null
        }.toMap()

    // ------------------------------ 模板 ------------------------------

    fun templateDir(scriptId: String): File =
        File(templatesDir, safeSegment(scriptId))

    fun templateFile(scriptId: String, templateId: String): File =
        File(templateDir(scriptId), "${safeSegment(templateId)}.png")

    @Synchronized
    fun templateExists(scriptId: String, templateId: String): Boolean =
        templateFile(scriptId, templateId).isFile

    @Synchronized
    fun listTemplateIds(scriptId: String): List<String> =
        runCatching {
            templateDir(scriptId).listFiles { file -> file.isFile && file.extension == "png" }
                ?.map { it.nameWithoutExtension }
                ?.sorted()
        }.getOrNull() ?: emptyList()

    @Synchronized
    fun saveTemplate(scriptId: String, templateId: String, png: ByteArray): Result<File> =
        runCatching {
            require(png.isNotEmpty()) { "template_empty" }
            val dir = templateDir(scriptId)
            require(dir.exists() || dir.mkdirs()) { "template_dir_unavailable" }
            val file = templateFile(scriptId, templateId)
            atomicWrite(file, png)
            file
        }

    @Synchronized
    fun deleteTemplate(scriptId: String, templateId: String): Boolean =
        runCatching { templateFile(scriptId, templateId).delete() }.getOrDefault(false)

    private fun copyTemplates(fromScriptId: String, toScriptId: String) {
        val from = templateDir(fromScriptId)
        if (!from.isDirectory) return
        val to = templateDir(toScriptId)
        require(to.exists() || to.mkdirs()) { "template_dir_unavailable" }
        from.listFiles { file -> file.isFile }?.forEach { file ->
            file.copyTo(File(to, file.name), overwrite = true)
        }
    }

    // ------------------------------ 内部 ------------------------------

    private fun scriptFile(id: String): File =
        File(scriptsDir, "${safeSegment(id)}.json")

    /**
     * 原子写：先写 `.tmp`，再 `ATOMIC_MOVE` 覆盖目标。
     * 文件系统不支持原子移动时退回普通覆盖，失败一律清掉临时文件。
     */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val parent = target.parentFile
        require(parent != null && (parent.exists() || parent.mkdirs())) {
            "parent_dir_unavailable: ${target.absolutePath}"
        }
        val temp = File(parent, target.name + ".tmp")
        try {
            temp.writeBytes(bytes)
            val source = temp.toPath()
            val destination = target.toPath()
            try {
                Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            runCatching { temp.delete() }
            throw failure
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
