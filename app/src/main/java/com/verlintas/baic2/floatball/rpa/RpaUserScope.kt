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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import android.content.Context
import android.util.Log
import java.io.File

/**
 * RPA 脚本库的用户作用域（多产品方案 §4.10.4）：
 * 登录后根目录切到 `filesDir/rpa/users/<userId>`，不同账号互不可见；
 * 旧版单机库 `filesDir/rpa` 在首个登录账号下一次性迁移过去（重命名优先，
 * 跨卷失败退回递归复制）。
 *
 * 用普通对象而不是 Hilt 依赖：`RpaModule` 的 store 提供者注入
 * `AccountManager`，若再由 `AccountManager` 反向注入 store 会形成循环。
 */
object RpaUserScope {

    private const val LEGACY_DIR = "rpa"
    private const val USERS_DIR = "users"
    private const val TAG = "RpaUserScope"

    @Volatile
    private var userId: Long = 0L

    /** 当前作用域用户（0 = 未登录，沿用旧库目录，仅登录门之外的兜底）。 */
    fun currentUserId(): Long = userId

    /** 切换账号作用域；正数用户首次进入时先迁移旧库。 */
    @Synchronized
    fun switchTo(context: Context, newUserId: Long) {
        val safe = newUserId.coerceAtLeast(0L)
        if (safe > 0 && !migrateLegacy(context.applicationContext, safe)) {
            // 迁移没走完不打断登录：旧库原样保留（moveDir 失败不删源目录），只留痕排查。
            Log.w(TAG, "rpa legacy migration incomplete, source library kept")
        }
        userId = safe
    }

    /** 当前根目录（动态提供者每次访问都取它）。 */
    fun root(context: Context): File {
        val current = userId
        val legacy = File(context.applicationContext.filesDir, LEGACY_DIR)
        return if (current > 0) File(File(legacy, USERS_DIR), current.toString()) else legacy
    }

    private fun migrateLegacy(context: Context, targetUserId: Long): Boolean {
        val legacy = File(context.filesDir, LEGACY_DIR)
        val target = File(File(legacy, USERS_DIR), targetUserId.toString())
        if (target.exists()) return true
        val scripts = File(legacy, "scripts")
        val templates = File(legacy, "templates")
        val hasScripts = scripts.listFiles()?.isNotEmpty() == true
        val hasTemplates = templates.listFiles()?.isNotEmpty() == true
        if (!hasScripts && !hasTemplates) {
            runCatching { target.mkdirs() }
            return true
        }
        runCatching { target.mkdirs() }
        // 两个目录互相独立：各自失败都不丢数据，任一失败整体按失败上报。
        val scriptsMoved = moveDir(scripts, File(target, "scripts"))
        val templatesMoved = moveDir(templates, File(target, "templates"))
        return scriptsMoved && templatesMoved
    }

    /**
     * 目录迁移（重命名失败后的复制回退）：**全部**子项复制成功才删除 [from]；
     * 任一子项失败立即中止并返回 false——[from] 与已拷贝进 [to] 的内容原样保留，
     * 宁可留一份重复也不丢脚本（存储满/IO 失败时曾把没拷走的旧目录删掉）。
     */
    private fun moveDir(from: File, to: File): Boolean {
        if (!from.isDirectory) return true
        if (!to.exists() && from.renameTo(to)) return true
        runCatching { to.mkdirs() }
        // 列不出子项 = 读取失败，同样不许删旧目录。
        val children = from.listFiles() ?: return false
        for (child in children) {
            val dest = File(to, child.name)
            val copied = runCatching {
                if (child.isDirectory) {
                    child.copyRecursively(dest, overwrite = true)
                } else {
                    child.copyTo(dest, overwrite = true)
                }
            }.isSuccess
            if (!copied) return false
        }
        runCatching { from.deleteRecursively() }
        return true
    }
}
