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

package com.verlintas.baic2.floatball.auto

import android.content.Intent
import android.widget.Toast
import com.verlintas.baic2.device.api.TextNode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 画像页「关联会话」的 IM 直达导航器：拉起 IM → a11y 点搜索 → 输入联系人名 →
 * 点进具体聊天页（双模铁律 §4.5：无 a11y 时失败返回明确原因码，由 UI 层
 * Toast 告知用户并指引手动操作，绝不静默失效）。
 *
 * 流程各步全部轮询等待界面就绪（微信启动慢/转场动画），任一步超时返回
 * REASON_* 码——微信已被拉起的失败（搜索按钮没找到/联系人没搜到等）仍算
 * 「打开了 App」，UI 文案提示用户手动点进联系人即可。
 */
@Singleton
class ImChatNavigator @Inject constructor(
    private val bridge: AssistsA11yBridge,
    @ApplicationContext private val context: android.content.Context,
) {

    sealed interface NavResult {
        data object Ok : NavResult

        /** reason 为 REASON_* 常量，UI 层映射用户文案。 */
        data class Fail(val reason: String) : NavResult
    }

    companion object {
        /** 无障碍服务未开启（双模铁律：必须显式提示，不许静默只开 App）。 */
        const val REASON_A11Y_OFF = "im_nav_a11y_off"

        /** 目标 IM 未安装或无法拉起。 */
        const val REASON_APP_MISSING = "im_nav_app_missing"

        /** IM 启动后迟迟未到前台。 */
        const val REASON_LAUNCH_TIMEOUT = "im_nav_launch_timeout"

        /** 主界面未找到搜索入口。 */
        const val REASON_SEARCH_BUTTON_NOT_FOUND = "im_nav_search_button_not_found"

        /** 搜索输入失败（聚焦/注入均拒绝）。 */
        const val REASON_INPUT_FAILED = "im_nav_input_failed"

        /** 搜索结果中没有该联系人。 */
        const val REASON_CONTACT_NOT_FOUND = "im_nav_contact_not_found"

        /** 结果节点点击失败。 */
        const val REASON_TAP_FAILED = "im_nav_tap_failed"

        private const val POLL_INTERVAL_MS = 300L
        private const val FOREGROUND_POLLS = 15
        private const val NODE_POLLS = 6
        private const val POST_TAP_DELAY_MS = 800L
        private const val POST_INPUT_DELAY_MS = 1_000L

        /** 结果节点最低 top：排除顶部搜索输入框自身（其 text 即关键词）。 */
        private const val RESULT_MIN_TOP_RATIO = 6f
    }

    /**
     * 打开 [imPackage] 中与 [contactName] 的聊天页。主线程之外执行（IO），
     * a11y 手势/tap 由 [AssistsA11yBridge] 内部调度到主线程。
     */
    suspend fun openChat(imPackage: String, contactName: String): NavResult =
        withContext(Dispatchers.IO) {
            val name = contactName.trim()
            if (name.isEmpty()) return@withContext NavResult.Fail(REASON_CONTACT_NOT_FOUND)

            // ① a11y 前置（§4.5 硬前置显式提示）：未开启直接报因，不瞎跑。
            if (!bridge.connected.value) {
                return@withContext NavResult.Fail(REASON_A11Y_OFF)
            }

            // ② 已在该联系人的聊天页？标题即联系人名，直接成功（重复点击场景）。
            if (bridge.foregroundPackage() == imPackage &&
                bridge.windowTitle()?.contains(name) == true
            ) {
                return@withContext NavResult.Ok
            }

            // ③ 拉起 IM 并等前台。
            val alreadyForeground = bridge.foregroundPackage() == imPackage
            if (!alreadyForeground) {
                val launch = runCatching {
                    context.packageManager.getLaunchIntentForPackage(imPackage)
                }.getOrNull() ?: return@withContext NavResult.Fail(REASON_APP_MISSING)
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(launch) }
                    .onFailure { return@withContext NavResult.Fail(REASON_APP_MISSING) }
                if (!awaitForeground(imPackage)) {
                    return@withContext NavResult.Fail(REASON_LAUNCH_TIMEOUT)
                }
            }

            // ④ 若拉起后已在聊天页（微信停在聊天 Tab 上次的聊天），直接成功。
            if (bridge.windowTitle()?.contains(name) == true) {
                return@withContext NavResult.Ok
            }

            // ⑤ 点搜索入口（主界面顶部搜索框 / 右上角放大镜，文本或描述均为「搜索」）。
            val searchEntry = awaitNode("搜索") ?: run {
                // 当前可能停在别的聊天页：先返回主界面再找搜索。
                runCatching { bridge.pressKey("back") }
                delay(POST_TAP_DELAY_MS)
                awaitNode("搜索")
            } ?: return@withContext NavResult.Fail(REASON_SEARCH_BUTTON_NOT_FOUND)
            if (bridge.tap(searchEntry.centerX, searchEntry.centerY).isFailure) {
                return@withContext NavResult.Fail(REASON_TAP_FAILED)
            }
            delay(POST_TAP_DELAY_MS)

            // ⑥ 聚焦输入框并输入联系人名。
            bridge.focusFirstEditable()
            if (bridge.typeText(name).isFailure) {
                return@withContext NavResult.Fail(REASON_INPUT_FAILED)
            }
            delay(POST_INPUT_DELAY_MS)

            // ⑦ 点搜索结果里的联系人（排除顶部输入框自身：取 top 足够大的首个节点）。
            val hit = awaitContactResult(name)
                ?: return@withContext NavResult.Fail(REASON_CONTACT_NOT_FOUND)
            if (bridge.tap(hit.centerX, hit.centerY).isFailure) {
                return@withContext NavResult.Fail(REASON_TAP_FAILED)
            }
            delay(POST_TAP_DELAY_MS)
            NavResult.Ok
        }

    /** 便捷 Toast：结果码 → 用户文案（画像页调用方用，避免每处手写 when）。 */
    fun toastResult(result: NavResult, contactName: String) {
        val res = when (result) {
            is NavResult.Ok -> com.verlintas.baic2.R.string.float_im_nav_ok
            is NavResult.Fail -> when (result.reason) {
                REASON_A11Y_OFF -> com.verlintas.baic2.R.string.float_im_nav_a11y_off
                REASON_APP_MISSING -> com.verlintas.baic2.R.string.float_im_nav_app_missing
                else -> com.verlintas.baic2.R.string.float_im_nav_generic_fail
            }
        }
        val message = context.getString(res, contactName)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private suspend fun awaitForeground(pkg: String): Boolean {
        repeat(FOREGROUND_POLLS) {
            delay(POLL_INTERVAL_MS)
            if (bridge.foregroundPackage() == pkg) return true
        }
        return false
    }

    /** 轮询找文本节点（等待界面转场补齐节点）。 */
    private suspend fun awaitNode(query: String): TextNode? {
        repeat(NODE_POLLS) {
            bridge.findText(query).firstOrNull()?.let { return it }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    /** 搜索结果节点：排除顶部输入框（其 text=关键词、top 小）；无结果返回 null。 */
    private suspend fun awaitContactResult(name: String): TextNode? {
        repeat(NODE_POLLS) {
            val minTop = (bridge.screenSize()?.second ?: 0) / RESULT_MIN_TOP_RATIO
            val hit = bridge.findText(name).firstOrNull { it.top > minTop }
            if (hit != null) return hit
            delay(POLL_INTERVAL_MS)
        }
        return null
    }
}
