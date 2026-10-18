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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor

import com.verlintas.baic2.floatball.advisor.data.ContactAliasEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * undoMerge 别名收回判定（F5 纯函数 [isAliasRestorable]）：该别名的现存行
 * 全部属本档（或已无任何行）才可收回；第三档在册行 → false 跳过，
 * 防 insertAll REPLACE 偷走别档别名。
 */
class ContactRepositoryTest {

    private fun row(contactId: Long, alias: String = "张三") = ContactAliasEntity(
        contactId = contactId,
        alias = alias,
        rawAlias = alias,
        source = "a11y",
        lastSeenAt = 0L,
    )

    @Test
    fun isAliasRestorable_noRowsMeansRestorable() {
        // 被并期间无人再取该别名 → 可安全收回。
        assertTrue(isAliasRestorable(emptyList(), fromId = 7L))
    }

    @Test
    fun isAliasRestorable_allRowsOwnedByFromIdIsRestorable() {
        assertTrue(isAliasRestorable(listOf(row(7L), row(7L)), fromId = 7L))
    }

    @Test
    fun isAliasRestorable_thirdPartyRowBlocksRestore() {
        // 同名别名已被第三档重新取得 → 跳过，不得 REPLACE 偷档。
        assertFalse(isAliasRestorable(listOf(row(7L), row(9L)), fromId = 7L))
        assertFalse(isAliasRestorable(listOf(row(9L)), fromId = 7L))
    }
}
