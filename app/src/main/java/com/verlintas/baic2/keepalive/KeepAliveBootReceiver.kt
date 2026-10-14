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
 * 开机 / 应用更新 / 厂商快速开机后拉活悬浮球服务并重排当前档位的全部调度。
 *
 * BOOT_COMPLETED、MY_PACKAGE_REPLACED 是系统豁免广播，接收期内启动
 * specialUse FGS 合法（A15 的 BOOT 受限类型清单不含 specialUse）；
 * QUICKBOOT_POWERON 是厂商自定义广播、不享豁免，启动失败时静默降级为
 * 「等心跳/闹钟」，绝不崩溃。
 */
@AndroidEntryPoint
class KeepAliveBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var manager: KeepAliveManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Handler(Looper.getMainLooper()).post {
            try {
                manager.onBootCompleted()
            } catch (t: Throwable) {
                // 保活组件自身故障绝不能升级成进程崩溃。
                Log.w(TAG, "boot handling failed: ${intent.action}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "KeepAliveBoot"
    }
}
