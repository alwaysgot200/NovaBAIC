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

package com.verlintas.baic2.floatball.notify

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * POST_NOTIFICATIONS（Android 13+ 运行时权限）的统一发送守卫。
 *
 * 好参谋的各路通知（起草降级/陪伴窗口汇总/主动保温/周报）都是可降级
 * 副产物：缺权限时静默跳过，绝不阻塞主链路。渠道创建与
 * areNotificationsEnabled 健全性检查仍归调用方，这里只做权限判定
 * 与最终 notify——Android 13+ 未授予时直接返回 false，lint 以
 * checkSelfPermission 分支满足 MissingPermission。
 */
object NotifyGate {

    private const val TAG = "NotifyGate"

    /** @return 是否真正发出了通知（false = 权限缺失或发送异常）。 */
    fun notifyIfPermitted(context: Context, id: Int, notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { Log.d(TAG, "notify failed", it) }
            .isSuccess
    }
}
