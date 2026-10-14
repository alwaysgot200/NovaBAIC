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

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentTextCodecTest {

    private fun zip(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun extractsDocxParagraphs() {
        val docx = zip(
            mapOf(
                "word/document.xml" to """
                    <w:document><w:body>
                    <w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>world &amp; friends</w:t></w:r></w:p>
                    <w:p><w:r><w:t>第二段</w:t></w:r></w:p>
                    </w:body></w:document>
                """.trimIndent(),
            ),
        )
        val text = DocumentTextCodec.extract(docx, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a.docx")
        assertEquals("Hello world & friends\n第二段", text)
    }

    @Test
    fun extractsXlsxSharedStringsAndNumbers() {
        val xlsx = zip(
            mapOf(
                "xl/sharedStrings.xml" to "<sst><si><t>名称</t></si><si><t>价格</t></si><si><t>苹果</t></si></sst>",
                "xl/worksheets/sheet1.xml" to """
                    <worksheet><sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                    <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>3.5</v></c></row>
                    </sheetData></worksheet>
                """.trimIndent(),
            ),
        )
        val text = DocumentTextCodec.extract(xlsx, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "a.xlsx")
        assertTrue(text!!.contains("名称 | 价格"))
        assertTrue(text.contains("苹果 | 3.5"))
    }

    @Test
    fun returnsNullForUnsupportedTypes() {
        assertNull(DocumentTextCodec.extract(ByteArray(0), "application/pdf", "a.pdf"))
        assertNull(DocumentTextCodec.extract(ByteArray(0), "text/plain", "a.txt"))
    }
}
