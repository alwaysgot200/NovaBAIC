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

package com.verlintas.baic2.floatball.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.FloatBallService

/**
 * 主页三键 → 悬浮球服务的启动器（方案 §5.1/§5.5）。
 *
 * 顺序不可颠倒：**先在前台发出 service action**（Android 12+ 后台启动 FGS 会被
 * 拒绝），再把主任务退到后台；服务侧等 `onStop` 握手确认自身不可见后才采集。
 */
object AdvisorHomeLauncher {

    fun startOneshot(activity: Activity) =
        launch(activity, FloatBallService.ACTION_ONESHOT_FROM_MAIN)

    fun startContinuous(activity: Activity) =
        launch(activity, FloatBallService.ACTION_CONTINUOUS_FROM_MAIN)

    fun startAuto(activity: Activity) =
        launch(activity, FloatBallService.ACTION_AUTO_FROM_MAIN)

    fun stopAuto(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatBallService::class.java)
                    .setAction(FloatBallService.ACTION_STOP_AUTO),
            )
        }.onFailure {
            Toast.makeText(context, R.string.float_home_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launch(activity: Activity, action: String) {
        val started = runCatching {
            ContextCompat.startForegroundService(
                activity,
                Intent(activity, FloatBallService::class.java).setAction(action),
            )
        }.isSuccess
        if (!started) {
            Toast.makeText(activity, R.string.float_home_launch_failed, Toast.LENGTH_SHORT).show()
            return
        }
        activity.window.decorView.post { activity.moveTaskToBack(true) }
    }
}
