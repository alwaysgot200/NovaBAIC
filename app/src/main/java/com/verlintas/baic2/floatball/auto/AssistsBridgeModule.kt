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

import com.verlintas.baic2.device.api.AccessibilityBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * R1 §17.1 换绑：[AccessibilityBridge] 指向 assists 换源实现。
 * 原绑定在 device/impl 的 DeviceImplModule（已移除）——实现类在 app 模块，
 * device/impl 不依赖 app，故绑定随实现走（同模块放 [upstream-touch] 最小化）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AssistsBridgeModule {

    @Binds
    @Singleton
    abstract fun bindAccessibilityBridge(impl: AssistsA11yBridge): AccessibilityBridge
}
