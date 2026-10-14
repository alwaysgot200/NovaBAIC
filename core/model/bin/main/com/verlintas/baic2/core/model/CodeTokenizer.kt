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

/** A highlighted span inside a code snippet. [end] is exclusive. */
data class CodeToken(val start: Int, val end: Int, val kind: Kind) {
    enum class Kind { KEYWORD, STRING, COMMENT, NUMBER, FUNCTION, VARIABLE }
}

/**
 * A tiny, dependency-free syntax tokenizer for the languages models emit most
 * often. It is intentionally forgiving: unknown languages return no tokens
 * (plain text) and malformed input never throws.
 *
 * The tokenizer only marks spans; the UI layer maps kinds to theme colours.
 */
object CodeTokenizer {

    private enum class Family { C_LIKE, PYTHON, SHELL, JSON, MARKUP, SQL, YAML }

    fun tokenize(code: String, language: String): List<CodeToken> {
        if (code.isEmpty()) return emptyList()
        val family = familyFor(language.trim().lowercase()) ?: return emptyList()
        return when (family) {
            Family.C_LIKE -> scan(code, C_LIKE, C_LIKE_KINDS)
            Family.PYTHON -> scan(code, PYTHON, PYTHON_KINDS)
            Family.SHELL -> scan(code, SHELL, SHELL_KINDS)
            Family.JSON -> scan(code, JSON, JSON_KINDS)
            Family.MARKUP -> scan(code, MARKUP, MARKUP_KINDS)
            Family.SQL -> scan(code, SQL, SQL_KINDS)
            Family.YAML -> scan(code, YAML, YAML_KINDS)
        }
    }

    private fun familyFor(language: String): Family? = when (language) {
        "kotlin", "kt", "kts", "java", "javascript", "js", "jsx", "typescript", "ts", "tsx",
        "c", "h", "cpp", "c++", "hpp", "cs", "csharp", "go", "golang", "rust", "rs",
        "swift", "dart", "scala", "groovy", "php",
        -> Family.C_LIKE

        "python", "py" -> Family.PYTHON
        "bash", "sh", "zsh", "shell", "console", "terminal", "powershell", "ps1" -> Family.SHELL
        "json", "jsonc" -> Family.JSON
        "html", "htm", "xml", "svg", "xhtml" -> Family.MARKUP
        "sql", "mysql", "postgres", "postgresql", "sqlite" -> Family.SQL
        "yaml", "yml", "toml", "ini", "properties", "conf" -> Family.YAML
        else -> null
    }

    /** [kinds] maps capture-group index (1-based) to a token kind; null skips. */
    private fun scan(
        code: String,
        regex: Regex,
        kinds: List<CodeToken.Kind?>,
    ): List<CodeToken> {
        val tokens = mutableListOf<CodeToken>()
        for (match in regex.findAll(code)) {
            for (index in kinds.indices) {
                val group = match.groups[index + 1] ?: continue
                kinds[index]?.let { kind ->
                    tokens += CodeToken(group.range.first, group.range.last + 1, kind)
                }
                break
            }
        }
        return tokens
    }

    private val C_LIKE_KEYWORDS = listOf(
        "abstract", "actual", "as", "async", "await", "break", "case", "catch", "class",
        "companion", "const", "constructor", "continue", "data", "default", "defer", "delete",
        "do", "dynamic", "else", "enum", "export", "extends", "external", "false", "final",
        "finally", "fn", "for", "from", "fun", "func", "get", "goto", "if", "implements",
        "import", "in", "init", "inline", "instanceof", "interface", "internal", "is",
        "lateinit", "let", "mut", "new", "null", "object", "open", "operator", "or", "out",
        "override", "package", "private", "protected", "pub", "public", "readonly", "reified",
        "return", "sealed", "set", "static", "struct", "super", "suspend", "switch", "this",
        "throw", "throws", "trait", "true", "try", "typealias", "typeof", "use", "val", "var",
        "void", "when", "where", "while", "with", "yield",
    ).joinToString("|")

    private val C_LIKE = Regex(
        """(?<comment>//[^\n]*|/\*[\s\S]*?\*/)""" +
            """|(?<string>"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`)""" +
            """|(?<number>\b\d+(?:\.\d+)?(?:[fFlLdDuU])?\b)""" +
            """|(?<keyword>\b(?:$C_LIKE_KEYWORDS)\b)""" +
            """|(?<function>\b[A-Za-z_]\w*(?=\s*\())""",
    )

    private val PYTHON = Regex(
        """(?<comment>#[^\n]*)""" +
            """|(?<string>\"\"\"[\s\S]*?\"\"\"|'''[\s\S]*?'''|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')""" +
            """|(?<number>\b\d+(?:\.\d+)?\b)""" +
            """|(?<keyword>\b(?:and|as|assert|async|await|break|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|nonlocal|not|or|pass|raise|return|try|while|with|yield|None|True|False)\b)""" +
            """|(?<function>\b[A-Za-z_]\w*(?=\s*\())""",
    )

    private val SHELL = Regex(
        """(?<comment>#[^\n]*)""" +
            """|(?<string>"(?:\\.|[^"\\])*"|'[^']*')""" +
            """|(?<variable>\$\{[^}\n]+\}|\$[A-Za-z_]\w*|\$\d)""" +
            """|(?<keyword>\b(?:if|then|elif|else|fi|for|while|until|do|done|case|esac|in|function|return|export|local|readonly|source|alias|echo|exit|set)\b)""",
    )

    private val JSON = Regex(
        """(?<function>"(?:\\.|[^"\\])*"(?=\s*:))""" +
            """|(?<string>"(?:\\.|[^"\\])*")""" +
            """|(?<number>-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)""" +
            """|(?<keyword>\b(?:true|false|null)\b)""",
    )

    private val MARKUP = Regex(
        """(?<comment><!--[\s\S]*?-->)""" +
            """|(?<keyword></?[A-Za-z][\w:.-]*(?=[\s/>]))""" +
            """|(?<string>"[^"]*"|'[^']*')""",
    )

    private val SQL = Regex(
        """(?<comment>--[^\n]*|/\*[\s\S]*?\*/)""" +
            """|(?<string>'(?:''|[^'])*')""" +
            """|(?<number>\b\d+(?:\.\d+)?\b)""" +
            """|(?<keyword>\b(?:select|from|where|join|inner|left|right|outer|full|on|group|by|order|having|limit|offset|insert|into|values|update|set|delete|create|table|index|view|drop|alter|add|column|primary|key|foreign|references|distinct|as|and|or|not|null|like|ilike|in|between|case|when|then|else|end|union|all|asc|desc|with|returning)\b)""" +
            """|(?<function>\b(?:count|sum|avg|min|max|coalesce|now|date|lower|upper)\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val YAML = Regex(
        """(?<comment>\#[^\n]*)""" +
            """|(?<function>^[ \t-]*[\w.-]+(?=\s*:))""" +
            """|(?<string>"[^"\n]*"|'[^'\n]*')""" +
            """|(?<number>\b\d+(?:\.\d+)?\b)""" +
            """|(?<keyword>\b(?:true|false|null|yes|no|on|off)\b)""",
        RegexOption.MULTILINE,
    )

    // Capture-group order must mirror the alternation order above.
    private val C_LIKE_KINDS = listOf(
        CodeToken.Kind.COMMENT,
        CodeToken.Kind.STRING,
        CodeToken.Kind.NUMBER,
        CodeToken.Kind.KEYWORD,
        CodeToken.Kind.FUNCTION,
    )
    private val PYTHON_KINDS = listOf(
        CodeToken.Kind.COMMENT,
        CodeToken.Kind.STRING,
        CodeToken.Kind.NUMBER,
        CodeToken.Kind.KEYWORD,
        CodeToken.Kind.FUNCTION,
    )
    private val SHELL_KINDS = listOf(
        CodeToken.Kind.COMMENT,
        CodeToken.Kind.STRING,
        CodeToken.Kind.VARIABLE,
        CodeToken.Kind.KEYWORD,
    )
    private val JSON_KINDS = listOf(
        CodeToken.Kind.FUNCTION,
        CodeToken.Kind.STRING,
        CodeToken.Kind.NUMBER,
        CodeToken.Kind.KEYWORD,
    )
    private val MARKUP_KINDS = listOf(
        CodeToken.Kind.COMMENT,
        CodeToken.Kind.KEYWORD,
        CodeToken.Kind.STRING,
    )
    private val SQL_KINDS = listOf(
        CodeToken.Kind.COMMENT,
        CodeToken.Kind.STRING,
        CodeToken.Kind.NUMBER,
        CodeToken.Kind.KEYWORD,
        CodeToken.Kind.FUNCTION,
    )
    private val YAML_KINDS = listOf(
        CodeToken.Kind.COMMENT,
        CodeToken.Kind.FUNCTION,
        CodeToken.Kind.STRING,
        CodeToken.Kind.NUMBER,
        CodeToken.Kind.KEYWORD,
    )
}
