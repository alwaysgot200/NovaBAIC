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

package com.verlintas.baic2.keepalive

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 每 15 分钟一次的心跳（智能/永久档排程，persisted）。
 * 拉活判定与三级降级全部在 [KeepAliveManager]；涉及 WindowManager 的操作
 * 要求主线程，故整个任务投递到主线程执行，完成后立即 jobFinished——毫秒级轻活。
 */
@AndroidEntryPoint
class KeepAliveHeartbeatJob : JobService() {

    @Inject
    lateinit var manager: KeepAliveManager

    override fun onStartJob(params: JobParameters?): Boolean {
        Handler(Looper.getMainLooper()).post {
            try {
                manager.onHeartbeat()
            } catch (t: Throwable) {
                Log.w(TAG, "heartbeat failed", t)
            } finally {
                jobFinished(params, false)
            }
        }
        return true // 工作已在别处进行，由我们显式 jobFinished
    }

    override fun onStopJob(params: JobParameters?): Boolean = true // 请求重试排程

    private companion object {
        const val TAG = "KeepAliveHeartbeat"
    }
}
