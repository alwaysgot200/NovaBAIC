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

import android.content.Context
import androidx.room.Room
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.MIGRATION_2_3
import com.verlintas.baic2.floatball.advisor.data.MIGRATION_3_4
import com.verlintas.baic2.floatball.advisor.data.MIGRATION_4_5
import com.verlintas.baic2.floatball.advisor.data.MIGRATION_5_6
import com.verlintas.baic2.floatball.advisor.data.MIGRATION_6_7
import com.verlintas.baic2.floatball.advisor.data.MIGRATION_7_8
import com.verlintas.baic2.floatball.advisor.data.MIGRATION_8_9
import com.verlintas.baic2.floatball.advisor.data.MIGRATION_9_10
import com.verlintas.baic2.floatball.advisor.data.MIGRATION_10_11
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 老参谋私有库装配。独立 advisor.db，与上游 Baic2Database 无任何迁移耦合。
 */
@Module
@InstallIn(SingletonComponent::class)
object AdvisorModule {

    @Provides
    @Singleton
    fun provideAdvisorDatabase(@ApplicationContext context: Context): AdvisorDatabase =
        Room.databaseBuilder(context, AdvisorDatabase::class.java, "advisor.db")
            // 版本迁移必须显式声明：画像/绑定是用户数据，禁止任何静默清库兜底。
            // 漏写迁移应在开发期直接崩溃暴露，而不是上线后删用户数据。
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .build()
}
