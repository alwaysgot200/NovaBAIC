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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L2 公式条件（[RpaConditionDsl]）单测：静态校验白名单、比较 / 逻辑 / 函数求值、
 * 非数值排序比较按「求值异常」抛出（引擎侧降级为不满足）。
 */
class RpaConditionDslTest {

    @Test
    fun validateAcceptsWellFormedExpression() {
        assertNull(RpaConditionDsl.validate("var(\"n\") > 3 && !hasText(\"广告\")"))
        assertNull(RpaConditionDsl.validate("hasImage(\"tpl1\") || prevOk()"))
    }

    @Test
    fun validateRejectsUnknownFunction() {
        assertNotNull(RpaConditionDsl.validate("foo(1)"))
        assertNotNull(RpaConditionDsl.validate("eval(\"1+1\")"))
    }

    @Test
    fun validateRejectsGarbage() {
        assertNotNull(RpaConditionDsl.validate("var(\"a\") =="))
        assertNotNull(RpaConditionDsl.validate("var(\"a\""))
        assertNotNull(RpaConditionDsl.validate("a ~ b"))
    }

    @Test
    fun comparesNumbersNumerically() = runBlocking {
        val context = FakeContext(vars = mapOf("n" to "5"))
        assertTrue(RpaConditionDsl.evaluate("var(\"n\") > 3", context))
        assertFalse(RpaConditionDsl.evaluate("var(\"n\") < 3", context))
        assertTrue(RpaConditionDsl.evaluate("var(\"n\") >= 5.0", context))
    }

    @Test
    fun comparesStringsAndLogic() = runBlocking {
        val context = FakeContext(
            vars = mapOf("s" to "abc"),
            texts = setOf("促销广告位"),
            prev = true,
        )
        assertTrue(RpaConditionDsl.evaluate("\"abc\" == var(\"s\")", context))
        assertTrue(RpaConditionDsl.evaluate("hasText(\"广告\") || prevOk()", context))
        assertFalse(RpaConditionDsl.evaluate("hasText(\"不存在\") && !prevOk()", context))
        assertTrue(RpaConditionDsl.evaluate("!hasImage(\"tpl\")", context))
    }

    @Test
    fun supportsRandomAndNowFunctions() = runBlocking {
        val context = FakeContext(random = 0.5)
        assertTrue(RpaConditionDsl.evaluate("random() < 1 && now() > 0", context))
    }

    @Test
    fun orderingCompareOnNonNumbersThrows() {
        assertThrows(RpaConditionException::class.java) {
            runBlocking { RpaConditionDsl.evaluate("\"a\" < \"b\"", FakeContext()) }
        }
    }

    @Test
    fun emptyExpressionThrowsForEvaluation() {
        assertThrows(RpaConditionException::class.java) {
            runBlocking { RpaConditionDsl.evaluate("", FakeContext()) }
        }
        assertNotNull(RpaConditionDsl.validate(""))
    }

    @Test
    fun contextReceivesVariableLookups() {
        val context = FakeContext(vars = mapOf("x" to "1"))
        assertEquals("1", context.variable("x"))
        assertNull(context.variable("y"))
    }

    private class FakeContext(
        private val vars: Map<String, String> = emptyMap(),
        private val images: Set<String> = emptySet(),
        private val texts: Set<String> = emptySet(),
        private val prev: Boolean = true,
        private val random: Double = 0.5,
    ) : RpaConditionContext {

        override fun variable(name: String): String? = vars[name]

        override suspend fun hasImage(templateId: String): Boolean = templateId in images

        override suspend fun hasText(text: String): Boolean =
            texts.any { it.contains(text) }

        override fun prevOk(): Boolean = prev

        override fun random(): Double = random

        override fun now(): Long = 1_700_000_000_000
    }
}
