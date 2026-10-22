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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import com.verlintas.baic2.device.api.OcrLine
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.device.api.TextNode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 画像页「关联会话」的 IM 直达导航器（双模铁律 §4.5）：
 *
 * - 模式 1（a11y 开）：拉起 IM → a11y 点搜索 → 输入联系人名 → 点进具体聊天页；
 * - 模式 2（a11y 关）：Shizuku shell 注入（tap/剪贴板粘贴）+ 静默 MediaProjection
 *   投影抽帧 + OCR 验证 + 以图找图——完整的第二套执行面（openChatByVision）。
 *
 * 两套执行面都不可用（a11y 关且 Shizuku 未就绪）时失败返回明确原因码，
 * 由 UI 层 Toast 告知用户并指引手动操作，绝不静默失效。
 *
 * 流程各步全部轮询等待界面就绪（微信启动慢/转场动画），任一步超时返回
 * REASON_* 码——微信已被拉起的失败（搜索按钮没找到/联系人没搜到等）仍算
 * 「打开了 App」，UI 文案提示用户手动点进联系人即可。
 */
@Singleton
class ImChatNavigator @Inject constructor(
    private val bridge: AssistsA11yBridge,
    private val shizuku: ShizukuInjector,
    private val ocr: OcrProvider,
    private val frameSource: FrameSource,
    private val screenCapture: ScreenCapture,
    private val imageMatcher: ImageMatcher,
    @ApplicationContext private val context: Context,
) {

    sealed interface NavResult {
        data object Ok : NavResult

        /** reason 为 REASON_* 常量，UI 层映射用户文案。 */
        data class Fail(val reason: String) : NavResult
    }

    companion object {
        private const val TAG = "ImChatNavigator"

        /** 无障碍服务未开启且 Shizuku 未就绪（双模执行面均不可用，显式提示）。 */
        const val REASON_A11Y_OFF = "im_nav_a11y_off"

        /** a11y 关且 Shizuku 通道不可用：两套执行面都没有，须指引任开其一。 */
        const val REASON_SHIZUKU_OFF = "im_nav_shizuku_off"

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

        /** 静默投影建立失败（模式 2 感知面无源，须引导用户检查 Shizuku/重试）。 */
        const val REASON_PROJECTION_UNAVAILABLE = "im_nav_projection_unavailable"

        private const val POLL_INTERVAL_MS = 300L
        private const val FOREGROUND_POLLS = 15
        private const val NODE_POLLS = 6
        private const val RESULT_POLLS = 12
        private const val POST_TAP_DELAY_MS = 800L
        private const val POST_INPUT_DELAY_MS = 1_200L

        /** ⑥ 聚焦+注入的重试次数（搜索页转场慢，no_focused_input 首发常见）。 */
        private const val INPUT_ATTEMPTS = 3

        /** ⑤ back 回主列表的最大轮次（覆盖 聊天页→主列表 一层；多无害）。 */
        private const val SEARCH_ROUNDS = 2

        /** ⑤ 等输入框聚焦的轮询次数（搜索页转场+键盘弹起 1-2s）。 */
        private const val INPUT_FOCUS_POLLS = 5

        /** ⑦ 多跳循环上限：第一跳 IME 候选上屏，第二跳结果条目进聊天页。 */
        private const val RESULT_HOPS = 3

        /** 结果节点最低 top：排除顶部搜索输入框自身（其 text 即关键词）。 */
        private const val RESULT_MIN_TOP_RATIO = 6f

        /** 微信 8.x 主列表右上角搜索图标（纯图标无文本节点）的屏幕比例位置。 */
        private const val SEARCH_ICON_X_RATIO = 0.825f
        private const val SEARCH_ICON_Y_RATIO = 0.074f

        /** 微信 FTS 搜索页输入框（对 a11y 不暴露，只能坐标 tap）的比例位置。 */
        private const val FTS_INPUT_X_RATIO = 0.46f
        private const val FTS_INPUT_Y_RATIO = 0.074f

        /** ⑥-1 clearInput 后的落定等待（Ctrl+A+DEL 到 PASTE 之间）。 */
        private const val CLEAR_SETTLE_MS = 300L

        /**
         * ⑦ 坐标兜底：FTS 结果页「联系人」section 首条目的 y 比例——搜索框
         * 正下方首个 section 即联系人，全名精确搜索首条目即目标（绿字中心
         * y=484/2460≈0.197，2026-10-21 真机像素实测）。
         */
        private const val RESULT_ENTRY_Y_RATIO = 0.197f

        /** ⑧ 视觉验证的顶部条高度比例（聊天页头部/FTS 搜索框都在此带内）。 */
        private const val HEADER_STRIP_RATIO = 0.12f

        /** CJK 统一表意文字码位区间（⑧ 名字匹配的中文段提取）。 */
        private val CJK_RANGE = 0x4E00..0x9FFF

        /** 中文段匹配的最短名字长度（低于此长度太易误命中，回退精确匹配）。 */
        private const val CJK_MIN = 2

        /** 前缀匹配阈值：公共前缀 ≥ 名长的该比例即算命中（OCR 漏尾缀容错）。 */
        private const val PREFIX_MATCH_RATIO = 0.67f

        /** 模式 2 ⑤ 搜索图标找图模板（assets/rpa_templates/search.png）。 */
        private const val TEMPLATE_SEARCH = "search"

        /** 模式 2 ⑤ 找图轮询次数（冷启动主列表图标渲染慢，miss 重试）。 */
        private const val IMAGE_FIND_POLLS = 3

        /** 模式 2 ⑤-1 归位 back 上限（微信 task 残留搜索页/聊天页多层）。 */
        private const val BACK_ROUNDS = 3

        /** 模式 2 ⑥-0 后的键盘弹起稳定等待（FTS 转场+IME 就绪，800ms 实测不足）。 */
        private const val FTS_INPUT_SETTLE_MS = 1_500L

        /** 模式 2 ⑦-0 搜索结果渲染轮询次数（paste 上屏→FTS 联想刷新为异步）。 */
        private const val RESULT_RENDER_POLLS = 6
    }

    /**
     * 打开 [imPackage] 中与 [contactName] 的聊天页。主线程之外执行（IO），
     * a11y 手势/tap 由 [AssistsA11yBridge] 内部调度到主线程。
     */
    suspend fun openChat(imPackage: String, contactName: String): NavResult =
        withContext(Dispatchers.IO) {
            val name = contactName.trim()
            if (name.isEmpty()) return@withContext NavResult.Fail(REASON_CONTACT_NOT_FOUND)
            Log.i(TAG, "openChat pkg=$imPackage name=$name a11y=${bridge.connected.value}")

            // ① 双模门面（§4.5 执行面双模铁律）：a11y 关不是失败，而是切换到
            // 视觉通道（Shizuku 注入 + 静默投影抽帧 + OCR + 以图找图）；只有
            // 两套执行面都不可用时才报因（尽力拉起 App，用户至少能手动点进
            // 联系人），绝不静默无动作。
            if (!bridge.connected.value) {
                if (shizuku.available) {
                    return@withContext openChatByVision(imPackage, name)
                }
                Log.w(TAG, "① a11y off & shizuku unavailable (${shizuku.unavailableReason})")
                launchBestEffort(imPackage)
                return@withContext NavResult.Fail(REASON_SHIZUKU_OFF)
            }

            // ② 已在该联系人的聊天页？标题即联系人名，直接成功（重复点击场景）。
            val titleNow = bridge.windowTitle()
            Log.i(TAG, "② fg=${bridge.foregroundPackage()} title=$titleNow")
            if (bridge.foregroundPackage() == imPackage && titleMatches(titleNow, name)) {
                return@withContext NavResult.Ok
            }

            // ②½ 提前把联系人名写进系统剪贴板：此刻自家仍是焦点应用（IM 尚未
            // 拉起），写入合法；Android 10+ 后台进程写剪贴板会被静默拒绝，
            // IM 前台后无法补写。⑥-2 的 shell KEYCODE_PASTE 注入依赖这份数据
            // ——由 IM（焦点应用）在粘贴时读取。剪贴板被导航占用（后台无法
            // 恢复原内容，不恢复）。
            val clipPrimed = runCatching {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("baic2", name))
                clipboard != null
            }.getOrDefault(false)
            Log.i(TAG, "②½ clipboard primed=$clipPrimed")

            // ③ 拉起 IM 并等前台。
            val alreadyForeground = bridge.foregroundPackage() == imPackage
            Log.i(TAG, "③ launch alreadyForeground=$alreadyForeground")
            if (!alreadyForeground) {
                val launch = runCatching {
                    context.packageManager.getLaunchIntentForPackage(imPackage)
                }.getOrNull() ?: run {
                    Log.w(TAG, "③ launch intent null (app missing)")
                    return@withContext NavResult.Fail(REASON_APP_MISSING)
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(launch) }
                    .onFailure {
                        Log.w(TAG, "③ startActivity failed", it)
                        return@withContext NavResult.Fail(REASON_APP_MISSING)
                    }
                if (!awaitForeground(imPackage)) {
                    Log.w(TAG, "③ awaitForeground timeout fg=${bridge.foregroundPackage()}")
                    return@withContext NavResult.Fail(REASON_LAUNCH_TIMEOUT)
                }
            }

            // ④ 若拉起后已在聊天页（微信停在聊天 Tab 上次的聊天），直接成功。
            val title4 = bridge.windowTitle()
            Log.i(TAG, "④ title=$title4")
            if (titleMatches(title4, name)) {
                return@withContext NavResult.Ok
            }

            // ④½ 快捷路径：主列表里直接点该联系人的会话条目（「刚聊过」最常见
            // 场景，跳过搜索全流程）。条目节点 text 即联系人名（findText 包含匹配）。
            val listHit = awaitNode(name)
            if (listHit != null && bridge.tap(listHit.centerX, listHit.centerY).isSuccess) {
                delay(POST_TAP_DELAY_MS)
                val titleFast = bridge.windowTitle()
                Log.i(TAG, "④½ list hit (${listHit.centerX},${listHit.centerY}) title=$titleFast")
                if (titleMatches(titleFast, name)) {
                    return@withContext NavResult.Ok
                }
                // 点了没进聊天页（可能是列表项误报）：退回主列表再走搜索。
                runCatching { bridge.pressKey("back") }
                delay(POST_TAP_DELAY_MS)
            }

            // ⑤ 进搜索页：微信拉起后恢复上次停留页（主列表/聊天页/搜索页三态，
            // 2026-10-21 真机实测）。搜索页输入框自动聚焦（键盘弹起）——
            // inputFocused 即已就绪直接输入；否则 back 回主列表（聊天页→主列表；
            // 主列表→「再按退出」不真退；搜索页等独立 task 页→微信退后台需重拉起）。
            // 循环后仍未就绪 → 点搜索入口（节点优先 + 坐标兜底，主列表 a11y 树
            // 对无障碍客户端不可见，坐标兜底是实际主路径）。
            var searchReady = false
            for (round in 1..SEARCH_ROUNDS) {
                if (bridge.foregroundPackage() != imPackage) {
                    Log.i(TAG, "⑤ im backgrounded, relaunch…")
                    if (!relaunch(imPackage)) {
                        Log.w(TAG, "⑤ relaunch timeout")
                        return@withContext NavResult.Fail(REASON_LAUNCH_TIMEOUT)
                    }
                }
                if (awaitInputFocused()) {
                    searchReady = true
                    break
                }
                runCatching { bridge.pressKey("back") }
                delay(POST_TAP_DELAY_MS)
                Log.i(TAG, "⑤ back round=$round, fg=${bridge.foregroundPackage()} title=${bridge.windowTitle()}")
                // ⑤½ 主列表条目直达：back 天然回到 IM 主列表，最近聊过的联系人
                // 就在列表里，条目节点 text 即联系人名（findText 空白无关匹配）——
                // 命中直接点进聊天页，绕过 FTS 搜索+输入全链（最稳路径，
                // 不依赖任何注入通道）。只在 IM 仍前台时扫描——back 可能把 IM
                // 退到后台（主列表 back=退出），此时屏上是自家 App，名字节点
                // 全是误报（实测 tap 到自家显示名输入框）。误报点进了别的页则
                // back 回列表继续。
                if (bridge.foregroundPackage() == imPackage) {
                    val listHit = awaitNode(name)
                    if (listHit != null && bridge.tap(listHit.centerX, listHit.centerY).isSuccess) {
                        delay(POST_TAP_DELAY_MS)
                        val t5 = bridge.windowTitle()
                        Log.i(TAG, "⑤½ list hit (${listHit.centerX},${listHit.centerY}) title=$t5")
                        if (titleMatches(t5, name)) {
                            return@withContext NavResult.Ok
                        }
                        runCatching { bridge.pressKey("back") }
                        delay(POST_TAP_DELAY_MS)
                    }
                }
            }
            if (!searchReady) {
                // 循环耗尽仍未就绪：back 可能把 IM 退到了后台（独立 task 页场景），
                // 先确保 IM 前台——relaunch 恢复的若是搜索页，输入框聚焦即就绪。
                if (bridge.foregroundPackage() != imPackage) {
                    Log.i(TAG, "⑤ post-rounds relaunch…")
                    if (!relaunch(imPackage)) {
                        Log.w(TAG, "⑤ post-rounds relaunch timeout")
                        return@withContext NavResult.Fail(REASON_LAUNCH_TIMEOUT)
                    }
                    if (awaitInputFocused()) searchReady = true
                }
            }
            if (!searchReady) {
                var searchTapped = false
                awaitNode("搜索")?.let { node ->
                    Log.i(TAG, "⑤ searchEntry=(${node.centerX},${node.centerY}) tap…")
                    searchTapped = bridge.tap(node.centerX, node.centerY).isSuccess
                }
                if (!searchTapped) {
                    val size = bridge.screenSize()
                    if (size == null) {
                        Log.w(TAG, "⑤ screenSize null, abort")
                        return@withContext NavResult.Fail(REASON_SEARCH_BUTTON_NOT_FOUND)
                    }
                    val sx = (size.first * SEARCH_ICON_X_RATIO).toInt()
                    val sy = (size.second * SEARCH_ICON_Y_RATIO).toInt()
                    Log.i(TAG, "⑤ node miss, tap search icon by ratio ($sx,$sy)")
                    searchTapped = bridge.tap(sx, sy).isSuccess
                }
                if (!searchTapped) {
                    Log.w(TAG, "⑤ tap search FAILED")
                    return@withContext NavResult.Fail(REASON_TAP_FAILED)
                }
                delay(POST_TAP_DELAY_MS)
            }

            // ⑥ 输入联系人名（三档通道）：
            // ⑥-1 Shizuku KEYCODE_PASTE（首选）：shell `input keyevent 279` 派发
            //   系统粘贴键到焦点窗口，由 IM（焦点应用）读剪贴板（②½ 已提前写
            //   入名字）——系统级注入，绕开微信 FTS 输入框的 a11y 黑洞
            //   （SET_TEXT/PASTE 打幽灵节点全被吞）与 IME 模式差异（讯飞随写
            //   模式无候选词栏、搜狗剪贴板联想不稳定，2026-10-21 真机实测）。
            // ⑥-2 a11y typeText（Shizuku 不可用时兜底，FTS 黑洞下大概率失败）。
            // 输入前防呆——必须仍在 IM 前台：back 循环若把微信退后台而流程
            // 未察觉，注入会落进自家 App 的输入框改用户数据（实测教训）。
            if (bridge.foregroundPackage() != imPackage) {
                Log.w(TAG, "⑥ not in IM foreground, abort")
                return@withContext NavResult.Fail(REASON_LAUNCH_TIMEOUT)
            }
            // ⑥-0 坐标 tap 搜索输入框：微信 FTS 输入框对 a11y 不暴露（findFocus
            // 返回 className/text 全空、bounds 零面积的幽灵节点，全树无
            // isEditable 节点——2026-10-21 真机实测），SET_TEXT/PASTE 打在幽灵
            // 节点必被吞。先按屏幕比例 tap 输入框（与搜索图标同高、居中），
            // 让系统焦点真正落框——KEYCODE_PASTE 与键盘弹起都依赖真实焦点。
            bridge.screenSize()?.let { size ->
                val ix = (size.first * FTS_INPUT_X_RATIO).toInt()
                val iy = (size.second * FTS_INPUT_Y_RATIO).toInt()
                Log.i(TAG, "⑥-0 tap fts input ($ix,$iy)")
                bridge.tap(ix, iy)
                delay(POST_TAP_DELAY_MS)
            }
            var typedOk = false
            if (shizuku.available) {
                // 清残留：FTS 恢复时输入框可能带着上次搜索文本，直接 paste 会
                // 追加成双份关键词（搜索结果必空）。Ctrl+A+DEL 全选清空后再粘贴。
                val cleared = runCatching { shizuku.clearInput() }.getOrNull()
                Log.i(TAG, "⑥-1 clearInput=${cleared?.isSuccess}")
                delay(CLEAR_SETTLE_MS)
                val pasted = shizuku.paste()
                Log.i(TAG, "⑥-1 shizuku KEYCODE_PASTE=${pasted.isSuccess}")
                if (pasted.isSuccess) {
                    // FTS 黑洞读不到输入框文本，无法就地回读验证——上屏与否
                    // 由 ⑦ 的搜索结果命中（=真验证）裁决。
                    typedOk = true
                } else {
                    Log.w(TAG, "⑥-1 shizuku paste failed", pasted.exceptionOrNull())
                }
            } else {
                Log.i(TAG, "⑥-1 shizuku unavailable (${shizuku.unavailableReason}), fallback to a11y")
            }
            if (!typedOk) {
                for (attempt in 1..INPUT_ATTEMPTS) {
                    val focused = bridge.focusFirstEditable()
                    val result = bridge.typeText(name)
                    Log.i(TAG, "⑥-2 attempt=$attempt focused=$focused typed=${result.isSuccess}")
                    if (result.isSuccess) {
                        typedOk = true
                        break
                    }
                    Log.w(TAG, "⑥-2 typeText failed", result.exceptionOrNull())
                    delay(POST_TAP_DELAY_MS)
                }
            }
            // ⑥ 注入失败不早退：微信 FTS 输入框是 a11y 黑洞（SET_TEXT/PASTE 全
            // 被幽灵焦点节点吞掉），但 pasteFallback 已把名字写入剪贴板——主流
            // 输入法会把它联想成候选词（IME 窗口节点 a11y 可见，findText 全窗口
            // 兜底可扫）。⑦ 的多跳循环先 tap 候选词让文本上屏，再 tap 搜索结果
            // 进聊天页。
            if (!typedOk) {
                Log.i(TAG, "⑥ all attempts failed, fall through to ⑦ IME-candidate path")
            }
            delay(POST_INPUT_DELAY_MS)

            // ⑦ 结果定位双通道：a11y 节点优先（结果树可见的 IM），全黑洞时
            // 坐标兜底——微信 FTS 整页对 a11y 服务零暴露（findText=0 且
            // screenText=''，2026-10-21 真机实测），但 FTS 布局固定：搜索框
            // 正下方首个 section 即「联系人」，全名精确搜索的首条目即目标
            // （绿字中心 y≈0.197h，1080×2460 真机像素实测）。坐标兜底只试
            // 一次（重复点同一位置无意义），点错由 ⑧ 标题验证拦截。
            var entered = false
            var coordinateTried = false
            for (hop in 1..RESULT_HOPS) {
                val hit = awaitContactResult(name)
                    ?: if (!coordinateTried) {
                        coordinateTried = true
                        contactResultByRatio()
                    } else {
                        null
                    }
                    ?: break
                Log.i(TAG, "⑦ hop=$hop hit=(${hit.centerX},${hit.centerY}) top=${hit.top} tap…")
                if (bridge.tap(hit.centerX, hit.centerY).isFailure) break
                delay(POST_INPUT_DELAY_MS)
                // 转场慢，标题轮询短等；标题读不到的 IM（微信全页黑洞，窗口
                // 标题恒为「微信」）用 OCR 视觉验证兜底（chatPageVisibleByOcr）。
                repeat(RESULT_POLLS) { poll ->
                    if (!entered) {
                        entered = titleMatches(bridge.windowTitle(), name) ||
                            // 转场 ~1s 后再开 OCR（截早了还是 FTS 页）；每 2 轮
                            // 一次（ML Kit 单次数百 ms，不必每轮跑）。
                            (poll >= 2 && poll % 2 == 0 &&
                                bridge.foregroundPackage() == imPackage &&
                                chatPageVisibleByOcr(name))
                    }
                    if (!entered) delay(POLL_INTERVAL_MS)
                }
                if (entered) break
            }
            Log.i(TAG, "⑧ entered=$entered title=${bridge.windowTitle()}")
            if (entered) NavResult.Ok
            else NavResult.Fail(REASON_CONTACT_NOT_FOUND)
        }

    // ========================= 模式 2：视觉通道（a11y 关） =========================

    /**
     * 静默投影建立（模式 2 感知面前置）：统一走 [ScreenCapture.ensureProjection]
     * 授权编排（a11y 开自动点弹窗 / Shizuku appops 静默直过 / 手动兜底，
     * docs/2026-10-22 方案模块 A）。已建立直接热复用；失败返回 false 由
     * 调用方报因。投影不主动释放——MPManager 常驻投影是既有基础设施（与
     * 找图/WebRTC 共用），导航器不自作主张停。
     */
    private suspend fun ensureProjection(): Boolean {
        val ok = screenCapture.ensureProjection()
        Log.i(TAG, "[v2] ① projection ensure=$ok alive=${screenCapture.projectionAlive}")
        return ok
    }

    /**
     * 模式 2 主流程（a11y 关，§4.5 完整第二套执行面）：Shizuku shell tap
     * 注入执行 + 静默投影抽帧感知（OCR 验证 + 以图找图）。步骤编号与模式 1
     * 对齐（③拉起→⑤搜索→⑥输入→⑦结果→⑧验证）；跳过 ②④④½⑤½ 快捷路径
     * （依赖 a11y 窗口标题/节点树，a11y 关时无从谈起，每次都走搜索全链是
     * 预期行为）。每步失败即报因返回（REASON_*），绝不静默。
     */
    private suspend fun openChatByVision(imPackage: String, name: String): NavResult {
        Log.i(TAG, "[v2] openChat pkg=$imPackage name=$name (vision mode)")
        // ①-2 感知面：Shizuku screencap 主源（原生分辨率清晰帧——HyperOS
        // 投影帧 ~2× 下采样模糊、OCR 无法识别，2026-10-21 真机实测）；投影
        // 降级为兜底帧源（FrameSource 阶梯内自动降级）。screencap 通道探测
        // 失败才建立投影会话；两者皆不可用 → 显式报因（§4.5.2 不静默）。
        if (runCatching { shizuku.screencap() }.getOrNull() == null) {
            Log.i(TAG, "[v2] ①-2 screencap unavailable, fallback to projection")
            if (!ensureProjection()) {
                return NavResult.Fail(REASON_PROJECTION_UNAVAILABLE)
            }
        }
        // ②½ 剪贴板 priming（与模式 1 同款：自家仍前台，写入合法；IM 前台
        // 后 Android 10+ 后台写剪贴板被拒）。
        val clipPrimed = runCatching {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("baic2", name))
            clipboard != null
        }.getOrDefault(false)
        Log.i(TAG, "[v2] ②½ clipboard primed=$clipPrimed")

        // ③ 拉起 IM 并等前台（dumpsys 轮询，节奏与模式 1 一致）。
        val launch = runCatching {
            context.packageManager.getLaunchIntentForPackage(imPackage)
        }.getOrNull() ?: run {
            Log.w(TAG, "[v2] ③ launch intent null (app missing)")
            return NavResult.Fail(REASON_APP_MISSING)
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
            .onFailure {
                Log.w(TAG, "[v2] ③ startActivity failed", it)
                return NavResult.Fail(REASON_APP_MISSING)
            }
        if (!awaitForegroundByShell(imPackage)) {
            Log.w(TAG, "[v2] ③ awaitForeground timeout fg=${shizuku.foregroundPackage()}")
            return NavResult.Fail(REASON_LAUNCH_TIMEOUT)
        }
        delay(POST_TAP_DELAY_MS)

        // ④' 当前页预检（模式 2 无窗口标题可读，用「dumpsys 类名门 + 投影帧
        // OCR」替代模式 1 的 ④ titleMatches）：微信拉起后恢复上次停留页，
        // 若恰为目标聊天页（重复点击/最近聊过场景）直接成功，跳过搜索全链。
        if (chatPageVisibleByVision(name)) {
            Log.i(TAG, "[v2] ④' already on target chat page")
            return NavResult.Ok
        }
        Log.i(TAG, "[v2] ④' not on target page, continue to search")

        // ⑤-1 非主列表态归位：微信 task 栈可能残留多层（搜索页/聊天页，
        // ④'-R2 真机实测栈=[主列表, FTSMainUI, ChattingUI]），搜索图标坐标
        //（⑤）只在主列表有效——back 逐层退出直到 LauncherUI（主列表 back=
        //「再按退出」不真退，模式 1 实测）。
        backToMainList()

        // ⑤ 搜索入口：以图找图优先（search 模板 + 投影帧，多尺度），坐标
        // 比例兜底——双模铁律的「以图找图」主路径；冷启动图标渲染慢，
        // miss 时轮询重试（[IMAGE_FIND_POLLS]）。tap 后必须验证 FTS 真的
        // 开了（R3 真机实测：冷启动 activity 已就绪但像素还是启动态，帧里
        // 无真图标 → 误匹配点在随机位置、FTS 从未打开而链路继续走空）；
        // 未开则归位后重试，ratio 兜底同样验证，全失败即报因。
        var searchTapped = false
        var poll = 0
        while (!searchTapped && poll < IMAGE_FIND_POLLS) {
            poll++
            val match = runCatching { imageMatcher.find(TEMPLATE_SEARCH).getOrNull() }.getOrNull()
            if (match != null) {
                val tapped = shizuku.tap(match.centerX, match.centerY).isSuccess
                Log.i(
                    TAG,
                    "[v2] ⑤ search by image poll=$poll " +
                        "(${match.centerX},${match.centerY}) score=${match.score} tapped=$tapped",
                )
                if (tapped) {
                    delay(POST_TAP_DELAY_MS)
                    searchTapped = ftsOpened()
                    if (!searchTapped) {
                        Log.i(TAG, "[v2] ⑤ fts not open after image tap, retry poll=$poll")
                    }
                }
            } else {
                Log.i(TAG, "[v2] ⑤ image match miss poll=$poll")
            }
            if (!searchTapped) {
                backToMainList()
                delay(POST_TAP_DELAY_MS)
            }
        }
        if (!searchTapped) {
            val size = screenSizeByMetrics()
            val sx = (size.first * SEARCH_ICON_X_RATIO).toInt()
            val sy = (size.second * SEARCH_ICON_Y_RATIO).toInt()
            Log.i(TAG, "[v2] ⑤ image miss, tap search icon by ratio ($sx,$sy)")
            if (shizuku.tap(sx, sy).isSuccess) {
                delay(POST_TAP_DELAY_MS)
                searchTapped = ftsOpened()
            }
        }
        if (!searchTapped) {
            Log.w(TAG, "[v2] ⑤ tap search FAILED (fts never opened)")
            return NavResult.Fail(REASON_TAP_FAILED)
        }
        delay(POST_TAP_DELAY_MS)

        // ⑥ 输入（与模式 1 同构，执行器全换 Shizuku）。
        // ⑥ 防呆：必须仍在 IM 前台（防注入落进自家输入框改用户数据）。
        if (shizuku.foregroundPackage() != imPackage) {
            Log.w(TAG, "[v2] ⑥ not in IM foreground, abort")
            return NavResult.Fail(REASON_LAUNCH_TIMEOUT)
        }
        // ⑥-0 坐标 tap FTS 输入框（比例位置与模式 1 同款，让真实焦点落框）。
        val size = screenSizeByMetrics()
        val ix = (size.first * FTS_INPUT_X_RATIO).toInt()
        val iy = (size.second * FTS_INPUT_Y_RATIO).toInt()
        Log.i(TAG, "[v2] ⑥-0 tap fts input ($ix,$iy)")
        shizuku.tap(ix, iy)
        delay(FTS_INPUT_SETTLE_MS)
        // ⑥-1 清残留 + KEYCODE_PASTE（模式 1 已真机验证的 shell 通道）。
        val cleared = runCatching { shizuku.clearInput() }.getOrNull()
        Log.i(TAG, "[v2] ⑥-1 clearInput=${cleared?.isSuccess}")
        delay(CLEAR_SETTLE_MS)
        val pasted = shizuku.paste()
        Log.i(TAG, "[v2] ⑥-1 shizuku KEYCODE_PASTE=${pasted.isSuccess}")
        if (!pasted.isSuccess) {
            Log.w(TAG, "[v2] ⑥-1 paste failed", pasted.exceptionOrNull())
            return NavResult.Fail(REASON_INPUT_FAILED)
        }
        delay(POST_INPUT_DELAY_MS)

        // ⑦-0 等搜索结果渲染：paste 上屏后 FTS 联想搜索异步刷新（R2 真机实测
        // 固定 1.2s 不够——tap 落在起始页「按住说出你的问题」语音按钮上，
        // y=0.197h 恰与其重合）。screencap + OCR 全帧轮询直到联系人名出现
        // （FTS 起始页无名字文本）再 tap 结果条目；超时兜底维持原坐标直点。
        var resultReady = false
        repeat(RESULT_RENDER_POLLS) {
            if (!resultReady) {
                resultReady = ocrSeesName(name)
                if (!resultReady) delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "[v2] ⑦-0 result rendered=$resultReady")

        // ⑦ 结果条目：坐标比例兜底唯一通道（FTS 全页 a11y 黑洞，节点通道
        // 不存在；paste 直接上屏，无 IME 候选多跳问题，单跳）。
        val entryX = size.first / 2
        val entryY = (size.second * RESULT_ENTRY_Y_RATIO).toInt()
        Log.i(TAG, "[v2] ⑦ tap result entry ($entryX,$entryY)")
        if (shizuku.tap(entryX, entryY).isFailure) {
            return NavResult.Fail(REASON_TAP_FAILED)
        }

        // ⑧ 投影抽帧 OCR 验证（chatPageVisibleByOcr 三层名字匹配 + 类名门）；
        // 转场 ~1s 后再开 OCR（poll≥2），每 2 轮一次。
        var entered = false
        repeat(RESULT_POLLS) { poll ->
            if (!entered) {
                entered = poll >= 2 && poll % 2 == 0 &&
                    chatPageVisibleByVision(name)
            }
            if (!entered) delay(POLL_INTERVAL_MS)
        }
        Log.i(TAG, "[v2] ⑧ entered=$entered")
        return if (entered) NavResult.Ok else NavResult.Fail(REASON_CONTACT_NOT_FOUND)
    }

    /** dumpsys 前台包名轮询（模式 2 的 awaitForeground；节奏同模式 1）。 */
    private suspend fun awaitForegroundByShell(pkg: String): Boolean {
        repeat(FOREGROUND_POLLS) {
            delay(POLL_INTERVAL_MS)
            if (shizuku.foregroundPackage() == pkg) return true
        }
        return false
    }

    /** 归位到微信主列表：back 逐层退出非 LauncherUI 态（搜索页/聊天页/弹层）。 */
    private suspend fun backToMainList() {
        for (round in 1..BACK_ROUNDS) {
            val activity = runCatching { shizuku.foregroundActivity() }.getOrNull()
            if (activity?.contains("LauncherUI") == true) {
                Log.i(TAG, "[v2] ⑤-1 on main list (round=$round)")
                break
            }
            val backed = runCatching { shizuku.back() }.getOrNull()
            Log.i(TAG, "[v2] ⑤-1 back round=$round ok=${backed?.isSuccess} activity=$activity")
            delay(POST_TAP_DELAY_MS)
        }
    }

    /** ⑤ tap 后验证 FTS 搜索页真的打开（防误匹配点空/点到弹层后链路走空）。 */
    private suspend fun ftsOpened(): Boolean =
        runCatching { shizuku.foregroundActivity() }.getOrNull()
            ?.contains("FTSMainUI") == true

    /**
     * ⑦-0 结果区 OCR 找联系人名：只看搜索框以下（centerY ≥ 0.12h）的行——
     * 粘贴上屏后搜索框恒含名字（全帧检查立即假阳性），结果条目（≈0.197h）
     * 渲染出来才算就绪。
     */
    private suspend fun ocrSeesName(name: String): Boolean {
        val frame = runCatching { shizuku.screencap() }.getOrNull() ?: return false
        val lines = ocr.recognize(bitmapToPng(frame)).getOrNull() ?: return false
        val minCy = (frame.height * HEADER_STRIP_RATIO).toInt()
        return nameInOcrLines(lines.filter { it.centerY >= minCy }, name)
    }

    /** 名字三层匹配（精确/CJK 段/前缀 2/3，⑧ 顶部条与 ⑦-0 全帧共用）。 */
    private fun nameInOcrLines(lines: List<OcrLine>, name: String): Boolean {
        val compactName = name.lowercase().filter { !it.isWhitespace() }
        if (compactName.isEmpty()) return false
        val cjkName = compactName.filter { it.code in CJK_RANGE }
        val prefixLen = (compactName.length * PREFIX_MATCH_RATIO).toInt()
        return lines.any { line ->
            val ocr = line.text.lowercase().filter { !it.isWhitespace() }
            ocr.contains(compactName) ||
                (cjkName.length >= CJK_MIN && ocr.filter { it.code in CJK_RANGE }.contains(cjkName)) ||
                commonPrefixLen(ocr, compactName) >= prefixLen
        }
    }

    /**
     * 模式 2 聊天页判别（④'/⑧ 共用）：dumpsys Activity 类名门 + 帧 OCR。
     * 聊天页有两种宿主：ChattingUI activity（从 FTS 搜索结果进入）或
     * LauncherUI 内嵌 fragment（从主列表直点进入——此时 task 栈仅
     * LauncherUI、无 ChattingUI 记录，2026-10-22 真机实测），两者都放行；
     * FTSMainUI（搜索页）仍排除——其搜索框文本=名字会诱导 OCR 误报，而
     * 「取消」小字 ML Kit 偶发漏识不可单独依赖。主列表态的 LauncherUI 由
     * [chatPageVisibleByOcr] 顶部条把关：主列表标题「微信(N)」不含联系人名，
     * 聊天页顶部条才是名字——类名只证明「在微信」，名字证明「跟谁的页」。
     */
    private suspend fun chatPageVisibleByVision(name: String): Boolean {
        val activity = runCatching { shizuku.foregroundActivity() }.getOrNull()
        val chatHost = activity?.contains("ChattingUI") == true ||
            activity?.contains("LauncherUI") == true
        if (!chatHost) return false
        return chatPageVisibleByOcr(name, frameSource.grabFrame().getOrNull())
    }

    /**
     * 屏幕尺寸（模式 2 坐标系）：application context 的 displayMetrics 即
     * 真实屏幕（1080×2460），与 a11y 无关、无窗口裁剪。
     */
    private fun screenSizeByMetrics(): Pair<Int, Int> =
        context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** 便捷 Toast：结果码 → 用户文案（画像页调用方用，避免每处手写 when）。 */
    fun toastResult(result: NavResult, contactName: String) {
        // 报因必须具体到环节（§4.5.2）：未单独映射的 REASON_* 不许吞成一句
        // 泛泛文案——把原始 reason 拼给用户，问题可报告、可定位。
        val res = when (result) {
            is NavResult.Ok -> com.verlintas.baic2.R.string.float_im_nav_ok
            is NavResult.Fail -> when (result.reason) {
                REASON_A11Y_OFF -> com.verlintas.baic2.R.string.float_im_nav_a11y_off
                REASON_SHIZUKU_OFF -> com.verlintas.baic2.R.string.float_im_nav_shizuku_off
                REASON_PROJECTION_UNAVAILABLE ->
                    com.verlintas.baic2.R.string.float_im_nav_projection_fail
                REASON_APP_MISSING -> com.verlintas.baic2.R.string.float_im_nav_app_missing
                else -> null
            }
        }
        val message = when {
            res != null -> context.getString(res, contactName)
            result is NavResult.Fail -> context.getString(
                com.verlintas.baic2.R.string.float_im_nav_generic_fail,
                contactName,
                result.reason,
            )
            else -> return
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /** 尽力拉起 IM（失败静默——结果码已带原因，Toast 会说明）。 */
    private fun launchBestEffort(imPackage: String) {
        runCatching {
            context.packageManager.getLaunchIntentForPackage(imPackage)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let(context::startActivity)
        }
    }

    /** 重新拉起 IM 并等其回前台（⑤ back 把 IM 退到后台时恢复）。 */
    private suspend fun relaunch(imPackage: String): Boolean {
        val launch = runCatching {
            context.packageManager.getLaunchIntentForPackage(imPackage)
        }.getOrNull() ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
        return awaitForeground(imPackage)
    }

    private suspend fun awaitForeground(pkg: String): Boolean {
        repeat(FOREGROUND_POLLS) {
            delay(POLL_INTERVAL_MS)
            if (bridge.foregroundPackage() == pkg) return true
        }
        return false
    }

    /** 轮询找文本节点（等待界面转场补齐节点）；只认中心在屏幕内的节点——
     *  真机实测 dump 会给出滚出屏/隐藏节点（如 (-734,2045)），点了必失败。 */
    private suspend fun awaitNode(query: String): TextNode? {
        repeat(NODE_POLLS) {
            bridge.findText(query).firstOrNull(::inScreen)?.let { return it }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * 搜索结果节点：排除顶部输入框（其 text=关键词、top 小）与屏幕外节点，
     * 取「最靠上的合格节点」——搜索结果条目（列表区 y 小）优先于 IME 候选词
     * （键盘区 y 大），两类目标都可能是 ⑦ 的合法 tap 对象。
     */
    private suspend fun awaitContactResult(name: String): TextNode? {
        repeat(RESULT_POLLS) {
            val minTop = (bridge.screenSize()?.second ?: 0) / RESULT_MIN_TOP_RATIO
            val hit = bridge.findText(name)
                .filter { it.top > minTop && inScreen(it) }
                .minByOrNull { it.top }
            if (hit != null) return hit
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    /** 节点中心是否在屏幕内（screenSize 不可用时放行，交由 tap 自身防护）。 */
    private fun inScreen(node: TextNode): Boolean {
        val size = bridge.screenSize() ?: return true
        return node.centerX in 0 until size.first && node.centerY in 0 until size.second
    }

    /**
     * 标题含名字判定（②④④½⑦⑧ 共用）：大小写不敏感 + 空白无关——微信
     * 聊天页标题/节点文本可能带分词空格（「吴瑞曦 topwayricky」），
     * 精确子串会 miss。
     */
    private fun titleMatches(title: String?, name: String): Boolean {
        if (title == null) return false
        if (title.contains(name)) return true
        val compactTitle = title.lowercase().filter { !it.isWhitespace() }
        val compactName = name.lowercase().filter { !it.isWhitespace() }
        return compactName.isNotEmpty() && compactTitle.contains(compactName)
    }

    /** 等搜索页输入框自动聚焦（FTS 转场+键盘弹起需 1-2s，轮询而非单次读）。 */
    private suspend fun awaitInputFocused(): Boolean {
        repeat(INPUT_FOCUS_POLLS) {
            if (bridge.inputFocused()) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    /**
     * ⑦ 坐标兜底：微信 FTS 结果页「联系人」首条目的屏幕比例位置（a11y 全黑洞
     * 时的唯一通道）。x 取屏幕居中（条目通栏可点），y 见 [RESULT_ENTRY_Y_RATIO]。
     * 返回 1px 占位 bounds 的节点，centerX/Y 即 tap 点。
     */
    private fun contactResultByRatio(): TextNode? {
        val size = bridge.screenSize() ?: return null
        val x = size.first / 2
        val y = (size.second * RESULT_ENTRY_Y_RATIO).toInt()
        return TextNode("", x - 1, y - 1, x + 1, y + 1)
    }

    /**
     * ⑧ 视觉验证兜底：微信全页 a11y 黑洞（节点零暴露、窗口标题恒为「微信」，
     * 2026-10-21 真机实测），标题/节点通道天然失效——改走截图 + OCR 验证
     * 聊天页特征：顶部条（[HEADER_STRIP_RATIO] 高）含联系人名（聊天页头部
     * 居中）且无「取消」——FTS 搜索页顶部条是搜索框（文本恰为名字）+右上角
     * 「取消」，仅凭名字无法区分两页，取消是判别键。
     *
     * 帧来源双模：[visionFrame] 非空（模式 2 投影抽帧）直接用；否则模式 1
     * 走 a11y 截图（API 30+，免投影授权）。
     *
     * 名字匹配三层放宽（ML Kit 对小字号拉丁尾缀偶发漏字符，真机实测
     * 「ricky」被识成「ricy」；中文段识别稳定）：精确包含 → 名字的 CJK
     * 字符序列包含（拉丁噪声全忽略）→ 公共前缀 ≥ 名长的 2/3。
     */
    private suspend fun chatPageVisibleByOcr(name: String, visionFrame: Bitmap? = null): Boolean {
        val png = visionFrame?.let(::bitmapToPng)
            ?: bridge.captureScreen().getOrNull()
            ?: return false
        val lines = ocr.recognize(png).getOrNull() ?: return false
        // 顶部条高度：模式 2 用投影帧自身高度（全屏帧即屏幕），模式 1 用
        // a11y screenSize（a11y 关时为 null，模式 2 必须给帧）。
        val height = visionFrame?.height ?: bridge.screenSize()?.second ?: return false
        val stripBottom = (height * HEADER_STRIP_RATIO).toInt()
        val strip = lines.filter { it.centerY < stripBottom }
        val nameHit = nameInOcrLines(strip, name)
        val cancelHit = strip.any { it.text.contains("取消") }
        if (lines.isEmpty()) {
            // 诊断（黑帧/尺寸异常线索）：帧到手但一个字都没识别到。
            Log.w(
                TAG,
                "⑧-ocr zero lines, frame=" +
                    (visionFrame?.let { "${it.width}x${it.height}" } ?: "a11y-capture"),
            )
        }
        Log.i(TAG, "⑧-ocr nameHit=$nameHit cancelHit=$cancelHit lines=${strip.size}")
        return nameHit && !cancelHit
    }

    /** [a]/[b] 公共前缀长度（大小写不敏感由调用方保证）。 */
    private fun commonPrefixLen(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    /** 投影帧 → PNG bytes（OCR 接口口径；OcrSendLocator 同款压缩）。 */
    private fun bitmapToPng(frame: Bitmap): ByteArray =
        ByteArrayOutputStream().use { sink ->
            frame.compress(Bitmap.CompressFormat.PNG, 100, sink)
            sink.toByteArray()
        }
}
