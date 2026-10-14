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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.verlintas.baic2.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 无声音乐前台服务（永久档可选组件，默认关）：循环播放 1 秒静音音频，
 * 让进程保持完全唤醒、音频通路常驻。只允许：
 * - 设置页前台启动；
 * - 心跳（电池白名单/可见 overlay 豁免）或精确闹钟路径复活。
 *
 * 绝不从 BOOT 接收器启动——mediaPlayback 在 Android 15 BOOT 受限
 * FGS 类型清单内，启动即抛异常。
 */
@AndroidEntryPoint
class KeepAliveMusicService : Service() {

    @Inject
    lateinit var prefs: KeepAlivePrefs

    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel()
        startAsForeground()
        val mediaPlayer = runCatching { MediaPlayer.create(this, R.raw.silence) }.getOrNull()
        if (mediaPlayer == null) {
            // 资源解码失败：空跑前台服务毫无意义，立即撤销，等下一拍按规则复活。
            Log.w(TAG, "silence track unavailable, stopping")
            stopSelf()
            return
        }
        mediaPlayer.apply {
            isLooping = true
            setOnErrorListener { _, _, _ ->
                // 播放失败不崩溃：停掉自己，等下一拍按规则复活。
                stopSelf()
                true
            }
        }
        player = mediaPlayer
        runCatching { mediaPlayer.start() }
            .onFailure { Log.w(TAG, "silence playback start failed", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY 的系统重建也过这里：用户已关音乐/退出/降级时不得自行复活。
        val config = prefs.state.value
        if (!prefs.serviceDesired ||
            config.mode != KeepAlivePrefs.Mode.PERMANENT ||
            !config.silenceMusic
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { player?.release() }
        player = null
        isRunning = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.float_keepalive_music_notification_title))
            .setContentText(getString(R.string.float_keepalive_music_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.float_keepalive_music_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.float_keepalive_music_channel)
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                    setSound(null, null)
                },
            )
        }
    }

    companion object {
        private const val TAG = "KeepAliveMusic"
        private const val CHANNEL_ID = "keepalive_music"
        private const val NOTIFICATION_ID = 4202

        @JvmStatic
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
