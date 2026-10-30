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

package com.verlintas.rpa.model

/**
 * L2 公式条件（§五）：类 Excel 公式，零代码、静态可校验、无副作用。
 *
 * 语法（递归下降）：
 * ```
 * expr    := or
 * or      := and ( '||' and )*
 * and     := not ( '&&' not )*
 * not     := '!' not | cmp
 * cmp     := primary ( ('=='|'!='|'>='|'<='|'>'|'<') primary )?
 * primary := number | string | 'true' | 'false' | '(' expr ')' | func '(' args? ')'
 * func    := var | hasImage | hasText | prevOk | random | now     # 白名单
 * ```
 *
 * 求值语义：两侧均可解析为数值时按数值比较，否则按字符串比较；排序比较作用于
 * 非数值字符串时抛 [RpaConditionException]（引擎按「条件不满足」降级并记日志）。
 * L3 沙箱 JS 为后续批次，实现同一 [RpaConditionContext] 接口即可替换。
 */
object RpaConditionDsl {

    /** 白名单函数（禁止任意调用；静态校验即拦截未知函数）。 */
    private val FUNCTIONS = setOf("var", "hasImage", "hasText", "prevOk", "random", "now")

    /**
     * 静态校验：只解析不求值（不触碰 [RpaConditionContext]，无副作用）。
     * 返回 null 表示合法，否则返回错误原因。
     */
    fun validate(expression: String): String? = runCatching {
        Parser(tokenize(expression)).parse()
        null
    }.getOrElse { failure -> failure.message ?: "expr_invalid" }

    /** 求值；语法 / 类型错误抛 [RpaConditionException]，由引擎按「不满足」降级。 */
    suspend fun evaluate(expression: String, context: RpaConditionContext): Boolean {
        val ast = try {
            Parser(tokenize(expression)).parse()
        } catch (failure: Throwable) {
            throw RpaConditionException(failure.message ?: "expr_parse_failed")
        }
        return try {
            ast.eval(context).toBool()
        } catch (failure: RpaConditionException) {
            throw failure
        } catch (failure: Throwable) {
            throw RpaConditionException(failure.message ?: "expr_eval_failed")
        }
    }

    // ---------------------------- 词法 ----------------------------

    private enum class TokenType { NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, COMMA, END }

    private data class Token(val type: TokenType, val text: String, val pos: Int)

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                ch.isWhitespace() -> i++
                ch == '(' -> { tokens += Token(TokenType.LPAREN, "(", i); i++ }
                ch == ')' -> { tokens += Token(TokenType.RPAREN, ")", i); i++ }
                ch == ',' -> { tokens += Token(TokenType.COMMA, ",", i); i++ }
                ch == '"' || ch == '\'' -> {
                    val quote = ch
                    val start = i
                    i++
                    val begin = i
                    while (i < input.length && input[i] != quote) i++
                    if (i >= input.length) throw RpaConditionException("unterminated_string@$start")
                    tokens += Token(TokenType.STRING, input.substring(begin, i), start)
                    i++
                }
                ch.isDigit() || (ch == '-' && i + 1 < input.length && input[i + 1].isDigit()) -> {
                    val start = i
                    if (ch == '-') i++
                    while (i < input.length && (input[i].isDigit() || input[i] == '.')) i++
                    tokens += Token(TokenType.NUMBER, input.substring(start, i), start)
                }
                ch.isLetter() || ch == '_' -> {
                    val start = i
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) i++
                    tokens += Token(TokenType.IDENT, input.substring(start, i), start)
                }
                else -> {
                    val two = input.substring(i, minOf(i + 2, input.length))
                    val op = when {
                        two == "==" || two == "!=" || two == ">=" || two == "<=" ||
                            two == "&&" || two == "||" -> two
                        ch == '>' || ch == '<' || ch == '!' -> ch.toString()
                        else -> throw RpaConditionException("unexpected_char '$ch'@$i")
                    }
                    tokens += Token(TokenType.OP, op, i)
                    i += op.length
                }
            }
        }
        tokens += Token(TokenType.END, "", input.length)
        return tokens
    }

    // ---------------------------- 语法 ----------------------------

    private sealed interface Node {
        suspend fun eval(context: RpaConditionContext): Value
    }

    private class Literal(val value: Value) : Node {
        override suspend fun eval(context: RpaConditionContext): Value = value
    }

    private class Call(val name: String, val args: List<Node>) : Node {
        override suspend fun eval(context: RpaConditionContext): Value = when (name) {
            "var" -> {
                requireArgs(name, args, 1)
                Value.Str(context.variable(args[0].eval(context).asString()) ?: "")
            }
            "hasImage" -> {
                requireArgs(name, args, 1)
                Value.Bool(context.hasImage(args[0].eval(context).asString()))
            }
            "hasText" -> {
                requireArgs(name, args, 1)
                Value.Bool(context.hasText(args[0].eval(context).asString()))
            }
            "prevOk" -> {
                requireArgs(name, args, 0)
                Value.Bool(context.prevOk())
            }
            "random" -> {
                requireArgs(name, args, 0)
                Value.Num(context.random())
            }
            "now" -> {
                requireArgs(name, args, 0)
                Value.Num(context.now().toDouble())
            }
            else -> throw RpaConditionException("unknown_function '$name'")
        }

        private fun requireArgs(name: String, args: List<Node>, count: Int) {
            if (args.size != count) {
                throw RpaConditionException("bad_arity '$name' expected=$count actual=${args.size}")
            }
        }
    }

    private class UnaryNot(val inner: Node) : Node {
        override suspend fun eval(context: RpaConditionContext): Value =
            Value.Bool(!inner.eval(context).toBool())
    }

    private class Binary(val op: String, val left: Node, val right: Node) : Node {
        override suspend fun eval(context: RpaConditionContext): Value {
            if (op == "&&" || op == "||") {
                val l = left.eval(context).toBool()
                // 短路：&& 左假不求右，|| 左真不求右。
                if (op == "&&" && !l) return Value.Bool(false)
                if (op == "||" && l) return Value.Bool(true)
                return Value.Bool(right.eval(context).toBool())
            }
            val l = left.eval(context)
            val r = right.eval(context)
            val ln = l.asNumberOrNull()
            val rn = r.asNumberOrNull()
            return when (op) {
                "==" -> Value.Bool(ln != null && rn != null && ln == rn ||
                    (ln == null || rn == null) && l.asString() == r.asString())
                "!=" -> Value.Bool(!(ln != null && rn != null && ln == rn ||
                    (ln == null || rn == null) && l.asString() == r.asString()))
                ">", ">=", "<", "<=" -> {
                    if (ln == null || rn == null) {
                        throw RpaConditionException("compare_needs_number '$op'")
                    }
                    Value.Bool(
                        when (op) {
                            ">" -> ln > rn
                            ">=" -> ln >= rn
                            "<" -> ln < rn
                            else -> ln <= rn
                        },
                    )
                }
                else -> throw RpaConditionException("unknown_op '$op'")
            }
        }
    }

    private class Parser(private val tokens: List<Token>) {
        private var index = 0

        private fun peek(): Token = tokens[index]

        private fun next(): Token = tokens[index++]

        private fun expect(type: TokenType): Token {
            val token = next()
            if (token.type != type) {
                throw RpaConditionException("expected $type got ${token.type}@${token.pos}")
            }
            return token
        }

        fun parse(): Node {
            val node = parseOr()
            if (peek().type != TokenType.END) {
                throw RpaConditionException("trailing_input@${peek().pos}")
            }
            return node
        }

        private fun parseOr(): Node {
            var node = parseAnd()
            while (peek().type == TokenType.OP && peek().text == "||") {
                next()
                node = Binary("||", node, parseAnd())
            }
            return node
        }

        private fun parseAnd(): Node {
            var node = parseNot()
            while (peek().type == TokenType.OP && peek().text == "&&") {
                next()
                node = Binary("&&", node, parseNot())
            }
            return node
        }

        private fun parseNot(): Node {
            if (peek().type == TokenType.OP && peek().text == "!") {
                next()
                return UnaryNot(parseNot())
            }
            return parseCmp()
        }

        private fun parseCmp(): Node {
            val left = parsePrimary()
            val token = peek()
            if (token.type == TokenType.OP &&
                token.text in setOf("==", "!=", ">", ">=", "<", "<=")
            ) {
                next()
                return Binary(token.text, left, parsePrimary())
            }
            return left
        }

        private fun parsePrimary(): Node {
            val token = next()
            return when (token.type) {
                TokenType.NUMBER -> Literal(Value.Num(token.text.toDouble()))
                TokenType.STRING -> Literal(Value.Str(token.text))
                TokenType.IDENT -> when (token.text) {
                    "true" -> Literal(Value.Bool(true))
                    "false" -> Literal(Value.Bool(false))
                    in FUNCTIONS -> {
                        expect(TokenType.LPAREN)
                        val args = mutableListOf<Node>()
                        if (peek().type != TokenType.RPAREN) {
                            args += parseOr()
                            while (peek().type == TokenType.COMMA) {
                                next()
                                args += parseOr()
                            }
                        }
                        expect(TokenType.RPAREN)
                        Call(token.text, args)
                    }
                    else -> throw RpaConditionException("unknown_identifier '${token.text}'@${token.pos}")
                }
                TokenType.LPAREN -> {
                    val node = parseOr()
                    expect(TokenType.RPAREN)
                    node
                }
                else -> throw RpaConditionException("unexpected_token@${token.pos}")
            }
        }
    }

    // ---------------------------- 值 ----------------------------

    private sealed class Value {
        data class Num(val value: Double) : Value()
        data class Str(val value: String) : Value()
        data class Bool(val value: Boolean) : Value()

        fun asString(): String = when (this) {
            is Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            is Str -> value
            is Bool -> value.toString()
        }

        fun asNumberOrNull(): Double? = when (this) {
            is Num -> value
            is Str -> value.trim().toDoubleOrNull()
            is Bool -> null
        }

        fun toBool(): Boolean = when (this) {
            is Bool -> value
            is Num -> value != 0.0
            is Str -> value.isNotEmpty()
        }
    }
}

/** 条件求值上下文（引擎实现；单测注入假实现即可覆盖 DSL）。 */
interface RpaConditionContext {

    /** 运行时变量值（未定义返回 null）。 */
    fun variable(name: String): String?

    /** 模板是否出现在当前屏幕。 */
    suspend fun hasImage(templateId: String): Boolean

    /** 文本是否出现在当前屏幕。 */
    suspend fun hasText(text: String): Boolean

    /** 上一步是否成功。 */
    fun prevOk(): Boolean

    /** [0,1) 随机数（引擎注入，便于单测确定性）。 */
    fun random(): Double

    /** 当前毫秒时间戳。 */
    fun now(): Long
}

/** 条件求值失败（引擎捕获后按「条件不满足」降级并记日志，绝不崩运行）。 */
class RpaConditionException(message: String) : RuntimeException(message)
