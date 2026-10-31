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
import com.verlintas.baic2.floatball.FloatBallService
import com.verlintas.baic2.floatball.rpa.quickfind.RpaQuickFindActivity
import com.verlintas.rpa.model.RpaLine

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

    /**
     * 拾取武装后直拉工具面（§7.9 排矛盾）：交给 `FloatBallService` 等前台让出本 App
     * 后自动打开布局分析 / 以图找图，**不经悬浮球菜单**（工具入口未来可移除）。
     */
    fun openPickTool(context: Context) {
        context.startService(
            Intent(context, FloatBallService::class.java)
                .setAction(FloatBallService.ACTION_RPA_PICK_OPEN_TOOL),
        )
    }

    /**
     * RPA 模式门「重试连接」（§7.4 双态报因）：交给 `FloatBallService` 自愈重绑
     * 无障碍服务——与布局分析「重试连接」同一实现（不另立），绑定仍失败由服务侧
     * toast 显式报因，不静默。
     */
    fun retryA11yBinding(context: Context) {
        context.startService(
            Intent(context, FloatBallService::class.java)
                .setAction(FloatBallService.ACTION_RPA_A11Y_RETRY),
        )
    }

    /**
     * 启动后台运行；脚本必须已经保存到 store（列表 / 编辑器点运行前先落盘）。
     * [forceLine] 非空即锁线运行（运行模式门选定的线路），null 为脚本自动选线。
     */
    fun startRun(context: Context, scriptId: String, forceLine: RpaLine? = null) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, RpaRunService::class.java)
                .setAction(RpaRunService.ACTION_START)
                .putExtra(RpaRunService.EXTRA_SCRIPT_ID, scriptId)
                .putExtra(RpaRunService.EXTRA_FORCE_LINE, forceLine?.name),
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
