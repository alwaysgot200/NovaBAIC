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

package com.verlintas.baic2.floatball.auto

import android.content.Context
import com.verlintas.baic2.floatball.AdvisorPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自动托管范围持久化（方案 §5.3.1 / §9.1）：`联系人 × APP 会话` 的托管组。
 *
 * 只落「选了谁」这一份轻量数据（不含聊天内容，隐私红线）；进程被杀后选择集
 * 保留，但运行态不自动恢复（方案 §8.11）。旧版 [AdvisorPrefs.autoWhitelist]
 * 首次读取时按联系人级托管目标迁移，保证升级后行为不丢。
 */
@Singleton
class AutoSelectionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val advisorPrefs: AdvisorPrefs,
) {

    /** 一个托管目标：联系人 × APP 聊天会话；[imPackage]=null 表示该联系人的全部 APP。 */
    data class Target(
        val contactId: Long,
        val imPackage: String?,
        val channelName: String?,
        val displayName: String,
    )

    /**
     * 托管组。[allContacts]=true 表示「全部联系人（仅通知拟稿，不自动发送）」，
     * 与 [targets] 互斥（全部联系人模式不进入串行调度器）。
     */
    data class Selection(
        val allContacts: Boolean = false,
        val targets: List<Target> = emptyList(),
    )

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _current = MutableStateFlow(readPersisted())
    val current: StateFlow<Selection> = _current.asStateFlow()

    fun save(selection: Selection) {
        prefs.edit()
            .putBoolean(KEY_ALL_CONTACTS, selection.allContacts)
            .putString(KEY_TARGETS, encode(selection.targets))
            .apply()
        _current.value = selection
    }

    fun clear() {
        prefs.edit().clear().apply()
        _current.value = Selection()
    }

    private fun readPersisted(): Selection {
        val allContacts = prefs.getBoolean(KEY_ALL_CONTACTS, false)
        val encoded = prefs.getString(KEY_TARGETS, null)
        if (encoded != null) {
            return Selection(allContacts = allContacts, targets = decode(encoded))
        }
        // 兼容迁移：旧白名单 → 联系人级托管目标（imPackage=null）。
        val legacyIds = advisorPrefs.state.value.autoWhitelistIds
        if (legacyIds.isEmpty()) return Selection()
        return Selection(
            targets = legacyIds.map { Target(it, null, null, "") },
        )
    }

    private fun encode(targets: List<Target>): String = targets.joinToString("\n") { target ->
        listOf(
            target.contactId.toString(),
            target.imPackage.orEmpty(),
            target.channelName.orEmpty(),
            target.displayName,
        ).joinToString(FIELD_SEP)
    }

    private fun decode(encoded: String): List<Target> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split('\n').mapNotNull { line ->
            val parts = line.split(FIELD_SEP)
            if (parts.size < 4) return@mapNotNull null
            val contactId = parts[0].toLongOrNull() ?: return@mapNotNull null
            Target(
                contactId = contactId,
                imPackage = parts[1].takeIf { it.isNotBlank() },
                channelName = parts[2].takeIf { it.isNotBlank() },
                displayName = parts.drop(3).joinToString(FIELD_SEP),
            )
        }
    }

    private companion object {
        const val PREFS_FILE = "advisor_auto_selection"
        const val KEY_ALL_CONTACTS = "all_contacts"
        const val KEY_TARGETS = "targets"

        /** ASCII Unit Separator：显示名/标题实际不会出现，避免与 `|`、换行冲突。 */
        const val FIELD_SEP = "\u001F"
    }
}
