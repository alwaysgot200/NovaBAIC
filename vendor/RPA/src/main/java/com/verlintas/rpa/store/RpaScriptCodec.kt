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

import com.verlintas.rpa.model.RpaScript
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * 脚本编解码（§八）：内部事实源是 JSON（[RpaJsonCodec]），导出可选 XML（[RpaXmlCodec]）。
 * 两个 codec 字段一一对应：XML 是「JSON 树」的等价映射（sealed 条件、Map、嵌套步骤
 * 全部不丢字段），往返一致由单测兜底。纯 JVM（JAXP），Android 与桌面都可用。
 */
interface RpaScriptCodec {

    val formatName: String

    val fileExtension: String

    fun encode(script: RpaScript): ByteArray

    fun decode(bytes: ByteArray): RpaScript
}

/** 与 [RpaScriptStore] 同配置的 JSON 实例（ignoreUnknownKeys 保证 v1 → v2 天然兼容）。 */
internal val rpaJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

object RpaJsonCodec : RpaScriptCodec {

    override val formatName: String = "json"

    override val fileExtension: String = "json"

    override fun encode(script: RpaScript): ByteArray =
        rpaJson.encodeToString(RpaScript.serializer(), script).toByteArray(Charsets.UTF_8)

    override fun decode(bytes: ByteArray): RpaScript =
        rpaJson.decodeFromString(RpaScript.serializer(), String(bytes, Charsets.UTF_8))
}

/**
 * XML 编解码：`<rpa-script format="json-tree">` 包裹一棵与 JSON 等价的树
 * （object/field/array/item/string/number/bool/null）。刻意不用 android.util.Xml
 * （vendor 单测在 JVM 跑），也不引第三方库——JAXP 两端都有。
 */
object RpaXmlCodec : RpaScriptCodec {

    override val formatName: String = "xml"

    override val fileExtension: String = "xml"

    private const val ROOT_TAG = "rpa-script"
    private const val FORMAT = "json-tree"

    override fun encode(script: RpaScript): ByteArray {
        val tree = rpaJson.parseToJsonElement(
            rpaJson.encodeToString(RpaScript.serializer(), script),
        )
        val body = StringBuilder()
        writeNode(body, tree, indent = 1)
        val text = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<$ROOT_TAG format=\"$FORMAT\" ")
            append("schemaVersion=\"${script.schemaVersion}\">\n")
            append(body)
            append("</$ROOT_TAG>\n")
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    override fun decode(bytes: ByteArray): RpaScript {
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            // 导入文件不可信：禁 DTD / 外部实体（XXE 防御），解析失败按损坏处理。
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities",
                    false,
                )
            }
            runCatching {
                factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false,
                )
            }
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }.getOrElse { failure ->
            throw IllegalArgumentException("xml_parse_failed: ${failure.message}")
        }
        val root = document.documentElement
        if (root == null || root.tagName != ROOT_TAG) {
            throw IllegalArgumentException("xml_root_invalid")
        }
        val tree = readChildren(root).singleOrNull { it.first == null }
            ?.second
            ?: throw IllegalArgumentException("xml_body_missing")
        return rpaJson.decodeFromJsonElement(RpaScript.serializer(), tree)
    }

    // ---------------------------- 写 ----------------------------

    private fun writeNode(out: StringBuilder, element: JsonElement, indent: Int) {
        val pad = "  ".repeat(indent)
        when {
            element is JsonNull -> out.append(pad).append("<null/>\n")
            element is JsonObject -> {
                out.append(pad).append("<object>\n")
                element.forEach { (key, value) ->
                    out.append("  ").append(pad).append("<field name=\"")
                    out.append(escapeAttr(key)).append("\">\n")
                    writeNode(out, value, indent + 2)
                    out.append("  ").append(pad).append("</field>\n")
                }
                out.append(pad).append("</object>\n")
            }
            element is JsonArray -> {
                out.append(pad).append("<array>\n")
                element.forEach { value -> writeNode(out, value, indent + 1) }
                out.append(pad).append("</array>\n")
            }
            element is JsonPrimitive -> {
                val tag = when {
                    element.isString -> "string"
                    element.booleanOrNull != null -> "bool"
                    else -> "number"
                }
                out.append(pad).append("<").append(tag).append(">")
                out.append(escapeText(element.content))
                out.append("</").append(tag).append(">\n")
            }
        }
    }

    // ---------------------------- 读 ----------------------------

    /** 读取元素的子节点：返回（field 名，值）列表；普通值的 field 名为 null。 */
    private fun readChildren(element: Element): List<Pair<String?, JsonElement>> {
        val out = mutableListOf<Pair<String?, JsonElement>>()
        val children = element.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val child = node as Element
            when (child.tagName) {
                "field" -> out += child.getAttribute("name") to readValue(child)
                "object", "array" -> out += null to readValue(child)
                "string", "number", "bool", "null" -> out += null to readValue(child)
                else -> throw IllegalArgumentException("xml_tag_invalid: ${child.tagName}")
            }
        }
        return out
    }

    private fun readValue(element: Element): JsonElement {
        return when (element.tagName) {
            "field" -> readChildren(element).singleOrNull()?.second
                ?: throw IllegalArgumentException("xml_field_invalid")
            "object" -> {
                val entries = readChildren(element).map { (name, value) ->
                    (name ?: throw IllegalArgumentException("xml_object_key_missing")) to value
                }
                JsonObject(entries.toMap())
            }
            "array" -> JsonArray(readChildren(element).map { it.second })
            "string" -> JsonPrimitive(elementText(element))
            "number" -> numberPrimitive(elementText(element))
            "bool" -> JsonPrimitive(elementText(element).toBoolean())
            "null" -> JsonNull
            else -> throw IllegalArgumentException("xml_tag_invalid: ${element.tagName}")
        }
    }

    /** number 节点保留数值性：整数 / 小数都按原始文本还原成 JsonPrimitive。 */
    private fun numberPrimitive(text: String): JsonPrimitive = when {
        text.toIntOrNull() != null -> JsonPrimitive(text.toInt())
        text.toLongOrNull() != null -> JsonPrimitive(text.toLong())
        text.toDoubleOrNull() != null -> JsonPrimitive(text.toDouble())
        else -> throw IllegalArgumentException("xml_number_invalid: $text")
    }

    private fun elementText(element: Element): String {
        val out = StringBuilder()
        val children = element.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                out.append(node.nodeValue)
            }
        }
        return out.toString()
    }

    // ---------------------------- 转义 ----------------------------

    private fun escapeText(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> if (isXmlSafe(ch)) append(ch) else append('�')
            }
        }
    }

    private fun escapeAttr(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (isXmlSafe(ch)) append(ch) else append('�')
            }
        }
    }

    /** XML 1.0 允许的字符：控制字符会让整个文档解析失败，替换为占位符。 */
    private fun isXmlSafe(ch: Char): Boolean =
        ch == '\t' || ch == '\n' || ch == '\r' ||
            ch.code in 0x20..0xD7FF || ch.code in 0xE000..0xFFFD
}

/** 从字节流自动识别 json / xml（导入用）。 */
internal fun looksLikeXml(bytes: ByteArray): Boolean =
    bytes.decodeToString(0, minOf(bytes.size, 64)).trimStart().startsWith("<")

/** 从 JSON 树读取对象（包 manifest 用）。 */
internal fun parseJsonObject(text: String): JsonObject =
    rpaJson.parseToJsonElement(text).jsonObject
