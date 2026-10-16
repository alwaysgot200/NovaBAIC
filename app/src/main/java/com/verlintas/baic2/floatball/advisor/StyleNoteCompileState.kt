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
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * customNote 慢车道编译的内存态（P3 §八）：记录编译失败的风格卡 id。
 *
 * 仅用于编辑页展示「理解失败，可重试/放弃」，不入库、不影响提示词；
 * 进程重启后草稿仍是 confirmed=0 且 compiledCustomJson 为空，编辑页按「理解中」展示，
 * 用户可手动点「重新理解」。
 */
@Singleton
class StyleNoteCompileState @Inject constructor() {

    private val _failed = MutableStateFlow(emptySet<Long>())
    val failed: StateFlow<Set<Long>> = _failed

    fun markFailed(cardId: Long) {
        _failed.update { it + cardId }
    }

    fun clear(cardId: Long) {
        _failed.update { it - cardId }
    }
}
