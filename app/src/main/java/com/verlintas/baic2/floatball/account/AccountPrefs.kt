/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.account

import android.content.Context
import com.verlintas.baic2.core.data.security.SecretCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 好参谋账号会话与卡密的本地持久化（floatball 私有 SharedPreferences）。
 *
 * - 会话 = 服务端签发的 access/refresh token + 过期时刻；token 与卡密经
 *   [SecretCipher]（Android Keystore）加密落盘，明文不进磁盘；
 * - 过期即视为未登录（P0 不做静默续期，重新登录即可）；
 * - 不碰上游 SettingsRepository（好参谋专属项纪律）。
 */
@Singleton
class AccountPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SecretCipher,
) {

    /** 登录会话；[expireAtMs] 到点即失效。 */
    data class Session(
        val username: String,
        val accessToken: String,
        val refreshToken: String?,
        val expireAtMs: Long,
    )

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** 读取有效会话；无记录或已过期返回 null。 */
    fun loadSession(): Session? {
        val token = decrypt(KEY_ACCESS_TOKEN) ?: return null
        val expireAtMs = prefs.getLong(KEY_EXPIRE_AT, 0L)
        if (expireAtMs <= System.currentTimeMillis()) return null
        return Session(
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            accessToken = token,
            refreshToken = decrypt(KEY_REFRESH_TOKEN),
            expireAtMs = expireAtMs,
        )
    }

    fun saveSession(session: Session) {
        prefs.edit()
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_ACCESS_TOKEN, cipher.encrypt(session.accessToken))
            .putString(KEY_REFRESH_TOKEN, session.refreshToken?.let(cipher::encrypt))
            .putLong(KEY_EXPIRE_AT, session.expireAtMs)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRE_AT)
            .apply()
    }

    /**
     * 登录过的时间戳（非 0 表示本机完成过登录）。
     * 装包登录门只认“本机是否登录过”：全新安装先登录；覆盖升级保留登录态。
     */
    fun loadInstallGateTime(): Long = prefs.getLong(KEY_INSTALL_GATE_TIME, 0L)

    fun saveInstallGateTime(lastUpdateTime: Long) {
        prefs.edit().putLong(KEY_INSTALL_GATE_TIME, lastUpdateTime).apply()
    }

    fun saveCardKey(cardKey: String) {
        prefs.edit().putString(KEY_CARD_KEY, cipher.encrypt(cardKey)).apply()
    }

    /** 读取当前绑定卡密明文；未绑卡或解密失败返回 null。 */
    fun loadCardKey(): String? = decrypt(KEY_CARD_KEY)

    fun clearCardKey() {
        prefs.edit().remove(KEY_CARD_KEY).apply()
    }

    /**
     * 钱包/卡密最近一次成功拉取的快照（离线兜底展示，见 [KEY_WALLET_SNAPSHOT]）。
     * 与登录态同源加密落盘；换号登录或退出登录时由 [clearSnapshots] 清空。
     */
    fun saveWalletSnapshot(json: String) {
        prefs.edit().putString(KEY_WALLET_SNAPSHOT, cipher.encrypt(json)).apply()
    }

    fun loadWalletSnapshot(): String? = decrypt(KEY_WALLET_SNAPSHOT)

    fun saveCardsSnapshot(json: String) {
        prefs.edit().putString(KEY_CARDS_SNAPSHOT, cipher.encrypt(json)).apply()
    }

    fun loadCardsSnapshot(): String? = decrypt(KEY_CARDS_SNAPSHOT)

    /** 清空离线快照：防止上一账号的余额/卡密被新账号看到。 */
    fun clearSnapshots() {
        prefs.edit()
            .remove(KEY_WALLET_SNAPSHOT)
            .remove(KEY_CARDS_SNAPSHOT)
            .apply()
    }

    private fun decrypt(key: String): String? =
        prefs.getString(key, null)?.let { cipher.decrypt(it).getOrNull() }

    private companion object {
        const val PREFS_FILE = "float_account"
        const val KEY_USERNAME = "username"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRE_AT = "expire_at_ms"
        const val KEY_CARD_KEY = "card_key"
        const val KEY_WALLET_SNAPSHOT = "wallet_snapshot"
        const val KEY_CARDS_SNAPSHOT = "cards_snapshot"
        const val KEY_INSTALL_GATE_TIME = "install_gate_time"
    }
}
