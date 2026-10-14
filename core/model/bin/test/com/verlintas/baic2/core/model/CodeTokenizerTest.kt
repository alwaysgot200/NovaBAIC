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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeTokenizerTest {

    private fun spans(code: String, language: String): List<Pair<String, CodeToken.Kind>> =
        CodeTokenizer.tokenize(code, language).map { token ->
            code.substring(token.start, token.end) to token.kind
        }

    @Test
    fun kotlinTokensCoverKeywordsStringsCommentsAndFunctions() {
        val code = "// note\nval x = \"hi\"\nfun go() = 42"
        val spans = spans(code, "kotlin")

        assertTrue(("// note" to CodeToken.Kind.COMMENT) in spans)
        assertTrue(("val" to CodeToken.Kind.KEYWORD) in spans)
        assertTrue(("\"hi\"" to CodeToken.Kind.STRING) in spans)
        assertTrue(("fun" to CodeToken.Kind.KEYWORD) in spans)
        assertTrue(("42" to CodeToken.Kind.NUMBER) in spans)
        assertTrue(anyFunction(spans, "go"))
    }

    @Test
    fun jsonKeysAreFunctionsAndLiteralsAreKeywords() {
        val code = """{"name": "Aviiya", "alive": true, "age": 1}"""
        val spans = spans(code, "json")

        assertTrue(spans.any { it.first == "\"name\"" && it.second == CodeToken.Kind.FUNCTION })
        assertTrue(spans.any { it.first == "\"Aviiya\"" && it.second == CodeToken.Kind.STRING })
        assertTrue(spans.any { it.first == "true" && it.second == CodeToken.Kind.KEYWORD })
        assertTrue(spans.any { it.first == "1" && it.second == CodeToken.Kind.NUMBER })
    }

    @Test
    fun pythonHandlesDefsTripleQuotesAndComments() {
        val code = "def greet():\n    \"\"\"doc\"\"\"\n    return 'hey'  # done"
        val spans = spans(code, "python")

        assertTrue(spans.any { it.first == "def" && it.second == CodeToken.Kind.KEYWORD })
        assertTrue(anyFunction(spans, "greet"))
        assertTrue(spans.any { it.first == "\"\"\"doc\"\"\"" && it.second == CodeToken.Kind.STRING })
        assertTrue(spans.any { it.first == "# done" && it.second == CodeToken.Kind.COMMENT })
    }

    @Test
    fun markupTagsAndComments() {
        val code = "<div class=\"x\"><!-- note --></div>"
        val spans = spans(code, "html")

        assertTrue(spans.any { it.first == "<div" && it.second == CodeToken.Kind.KEYWORD })
        assertTrue(spans.any { it.first == "<!-- note -->" && it.second == CodeToken.Kind.COMMENT })
        assertTrue(spans.any { it.first == "\"x\"" && it.second == CodeToken.Kind.STRING })
    }

    @Test
    fun unknownLanguageStaysPlain() {
        assertEquals(emptyList(), CodeTokenizer.tokenize("plain text", "brainfuck"))
        assertEquals(emptyList(), CodeTokenizer.tokenize("", "kotlin"))
    }

    @Test
    fun tokensAreOrderedAndNonOverlapping() {
        val code = "fun a() { val b = \"x // not a comment\" } // real"
        val tokens = CodeTokenizer.tokenize(code, "kotlin")

        assertTrue(tokens.isNotEmpty())
        var previousEnd = 0
        tokens.forEach { token ->
            assertTrue(token.start >= previousEnd, "tokens must not overlap")
            assertTrue(token.end > token.start)
            previousEnd = token.end
        }
        assertTrue(
            tokens.any {
                code.substring(it.start, it.end).startsWith("\"x //") &&
                    it.kind == CodeToken.Kind.STRING
            },
            "comment markers inside strings must not leak",
        )
    }

    private fun anyFunction(spans: List<Pair<String, CodeToken.Kind>>, name: String): Boolean =
        spans.any { it.first == name && it.second == CodeToken.Kind.FUNCTION }
}
