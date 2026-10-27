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
import com.verlintas.rpa.model.RpaPickRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * 拾取请求协调器（方案 §7.8）：
 *
 * 编辑器 arm 一份请求（脚本 / 步骤 / 线路），工具读请求、显示拾取横幅，
 * 用户点「应用到当前步骤」后**直接写回 [com.verlintas.rpa.store.RpaScriptStore]**
 * 并重新拉起编辑器定位到该步骤。
 *
 * 与文档的偏差（有意简化）：不额外落 `RpaPickResult` 文件，工具直接改 store，
 * 避免「结果文件 → Activity 消费 → 再写 store」的两段式失败面；请求本身仍写
 * cache 目录，进程被杀后重新打开工具仍能恢复横幅。
 */
@Singleton
class RpaPickCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cached: RpaPickRequest? = null

    @Synchronized
    fun arm(request: RpaPickRequest) {
        cached = request
        runCatching {
            val dir = pickDir() ?: return
            dir.mkdirs()
            requestFile(dir).writeText(json.encodeToString(RpaPickRequest.serializer(), request))
        }
    }

    /** 当前有效请求；过期或无请求返回 null，并顺手清掉过期文件。 */
    @Synchronized
    fun current(now: Long = System.currentTimeMillis()): RpaPickRequest? {
        val value = cached ?: loadFromDisk()
        if (value == null) return null
        if (value.isExpired(now)) {
            cancel()
            return null
        }
        cached = value
        return value
    }

    @Synchronized
    fun cancel() {
        cached = null
        runCatching { pickDir()?.let { requestFile(it).delete() } }
    }

    private fun loadFromDisk(): RpaPickRequest? = runCatching {
        val dir = pickDir() ?: return null
        val file = requestFile(dir)
        if (!file.isFile) return null
        json.decodeFromString(RpaPickRequest.serializer(), file.readText())
    }.getOrNull()

    private fun pickDir(): File? = context.cacheDir?.let { File(it, PICK_DIR) }

    private fun requestFile(dir: File): File = File(dir, REQUEST_FILE)

    private companion object {
        const val PICK_DIR = "rpa/pick"
        const val REQUEST_FILE = "request.json"
    }
}
