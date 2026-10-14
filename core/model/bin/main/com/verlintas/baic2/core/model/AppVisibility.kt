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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app currently has a visible foreground activity. Runs that end
 * while this is false fall back to a system notification instead of an
 * in-app dialog so the user still learns the outcome. Memory consolidation
 * also waits for the background edge - sleep-time replay, not mid-task.
 */
object AppVisibility {

    private val _foregroundFlow = MutableStateFlow(false)
    val foregroundFlow: StateFlow<Boolean> = _foregroundFlow.asStateFlow()

    @Volatile
    var foreground: Boolean = false
        set(value) {
            field = value
            _foregroundFlow.value = value
        }
}
