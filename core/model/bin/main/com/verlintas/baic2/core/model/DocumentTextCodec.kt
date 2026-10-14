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
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.core.model

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Dependency-free text extraction for OOXML documents: `.docx` (paragraphs of
 * `word/document.xml`) and `.xlsx` (shared strings + cell values of every
 * worksheet). PDFs are handled by the device layer because rasterization and
 * OCR need Android APIs.
 *
 * This intentionally reads the XML with narrow regexes instead of pulling in
 * a full OOXML parser: model attachments only need readable text, not layout.
 */
object DocumentTextCodec {

    fun extract(bytes: ByteArray, mimeType: String, fileName: String): String? = when {
        mimeType.contains("wordprocessingml") || fileName.endsWith(".docx", ignoreCase = true) ->
            docx(bytes)
        mimeType.contains("spreadsheetml") || fileName.endsWith(".xlsx", ignoreCase = true) ->
            xlsx(bytes)
        else -> null
    }

    private fun docx(bytes: ByteArray): String? = runCatching {
        val document = readEntry(bytes, "word/document.xml") ?: return null
        document.split("</w:p>")
            .map { paragraph ->
                PARAGRAPH_TEXT.findAll(paragraph)
                    .map { unescape(it.groupValues[1]) }
                    .joinToString("")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun xlsx(bytes: ByteArray): String? = runCatching {
        val shared = readEntry(bytes, "xl/sharedStrings.xml")
            ?.let { xml -> SHARED_TEXT.findAll(xml).map { unescape(it.groupValues[1]) }.toList() }
            .orEmpty()

        val sections = mutableListOf<String>()
        sheetNames(bytes).forEach { sheet ->
            val xml = readEntry(bytes, sheet) ?: return@forEach
            val rows = ROW.findAll(xml).mapNotNull { row ->
                val values = CELL.findAll(row.groupValues[1]).mapNotNull { cell ->
                    val attributes = cell.groupValues[1]
                    val raw = VALUE.find(cell.groupValues[2])?.groupValues?.get(1)
                        ?: INLINE_TEXT.find(cell.groupValues[2])?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    if (attributes.contains("t=\"s\"")) {
                        shared.getOrNull(raw.trim().toIntOrNull() ?: -1)
                    } else {
                        unescape(raw).trim()
                    }
                }.toList()
                if (values.all { it.isBlank() }) null else values.joinToString(" | ")
            }.toList()
            if (rows.isNotEmpty()) sections += "## ${sheet.substringAfterLast('/')}\n" + rows.joinToString("\n")
        }
        sections.joinToString("\n\n").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun sheetNames(bytes: ByteArray): List<String> =
        zipEntries(bytes)
            .filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .sortedBy { entry ->
                entry.substringAfterLast("sheet").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE
            }

    private fun readEntry(bytes: ByteArray, entryName: String): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == entryName) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return null
    }

    private fun zipEntries(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
        }
        return names
    }

    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private val PARAGRAPH_TEXT = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
    private val SHARED_TEXT = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val ROW = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<c\\b([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
    private val VALUE = Regex("<v[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_TEXT = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
}
