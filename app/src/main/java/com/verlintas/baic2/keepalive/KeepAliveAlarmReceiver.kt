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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 精确闹钟链接收器（永久档）。「精确闹钟触发」是 targetSdk 36 下与
 * overlay 可见性无关的独立 FGS 启动豁免，因此在用户拒绝电池白名单的
 * Android 15+ 机器上，它是比 Job 心跳更可靠的拉活路径。
 */
@AndroidEntryPoint
class KeepAliveAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var manager: KeepAliveManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != KeepAliveManager.ACTION_HEARTBEAT_ALARM) return
        val pending = goAsync()
        Handler(Looper.getMainLooper()).post {
            try {
                manager.onAlarmFired()
            } catch (t: Throwable) {
                Log.w(TAG, "alarm handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "KeepAliveAlarm"
    }
}
