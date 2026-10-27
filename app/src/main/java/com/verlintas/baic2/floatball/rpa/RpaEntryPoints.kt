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

package com.verlintas.baic2.floatball.rpa

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.verlintas.baic2.floatball.rpa.quickfind.RpaQuickFindActivity

/**
 * RPA 工具的入口门面：悬浮菜单只依赖这里，不 import 具体页面内部。
 *
 * - [design]：脚本库 / 拖拽编排；
 * - [quickFind]：以图找图的透明结果浮层（P3）；
 * - [startRun] / [stopRun]：后台运行（P4，`RpaRunService`）；
 * - 布局分析不是 Activity，由 `RpaAnalyzeOverlayController` 在服务侧直接挂层，
 *   故这里没有对应工厂。
 */
internal object RpaEntryPoints {

    /** 打开脚本库；[imPackage] 非空时只用于新建脚本时预填包名。 */
    fun design(context: Context, imPackage: String? = null): Intent =
        RpaActivity.intent(context, imPackage)

    /** 打开「以图找图」透明全屏浮层（无 a11y，帧源 Shizuku / 录屏）。 */
    fun quickFind(context: Context): Intent =
        Intent(context, RpaQuickFindActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 启动后台运行；脚本必须已经保存到 store（列表 / 编辑器点运行前先落盘）。 */
    fun startRun(context: Context, scriptId: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, RpaRunService::class.java)
                .setAction(RpaRunService.ACTION_START)
                .putExtra(RpaRunService.EXTRA_SCRIPT_ID, scriptId),
        )
    }

    /** 停止当前运行（通知栏与页面共用同一 action）。 */
    fun stopRun(context: Context) {
        context.startService(
            Intent(context, RpaRunService::class.java)
                .setAction(RpaRunService.ACTION_STOP),
        )
    }
}
