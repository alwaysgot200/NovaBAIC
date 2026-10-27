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

        /**
         * a11y 黑洞探针的节点预算（2026-10-27）：只用于判断「整页有没有文本」，
         * 取够最小样本即停，避免为一次探测走完整棵树。
         */
        private const val BLACKHOLE_PROBE_NODES = 64

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

        /** 主列表底部 Tab 条判定带（[MAIN_LIST_TAB_ZONE_RATIO] 以上不计）。 */
        private const val MAIN_LIST_TAB_ZONE_RATIO = 0.90f

        /** 底部带内命中 ≥ 该数量个 Tab 词即判「已在主列表」（单命中容错不足）。 */
        private const val MAIN_LIST_TAB_MIN_HITS = 2

        /**
         * 主列表视觉直达的翻页上限（2026-10-27）：每页一帧 OCR，未命中就上滑
         * 一屏再找。真机实测目标会话常在首屏之后（置顶/新消息把列表推下去，
         * 实测账号 47 条置顶 + 50 未读），4 页时未命中→提到 6 页
         * （≈10s 上限；通知直达命中时这一段根本不跑）。单页耗时 ≈1.7s
         *（a11y 截图 + OCR），超上限即判失败回落搜索链，不无界拖长启动。
         */
        private const val LIST_SCAN_PAGES = 6

        /**
         * 主列表名字行的最小 y 比例：排除顶部标题带。真机 1080×2460 实测首条
         * 会话名字 y≈320（比例 0.13），比标题「微信(N)」低一档，取 0.055h 兜底。
         */
        private const val LIST_NAME_MIN_TOP_RATIO = 0.055f

        /** 主列表翻页上滑（起点/终点 y 比例与时长）：滑动而非 fling，列表不甩飞。 */
        private const val LIST_SWIPE_FROM_Y_RATIO = 0.72f
        private const val LIST_SWIPE_TO_Y_RATIO = 0.28f
        private const val LIST_SWIPE_MS = 320

        /** 主列表底部 Tab 词（聊天页输入栏/搜索页键盘区不含这组词）。 */
        private val MAIN_LIST_TAB_TOKENS = listOf("微信", "通讯录", "发现")

        /**
         * 置顶折叠行文本（微信主列表）：出现这一行即置顶区处于折叠态，
         * 点击即展开。目标联系人若被置顶，普通列表里根本不存在其行。
         */
        private const val PINNED_COLLAPSED_TOKEN = "折叠置顶聊天"
    }

    /**
     * 单个 IM 的页面 activity 关键字（模式 2 无 a11y 节点树，只能靠 dumpsys
     * 类名判别「主列表 / 搜索页 / 聊天页」）。昵称来自 2026-10-26 真机实测：
     * 微信 LauncherUI/FTSMainUI/ChattingUI；企微 WwMainActivity/
     * GlobalSearchActivityV2/MessageListActivity。
     */
    private data class ImPages(
        val mainMarkers: List<String>,
        val searchMarkers: List<String>,
        val chatMarkers: List<String>,
    )

    private fun pagesOf(imPackage: String): ImPages = when (imPackage) {
        "com.tencent.mm" -> ImPages(
            mainMarkers = listOf("LauncherUI"),
            searchMarkers = listOf("FTSMainUI"),
            // 微信聊天页有两种宿主：独立 ChattingUI 或 LauncherUI 内嵌 fragment，
            // 后者由顶部条 OCR 名字把关（与 2026-10-22 实测口径一致）。
            chatMarkers = listOf("ChattingUI", "LauncherUI"),
        )
        "com.tencent.wework" -> ImPages(
            mainMarkers = listOf("WwMainActivity"),
            searchMarkers = listOf("GlobalSearchActivity"),
            chatMarkers = listOf("MessageListActivity"),
        )
        else -> ImPages(
            mainMarkers = listOf("Launcher", "Main"),
            searchMarkers = listOf("Search"),
            chatMarkers = listOf("Chat", "Message"),
        )
    }

    /**
     * 打开 [imPackage] 中与 [contactName] 的聊天页。主线程之外执行（IO），
     * a11y 手势/tap 由 [AssistsA11yBridge] 内部调度到主线程。
     *
     * 2026-10-27 启动段记账：全流程分段耗时以 `auto_timing:nav=…,probe=…` 落
     * logcat（TAG=[AutoTiming.TAG]，与回合/执行账单同前缀同 TAG）——「点开始
     * 托管到首帧 28s」的归因不再靠人工比对时间戳。
     */
    suspend fun openChat(imPackage: String, contactName: String): NavResult =
        withContext(Dispatchers.IO) {
            val name = contactName.trim()
            if (name.isEmpty()) return@withContext NavResult.Fail(REASON_CONTACT_NOT_FOUND)
            val trace = TimingTrace()
            val startedAtNanos = System.nanoTime()
            var markNanos = startedAtNanos
            fun mark(stage: String) {
                val now = System.nanoTime()
                trace.add(stage, (now - markNanos) / 1_000_000)
                markNanos = now
            }
            // 统一出口：补当前段与总耗时，落一行账单（每个 return 都经此，无漏账）。
            fun done(stage: String, result: NavResult): NavResult {
                mark(stage)
                trace.add("total", (System.nanoTime() - startedAtNanos) / 1_000_000)
                Log.i(AutoTiming.TAG, "${trace.render()} pkg=$imPackage name=$name result=$result")
                return result
            }
            Log.i(TAG, "openChat pkg=$imPackage name=$name a11y=${bridge.connected.value}")

            // ① 双模门面（§4.5 执行面双模铁律）：a11y 关不是失败，而是切换到
            // 视觉通道（Shizuku 注入 + 静默投影抽帧 + OCR + 以图找图）；只有
            // 两套执行面都不可用时才报因（尽力拉起 App，用户至少能手动点进
            // 联系人），绝不静默无动作。
            if (!bridge.connected.value) {
                if (shizuku.available) {
                    return@withContext done("mode2", openChatByVision(imPackage, name))
                }
                Log.w(TAG, "① a11y off & shizuku unavailable (${shizuku.unavailableReason})")
                launchBestEffort(imPackage)
                return@withContext done("gate", NavResult.Fail(REASON_SHIZUKU_OFF))
            }

            // ② 已在该联系人的聊天页？标题即联系人名，直接成功（重复点击场景）。
            val titleNow = bridge.windowTitle()
            Log.i(TAG, "② fg=${bridge.foregroundPackage()} title=$titleNow")
            if (bridge.foregroundPackage() == imPackage && titleMatches(titleNow, name)) {
                return@withContext done("probe", NavResult.Ok)
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
                    return@withContext done("launch", NavResult.Fail(REASON_APP_MISSING))
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(launch) }
                    .onFailure {
                        Log.w(TAG, "③ startActivity failed", it)
                        return@withContext done("launch", NavResult.Fail(REASON_APP_MISSING))
                    }
                if (!awaitForeground(imPackage)) {
                    Log.w(TAG, "③ awaitForeground timeout fg=${bridge.foregroundPackage()}")
                    return@withContext done("launch", NavResult.Fail(REASON_LAUNCH_TIMEOUT))
                }
            }

            // ④ 若拉起后已在聊天页（微信停在聊天 Tab 上次的聊天），直接成功。
            val title4 = bridge.windowTitle()
            Log.i(TAG, "④ title=$title4")
            if (titleMatches(title4, name)) {
                return@withContext NavResult.Ok
            }

            // ④¾ a11y 黑洞视觉直达（2026-10-27 启动慢归因修复）：微信页面节点树
            // 对无障碍客户端零暴露——真机 10:19 实测 ②④ 标题恒为 null/「微信」，
            // ④½ findText 恒空，旧流程只能「back 归位 → 搜索全链」，从点击「开始
            // 自动托管」到首帧耗时 28s（10:19:09→10:19:37）。而「陪伴会话重启时
            // 已停在目标聊天页」恰是最常见场景：用不依赖 a11y 的 activity 类名门
            // + 顶部条 OCR（[chatPageVisibleByVision]，含搜索页「取消」排除）当场
            // 确认，命中即成功（~1s vs 28s）。Shizuku 不可用时该函数返回 false，
            // 自动回落旧路径（无阻塞、无新增失败面）。
            val imPages = pagesOf(imPackage)
            if (bridge.foregroundPackage() == imPackage &&
                chatPageVisibleByVision(name, imPages)
            ) {
                Log.i(TAG, "④¾ vision: already on target chat page, skip search")
                return@withContext done("probe", NavResult.Ok)
            }

            // ④¾-a 视觉「已在目标会话」预检（不依赖 Shizuku，2026-10-27 补）：
            // ④¾ 走 dumpsys activity，Shizuku 关时恒 false——而本机正是「a11y 开、
            // Shizuku 关」，重启自动档时若已停在目标会话页，旧逻辑会走 20s 搜索
            // 全链再失败。这里用 a11y 截图 + OCR 做等价判定：顶部条名字命中、
            // 无「取消」（排除搜索页）、底部无主列表 Tab 锚（排除主列表）。
            if (bridge.foregroundPackage() == imPackage && chatPageVisibleByOcr(name)) {
                Log.i(TAG, "④¾-a vision(a11y): already on target chat page, skip search")
                return@withContext done("probe", NavResult.Ok)
            }

            // ④½ 快捷路径：主列表里直接点该联系人的会话条目（「刚聊过」最常见
            // 场景，跳过搜索全流程）。条目节点 text 即联系人名（findText 包含匹配）。
            // 黑页指纹先行（节点树零暴露的 IM 不空等 [NODE_POLLS]）。
            val listHit = if (bridge.foregroundPackage() == imPackage && !screenTextBlank()) {
                awaitNode(name)
            } else {
                null
            }
            if (listHit != null && bridge.tap(listHit.centerX, listHit.centerY).isSuccess) {
                delay(POST_TAP_DELAY_MS)
                val titleFast = bridge.windowTitle()
                Log.i(TAG, "④½ list hit (${listHit.centerX},${listHit.centerY}) title=$titleFast")
                if (titleMatches(titleFast, name)) {
                    return@withContext done("list", NavResult.Ok)
                }
                // 点了没进聊天页（可能是列表项误报）：退回主列表再走搜索。
                runCatching { bridge.pressKey("back") }
                delay(POST_TAP_DELAY_MS)
            }

            // ④½-b 主列表视觉直达（2026-10-27 新增，微信等 a11y 黑洞的主路径）：
            // 节点通道恒空时，用「截图 + OCR 找会话行 → tap」进会话，不依赖
            // 节点树/注入通道。命中即成功（~2s），未命中才继续搜索全链。
            // 扫描记号：⑤ 分支若同一状态再扫一遍（真机 14:15 实测白花 7s，
            // 同一列表同一方向，结果必然相同）只认第一次的结论。
            var listScanned = false
            if (bridge.foregroundPackage() == imPackage) {
                listScanned = true
                if (tapMainListContact(name)) {
                    return@withContext done("list", NavResult.Ok)
                }
            }
            mark("probe")

            // ⑤ 进搜索页：微信拉起后恢复上次停留页（主列表/聊天页/搜索页三态，
            // 2026-10-21 真机实测）。归位策略 2026-10-27 收紧：每轮先做状态预检
            // （搜索页/输入框聚焦/视觉主列表锚），确认该 back 才 back；不再盲退。
            // 循环后仍未就绪 → 点搜索入口（节点优先 + 坐标兜底，主列表 a11y 树
            // 对无障碍客户端不可见，坐标兜底是实际主路径）。
            var searchReady = false
            for (round in 1..SEARCH_ROUNDS) {
                if (bridge.foregroundPackage() != imPackage) {
                    Log.i(TAG, "⑤ im backgrounded, relaunch…")
                    if (!relaunch(imPackage)) {
                        Log.w(TAG, "⑤ relaunch timeout")
                        return@withContext done("rounds", NavResult.Fail(REASON_LAUNCH_TIMEOUT))
                    }
                }
                // ⑤-0 状态预检（2026-10-27 稳定性修复）：back 只为「退出非主列表
                // 页」，绝不在已经是主列表/搜索页时按——真机 10:19 实测微信主列表
                // 再按一次 back 直接退出到系统桌面，重拉起 + 搜索全链白白多花 20s。
                // ① activity 通道（不依赖 a11y 节点树）：已在搜索页 = 输入框就绪；
                // ② a11y 焦点通道（企微等节点树可读的 IM）：输入框已聚焦即就绪；
                // ③ 视觉主列表锚：底部 Tab 条（聊天页/搜索页都没有），确认在主列表
                //    就不再 back，直接进下方「点搜索图标」。
                if (isSearchActivity(imPages)) {
                    Log.i(TAG, "⑤ already on search page (activity), input ready")
                    searchReady = true
                    break
                }
                if (awaitInputFocused()) {
                    searchReady = true
                    break
                }
                if (looksLikeMainListByOcr()) {
                    Log.i(TAG, "⑤ already on main list (vision), skip back")
                    // 主列表视觉直达（2026-10-27）：已在主列表且目标会话在列表里
                    // 就当场点进去，不走搜索（微信 FTS 输入框对 a11y 与 Shizuku
                    // 双通道都可能吞字，搜索链在本机是必败路径）。④½-b 已扫过
                    // （listScanned）时不重复扫描——同列表同方向结果必然相同。
                    if (!listScanned && tapMainListContact(name)) {
                        return@withContext done("list", NavResult.Ok)
                    }
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
                            return@withContext done("list", NavResult.Ok)
                        }
                        runCatching { bridge.pressKey("back") }
                        delay(POST_TAP_DELAY_MS)
                    }
                }
            }
            mark("rounds")
            if (!searchReady) {
                // 循环耗尽仍未就绪：back 可能把 IM 退到了后台（独立 task 页场景），
                // 先确保 IM 前台——relaunch 恢复的若是搜索页，输入框聚焦即就绪。
                if (bridge.foregroundPackage() != imPackage) {
                    Log.i(TAG, "⑤ post-rounds relaunch…")
                    if (!relaunch(imPackage)) {
                        Log.w(TAG, "⑤ post-rounds relaunch timeout")
                        return@withContext done("rounds", NavResult.Fail(REASON_LAUNCH_TIMEOUT))
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
                        return@withContext done("search", NavResult.Fail(REASON_SEARCH_BUTTON_NOT_FOUND))
                    }
                    val sx = (size.first * SEARCH_ICON_X_RATIO).toInt()
                    val sy = (size.second * SEARCH_ICON_Y_RATIO).toInt()
                    Log.i(TAG, "⑤ node miss, tap search icon by ratio ($sx,$sy)")
                    searchTapped = bridge.tap(sx, sy).isSuccess
                }
                if (!searchTapped) {
                    Log.w(TAG, "⑤ tap search FAILED")
                    return@withContext done("search", NavResult.Fail(REASON_TAP_FAILED))
                }
                delay(POST_TAP_DELAY_MS)
            }
            mark("search")

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
                return@withContext done("input", NavResult.Fail(REASON_LAUNCH_TIMEOUT))
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
            mark("input")

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
            return@withContext done(
                "verify",
                if (entered) NavResult.Ok else NavResult.Fail(REASON_CONTACT_NOT_FOUND),
            )
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
     *
     * 可见性 2026-10-27：供 RPA 解释器在「无 a11y」线显式调用——即便本机
     * a11y 处于开启状态，也必须走这条纯视觉线，满足 RPA 三模式的分流语义。
     */
    suspend fun openChatByVision(imPackage: String, name: String): NavResult {
        Log.i(TAG, "[v2] openChat pkg=$imPackage name=$name (vision mode)")
        val pages = pagesOf(imPackage)
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
        if (chatPageVisibleByVision(name, pages)) {
            Log.i(TAG, "[v2] ④' already on target chat page")
            return NavResult.Ok
        }
        Log.i(TAG, "[v2] ④' not on target page, continue to search")

        // ⑤-0 已在搜索页（拉起后恢复了上次停留的搜索页）→ 直接输入，不必
        // 归位再点搜索图标（企微 back 会退到上一 App，绕路反而易错）。
        var searchReady = isSearchActivity(pages)

        // ⑤-1 非主列表/搜索页时归位：微信/企微 task 栈可能残留多层（搜索页/
        // 聊天页，真机实测栈=[主列表, 搜索页, 聊天页]）。防呆（2026-10-26
        // 企微实测）：back 可能退出 IM 回到上一个 App（企微主列表 back 直接
        // 退到微信），盲退 3 轮会把「搜索图标」点进别的 App——每轮先确认
        // 前台仍是目标 IM，退到主列表即停；已离开则重新拉起。
        if (!searchReady) {
            backToMainList(imPackage, pages)
            if (shizuku.foregroundPackage() != imPackage) {
                Log.i(TAG, "[v2] ⑤-1 im left foreground, relaunch…")
                if (!relaunchByShell(imPackage)) {
                    Log.w(TAG, "[v2] ⑤-1 relaunch timeout")
                    return NavResult.Fail(REASON_LAUNCH_TIMEOUT)
                }
                delay(POST_TAP_DELAY_MS)
            }
            searchReady = isSearchActivity(pages)
        }

        // ⑤ 搜索入口：以图找图优先（search 模板 + 投影帧，多尺度），坐标
        // 比例兜底——双模铁律的「以图找图」主路径；冷启动图标渲染慢，
        // miss 时轮询重试（[IMAGE_FIND_POLLS]）。tap 后必须验证搜索页真的
        // 开了（R3 真机实测：冷启动 activity 已就绪但像素还是启动态，帧里
        // 无真图标 → 误匹配点在随机位置、搜索页从未打开而链路继续走空）；
        // 未开则归位后重试，ratio 兜底同样验证，全失败即报因。
        var searchTapped = searchReady
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
                    searchTapped = isSearchActivity(pages)
                    if (!searchTapped) {
                        Log.i(TAG, "[v2] ⑤ search page not open after image tap, retry poll=$poll")
                    }
                }
            } else {
                Log.i(TAG, "[v2] ⑤ image match miss poll=$poll")
            }
            if (!searchTapped) {
                backToMainList(imPackage, pages)
                if (shizuku.foregroundPackage() != imPackage && !relaunchByShell(imPackage)) {
                    Log.w(TAG, "[v2] ⑤ retry relaunch timeout")
                    return NavResult.Fail(REASON_LAUNCH_TIMEOUT)
                }
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
                searchTapped = isSearchActivity(pages)
            }
        }
        if (!searchTapped) {
            Log.w(TAG, "[v2] ⑤ tap search FAILED (search page never opened)")
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

        // ⑦-0 等搜索结果渲染：paste 上屏后搜索联想异步刷新（R2 真机实测
        // 固定 1.2s 不够——tap 落在起始页「按住说出你的问题」语音按钮上，
        // y=0.197h 恰与其重合）。screencap + OCR 全帧轮询直到联系人名出现
        // （搜索起始页无名字文本）；命中行的中心即结果条目 tap 点（各 IM
        // 结果行高/布局不同，OCR 定位比固定比例稳，企微实测行中心 y≈525）。
        var resultLine: OcrLine? = null
        repeat(RESULT_RENDER_POLLS) {
            if (resultLine == null) {
                resultLine = ocrNameLine(name)
                if (resultLine == null) delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "[v2] ⑦-0 result line=${resultLine?.text} cy=${resultLine?.centerY}")

        // ⑦ 结果条目：OCR 命中行中心优先，未渲染出来才回退坐标比例（搜索页
        // 全页 a11y 黑洞，节点通道不存在；paste 直接上屏，单跳）。
        val entryX = resultLine?.centerX ?: (size.first / 2)
        val entryY = resultLine?.centerY ?: (size.second * RESULT_ENTRY_Y_RATIO).toInt()
        Log.i(TAG, "[v2] ⑦ tap result entry ($entryX,$entryY)")
        if (shizuku.tap(entryX, entryY).isFailure) {
            return NavResult.Fail(REASON_TAP_FAILED)
        }

        // ⑧ 抽帧 OCR 验证（chatPageVisibleByOcr 三层名字匹配 + 类名门）；
        // 转场 ~1s 后再开 OCR（poll≥2），每 2 轮一次。
        var entered = false
        repeat(RESULT_POLLS) { poll ->
            if (!entered) {
                entered = poll >= 2 && poll % 2 == 0 &&
                    chatPageVisibleByVision(name, pages)
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

    /** 重新拉起 IM 并等前台（模式 2 用 shell 轮询，不依赖 a11y）。 */
    private suspend fun relaunchByShell(imPackage: String): Boolean {
        val launch = runCatching {
            context.packageManager.getLaunchIntentForPackage(imPackage)
        }.getOrNull() ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
        return awaitForegroundByShell(imPackage)
    }

    /** 当前是否在搜索页（页面 activity 命中搜索关键字）。 */
    private suspend fun isSearchActivity(pages: ImPages): Boolean {
        val activity = runCatching { shizuku.foregroundActivity() }.getOrNull() ?: return false
        return pages.searchMarkers.any { activity.contains(it) }
    }

    /**
     * 归位到 IM 主列表：back 逐层退出非主列表态（搜索页/聊天页/弹层）。
     * 每轮先确认前台仍是目标 IM——back 可能直接退出 App（企微主列表 back
     * 退到上一个 App），此时立即停手交调用方重新拉起，绝不盲退。
     * 主列表判定按 [pages] 的 IM 专属 activity（微信 LauncherUI、企微
     * WwMainActivity），通用「LauncherUI」硬编码会让企微永远归不了位。
     */
    private suspend fun backToMainList(imPackage: String, pages: ImPages) {
        for (round in 1..BACK_ROUNDS) {
            val fg = runCatching { shizuku.foregroundPackage() }.getOrNull()
            if (fg != imPackage) {
                Log.i(TAG, "[v2] ⑤-1 left $imPackage fg=$fg (round=$round)")
                return
            }
            val activity = runCatching { shizuku.foregroundActivity() }.getOrNull()
            if (pages.mainMarkers.any { activity?.contains(it) == true }) {
                Log.i(TAG, "[v2] ⑤-1 on main list (round=$round) activity=$activity")
                return
            }
            val backed = runCatching { shizuku.back() }.getOrNull()
            Log.i(TAG, "[v2] ⑤-1 back round=$round ok=${backed?.isSuccess} activity=$activity")
            delay(POST_TAP_DELAY_MS)
        }
    }

    /**
     * ⑦-0 结果区 OCR 找联系人名并回传命中行：只看搜索框以下（centerY ≥ 0.12h）
     * 的行——粘贴上屏后搜索框恒含名字（全帧检查立即假阳性）。多命中取最靠上
     * （IM 搜索按相关度/最近排序，与「打开排序最前的对话」一致）。
     */
    private suspend fun ocrNameLine(name: String): OcrLine? {
        val frame = runCatching { shizuku.screencap() }.getOrNull() ?: return null
        val lines = ocr.recognize(bitmapToPng(frame)).getOrNull() ?: return null
        val minCy = (frame.height * HEADER_STRIP_RATIO).toInt()
        return lines.asSequence()
            .filter { it.centerY >= minCy && lineMatches(it.text, name) }
            .minByOrNull { it.centerY }
    }

    /** 名字三层匹配（精确/CJK 段/前缀 2/3，⑧ 顶部条与 ⑦-0 结果区共用）。 */
    private fun nameInOcrLines(lines: List<OcrLine>, name: String): Boolean =
        lines.any { lineMatches(it.text, name) }

    private fun lineMatches(text: String, name: String): Boolean {
        val compactName = name.lowercase().filter { !it.isWhitespace() }
        if (compactName.isEmpty()) return false
        val cjkName = compactName.filter { it.code in CJK_RANGE }
        val prefixLen = (compactName.length * PREFIX_MATCH_RATIO).toInt()
        val ocr = text.lowercase().filter { !it.isWhitespace() }
        return ocr.contains(compactName) ||
            (cjkName.length >= CJK_MIN && ocr.filter { it.code in CJK_RANGE }.contains(cjkName)) ||
            commonPrefixLen(ocr, compactName) >= prefixLen
    }

    /**
     * 模式 2 聊天页判别（④'/⑧ 共用）：dumpsys Activity 类名门 + 帧 OCR。
     * 类名门按 IM 专属 [ImPages.chatMarkers]：微信 ChattingUI / LauncherUI
     * 内嵌 fragment（从主列表直点进入时 task 栈仅 LauncherUI、无 ChattingUI
     * 记录，2026-10-22 实测）、企微 MessageListActivity（2026-10-26 实测）。
     * 搜索页仍排除——其搜索框文本=名字会诱导 OCR 误报，而「取消」小字
     * ML Kit 偶发漏识不可单独依赖；搜索页 activity 不在 chatMarkers 内，
     * 天然排除。主列表态的微信 LauncherUI 由 [chatPageVisibleByOcr] 顶部条
     * 把关：主列表标题「微信(N)」不含联系人名，聊天页顶部条才是名字。
     */
    private suspend fun chatPageVisibleByVision(name: String, pages: ImPages): Boolean {
        val activity = runCatching { shizuku.foregroundActivity() }.getOrNull()
            ?: return false
        if (pages.chatMarkers.none { activity.contains(it) }) return false
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

    /**
     * 联系人名匹配片段（模糊匹配）：除完整名外，仅当「中文段 ≥2 字 + 拉丁段
     * ≥5 字符」时再拆出中英文片段（如「吴瑞曦topwayricky」→ 吴瑞曦 /
     * topwayricky）。微信联系人显示名常是中文+英文备注连写，搜索结果/聊天页
     * 标题可能只露出其中一段（或带分词空格），精确匹配必 miss。
     * 拉丁段门槛 5 字符：避免把「demo 项目群」这类普通标题拆成 "demo" 后
     * 误中其他会话。
     */
    private fun matchTokens(name: String): List<String> {
        val compact = name.lowercase().filter { !it.isWhitespace() }
        if (compact.isEmpty()) return emptyList()
        val cjk = compact.filter { it.code in CJK_RANGE }
        val latin = compact.filter { it in 'a'..'z' || it in '0'..'9' }
        return buildList {
            add(compact)
            if (cjk.length >= CJK_MIN && latin.length >= 5) {
                add(cjk)
                add(latin)
            }
        }.distinct()
    }

    /** 按完整名 + 中英文片段查找文本节点（去重），供结果/会话条目模糊匹配。 */
    private fun findTextFuzzy(query: String): List<TextNode> {
        val tokens = matchTokens(query)
        if (tokens.isEmpty()) return emptyList()
        if (tokens.size == 1) return bridge.findText(tokens.first())
        val results = LinkedHashMap<String, TextNode>()
        tokens.forEach { token ->
            bridge.findText(token).forEach { node ->
                results.putIfAbsent("${node.text}\u0000${node.top}\u0000${node.left}", node)
            }
        }
        return results.values.toList()
    }

    /** 轮询找文本节点（等待界面转场补齐节点）；只认中心在屏幕内的节点——
     *  真机实测 dump 会给出滚出屏/隐藏节点（如 (-734,2045)），点了必失败。
     *  多命中时取最靠上者：IM 主列表按最近会话排序，首条即「排序最前的对话」。 */
    private suspend fun awaitNode(query: String): TextNode? {
        repeat(NODE_POLLS) { poll ->
            findTextFuzzy(query)
                .filter(::inScreen)
                .minByOrNull { it.top }
                ?.let { return it }
            // a11y 黑洞早退（2026-10-27 启动慢归因）：整页读不到任何文本时，
            // 再多轮询也变不出节点（微信部分页面对无障碍零暴露），留一拍给
            // 转场渲染后即返回 null，把等待让给调用方的 OCR/坐标兜底，
            // 单处省 NODE_POLLS-1 拍 ≈ 1.5s。
            if (poll > 0 && screenTextBlank()) {
                Log.i(TAG, "awaitNode a11y black hole, hand off to fallback: $query")
                return null
            }
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
        repeat(RESULT_POLLS) { poll ->
            val minTop = (bridge.screenSize()?.second ?: 0) / RESULT_MIN_TOP_RATIO
            val hit = findTextFuzzy(name)
                .filter { it.top > minTop && inScreen(it) }
                .minByOrNull { it.top }
            if (hit != null) return hit
            // a11y 黑洞早退（2026-10-27 回复慢归因）：微信 FTS 整页对无障碍零暴露
            // （findText=0 且 screenText=''），这里原来固定空等 12 拍 ≈ 6.2s 才轮到
            // ⑦ 的比例兜底坐标。整页连一个文本节点都读不到时，继续轮询不可能出现
            // 新结果——留一拍给转场渲染，之后就交棒给兜底通道。
            if (poll > 0 && screenTextBlank()) {
                Log.i(TAG, "⑦-0 a11y black hole, hand off to ratio fallback")
                return null
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    /** 当前前台窗口是否读不到任何文本（a11y 黑洞指纹）；探针自身失败按「非黑洞」处理。 */
    private fun screenTextBlank(): Boolean =
        runCatching { bridge.screenText(BLACKHOLE_PROBE_NODES) }.getOrNull().isNullOrBlank()

    /** 节点中心是否在屏幕内（screenSize 不可用时放行，交由 tap 自身防护）。 */
    private fun inScreen(node: TextNode): Boolean {
        val size = bridge.screenSize() ?: return true
        return node.centerX in 0 until size.first && node.centerY in 0 until size.second
    }

    /**
     * 标题含名字判定（②④④½⑦⑧ 共用）：大小写不敏感 + 空白无关 + 中英文片段
     * 模糊匹配——微信聊天页标题可能是「吴瑞曦topwayricky」，也可能只露出
     * 中文段或英文备注（或带分词空格），精确子串会 miss。
     */
    private fun titleMatches(title: String?, name: String): Boolean {
        if (title == null) return false
        val compactTitle = title.lowercase().filter { !it.isWhitespace() }
        return matchTokens(name).any { token -> compactTitle.contains(token) }
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
        // 主列表排除（2026-10-27）：微信主列表首条会话名字的 y 可落在顶部条内
        // （1080×2460 实测名字行 y≈320，顶部条高 295，紧邻），仅凭「顶部条有
        // 名字」会把主列表误判成会话页 → 导航早退「成功」而实际没进会话。
        // 底部 Tab 锚是主列表独有指纹（会话页底部是输入栏、搜索页是键盘）。
        val tabHits = mainListTabHits(lines, height)
        val stillMainList = tabHits >= MAIN_LIST_TAB_MIN_HITS
        if (lines.isEmpty()) {
            // 诊断（黑帧/尺寸异常线索）：帧到手但一个字都没识别到。
            Log.w(
                TAG,
                "⑧-ocr zero lines, frame=" +
                    (visionFrame?.let { "${it.width}x${it.height}" } ?: "a11y-capture"),
            )
        }
        Log.i(
            TAG,
            "⑧-ocr nameHit=$nameHit cancelHit=$cancelHit tabHits=$tabHits lines=${strip.size}",
        )
        return nameHit && !cancelHit && !stillMainList
    }

    /** [a]/[b] 公共前缀长度（大小写不敏感由调用方保证）。 */
    private fun commonPrefixLen(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    /**
     * 视觉主列表锚（2026-10-27 稳定性修复，a11y 黑洞场景）：底部 Tab 条
     * （[MAIN_LIST_TAB_ZONE_RATIO] 以下）出现 ≥ [MAIN_LIST_TAB_MIN_HITS] 个
     * 主列表专属词即判定「已在 IM 主列表」——聊天页底部是输入栏、搜索页底部
     * 是键盘/空白，均无此组词。用于 ⑤ 归位时决定「不需要 back」。
     *
     * Shizuku 不可用/取帧失败一律返回 false（回落旧 back 逻辑），不新增失败面。
     */
    private suspend fun looksLikeMainListByOcr(): Boolean {
        val frame = runCatching { frameSource.grabFrame().getOrNull() }.getOrNull() ?: return false
        val lines = runCatching { ocr.recognize(bitmapToPng(frame)).getOrNull() }.getOrNull()
            ?: return false
        val hits = mainListTabHits(lines, frame.height)
        val hit = hits >= MAIN_LIST_TAB_MIN_HITS
        Log.i(TAG, "⑤ vision main-list check hits=$hits hit=$hit lines=${lines.size}")
        return hit
    }

    /**
     * 主列表底部 Tab 锚命中数（纯函数）：底部 [MAIN_LIST_TAB_ZONE_RATIO] 带内
     * 命中主列表专属 Tab 词的行数。⑤ 归位判定、视觉直达、⑧ 进页确认三处共用
     * 同一口径——「还在主列表」在三处含义完全一致，任何一处口径漂移都会造成
     * 误判（④/⑧ 把主列表当会话页，或 ⑤ 把会话页当主列表反复 back）。
     */
    private fun mainListTabHits(lines: List<OcrLine>, frameHeight: Int): Int {
        val zoneTop = (frameHeight * MAIN_LIST_TAB_ZONE_RATIO).toInt()
        return lines.count { line ->
            line.centerY >= zoneTop && MAIN_LIST_TAB_TOKENS.any { line.text.contains(it) }
        }
    }

    /**
     * 主列表视觉直达（2026-10-27 真机 `im_nav_contact_not_found` 根因修复，
     * 自动档启动 19.8s 失败 → ~2s 成功的那条路）：
     *
     * 微信主列表整页 a11y 黑洞（findText 恒空、screenText=''），④½/⑤½ 的节点
     * 直达天然失效；而「最近聊过的人就在列表里」是最常见场景——OCR 一帧找
     * 联系人名所在行，点行中心即进会话，**全程不依赖任何注入通道**（Shizuku
     * 关、FTS 输入框吞字也照样能用），绕过「搜索 → 输入 → 结果」三跳。
     *
     * 每页一次「取帧 → OCR」：底部 Tab 锚确认在主列表（否则立即返回 false，
     * 不误点）；名字行须在标题带以下、Tab 带以上；取最靠上者（列表按最近
     * 会话排序，首条即最靠前）。命中即 tap → 进页确认（标题节点或视觉判定），
     * 确认失败 back 回列表继续找——绝不把「点了没进」当成成功。
     *
     * 未命中/未进页则上滑一屏再找，最多 [LIST_SCAN_PAGES] 页（目标常被置顶与
     * 新消息推到首屏之后；真机复测目标会话在置顶折叠区时首屏根本看不到）。
     */
    private suspend fun tapMainListContact(name: String): Boolean {
        var pinnedExpanded = false
        repeat(LIST_SCAN_PAGES) { page ->
            val frame = runCatching { frameSource.grabFrame().getOrNull() }.getOrNull()
            if (frame == null) {
                Log.w(TAG, "list-scan page=$page frame unavailable")
                return false
            }
            val lines = runCatching { ocr.recognize(bitmapToPng(frame)).getOrNull() }.getOrNull()
            if (lines == null) {
                Log.w(TAG, "list-scan page=$page ocr unavailable")
                return false
            }
            val tabHits = mainListTabHits(lines, frame.height)
            if (tabHits < MAIN_LIST_TAB_MIN_HITS) {
                Log.i(TAG, "list-scan page=$page not main list (tabHits=$tabHits), abort")
                return false
            }
            val minTop = (frame.height * LIST_NAME_MIN_TOP_RATIO).toInt()
            val maxBottom = (frame.height * MAIN_LIST_TAB_ZONE_RATIO).toInt()
            val hit = lines.asSequence()
                .filter { it.centerY in minTop..maxBottom && lineMatches(it.text, name) }
                .minByOrNull { it.centerY }
            Log.i(
                TAG,
                "list-scan page=$page lines=${lines.size} hit='${hit?.text}' " +
                    "cy=${hit?.centerY}",
            )
            if (hit != null && bridge.tap(hit.centerX, hit.centerY).isSuccess) {
                delay(POST_TAP_DELAY_MS)
                if (confirmEnteredChat(name)) return true
                Log.w(TAG, "list-scan tap not entered chat, back to list")
                runCatching { bridge.pressKey("back") }
                delay(POST_TAP_DELAY_MS)
            }
            // 置顶折叠区展开（2026-10-27 真机根因之一）：目标会话被置顶时**不在
            // 普通列表里**（真机 47 个置顶、目标恰在其中），首屏与翻页都找不到。
            // 「折叠置顶聊天」行只在折叠态存在，点它是纯展开（不会把已展开的
            // 置顶区折起来），故只做一次、失败即继续走原流程。
            if (hit == null && !pinnedExpanded) {
                val header = lines.firstOrNull {
                    it.text.contains(PINNED_COLLAPSED_TOKEN) &&
                        it.centerY in minTop..maxBottom
                }
                if (header != null) {
                    Log.i(TAG, "list-scan expand pinned group (${header.centerX},${header.centerY})")
                    bridge.tap(header.centerX, header.centerY)
                    delay(POST_TAP_DELAY_MS)
                    pinnedExpanded = true
                    return@repeat
                }
            }
            if (page < LIST_SCAN_PAGES - 1) {
                val size = bridge.screenSize() ?: return false
                val x = size.first / 2
                bridge.swipe(
                    x,
                    (size.second * LIST_SWIPE_FROM_Y_RATIO).toInt(),
                    x,
                    (size.second * LIST_SWIPE_TO_Y_RATIO).toInt(),
                    LIST_SWIPE_MS,
                )
                delay(POST_TAP_DELAY_MS)
            }
        }
        return false
    }

    /**
     * 进页确认（视觉直达专用）：窗口标题通道（企微等节点树可读的 IM）或
     * 视觉通道（顶部条名字 + 非主列表 + 非搜索页）。两者都不可判定即 false
     * ——宁可继续搜索也不把「点了但没进会话」当成功。
     */
    private suspend fun confirmEnteredChat(name: String): Boolean {
        if (titleMatches(bridge.windowTitle(), name)) {
            Log.i(TAG, "list-scan entered via window title")
            return true
        }
        return chatPageVisibleByOcr(name)
    }

    /**
     * 发送前会话页守卫（2026-10-27 真机 12:16/12:17 `send_button_not_found`
     * 根因）：导航自称 Ok 后页面可能已漂移到主列表/通讯录页（微信主列表与
     * 聊天页同为 LauncherUI），发送链只校验前台包名 → 在非会话页注入、
     * locate 必败，还在输入框留下草稿。
     *
     * 判定口径与 ⑧ 视觉验证一致：activity ∈ [ImPages.chatMarkers] + 顶部条
     * OCR 命中联系人名且无「取消」（排除 FTS 搜索页）。
     *
     * @return true=当前页就是目标会话；false=明确不是（主列表/通讯录/搜索页）；
     *   null=无法判定（取帧/OCR 不可用），调用方**不得**据此拦截发送。
     */
    suspend fun chatPageTitleMatches(imPackage: String, contactName: String): Boolean? =
        withContext(Dispatchers.IO) {
            val name = contactName.trim()
            if (name.isEmpty()) return@withContext null
            val activity = runCatching { shizuku.foregroundActivity() }.getOrNull()
                ?: return@withContext null
            if (pagesOf(imPackage).chatMarkers.none { activity.contains(it) }) {
                Log.w(TAG, "guard activity=$activity not a chat host")
                return@withContext false
            }
            val frame = runCatching { frameSource.grabFrame().getOrNull() }.getOrNull()
                ?: return@withContext null
            headerNameVerdict(name, frame)
        }

    /** 顶部条 OCR 名字判定：null=OCR 无输出（不可判定）；false=名字未命中或命中「取消」。 */
    private suspend fun headerNameVerdict(name: String, frame: Bitmap): Boolean? {
        val lines = ocr.recognize(bitmapToPng(frame)).getOrNull() ?: return null
        if (lines.isEmpty()) return null
        val stripBottom = (frame.height * HEADER_STRIP_RATIO).toInt()
        val strip = lines.filter { it.centerY < stripBottom }
        val nameHit = nameInOcrLines(strip, name)
        val cancelHit = strip.any { it.text.contains("取消") }
        Log.i(TAG, "guard-ocr nameHit=$nameHit cancelHit=$cancelHit lines=${strip.size}")
        return if (cancelHit) false else nameHit
    }

    /** 投影帧 → PNG bytes（OCR 接口口径；OcrSendLocator 同款压缩）。 */
    private fun bitmapToPng(frame: Bitmap): ByteArray =
        ByteArrayOutputStream().use { sink ->
            frame.compress(Bitmap.CompressFormat.PNG, 100, sink)
            sink.toByteArray()
        }
}
