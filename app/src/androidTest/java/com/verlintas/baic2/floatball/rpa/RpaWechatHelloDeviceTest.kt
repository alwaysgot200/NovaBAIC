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
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.verlintas.baic2.device.api.ShellBridge
import com.verlintas.baic2.device.api.ShellResult
import com.verlintas.baic2.device.api.ShellState
import com.verlintas.baic2.floatball.auto.WechatTreeUnlock
import com.verlintas.rpa.engine.RpaRunner
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaRunEvent
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 7 步微信夹具的**真机**仪器测试通道（`app/src/androidTest`）。
 *
 * 前置（真机手动准备，缺一即 assume 跳过而不是假通过）：
 * 1. 微信已安装并已登录；
 * 2. 屏幕已点亮、已解锁；
 * 3. 至少一条执行线路就绪：无障碍（Assists 服务 + 桥接）或 Shizuku + 投影；
 * 4. 设备已给好参谋开悬浮窗权限（后台拉起微信需要）；
 * 5. 节点树解锁降级通道（一次性）：`adb shell pm grant com.verlintas.baic2
 *    android.permission.WRITE_SECURE_SETTINGS`——`am instrument` 重启进程时
 *    Shizuku binder 推送存在竞态（见 [LocalShellBridge]），未就绪时降级用。
 *
 * 运行（真机序列号以 `adb devices` 为准）：
 * ```
 * .\gradlew.bat :app:connectedDebugAndroidTest `
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.verlintas.baic2.floatball.rpa.RpaWechatHelloDeviceTest
 * adb -s <serial> shell am instrument -w `
 *   -e class com.verlintas.baic2.floatball.rpa.RpaWechatHelloDeviceTest `
 *   -e contactName "吴瑞曦topwayricky" `
 *   -e imPackage "com.tencent.mm" `
 *   -e runLine "auto" `
 *   com.verlintas.baic2.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 运行参数：`-e imPackage` 换目标 IM（默认微信）；`-e runLine` 锁线运行
 * （a11y|vision|auto，auto=自动选线）。imPackage 未安装走 B1 边界断言
 * （app_missing 干净失败、零线路回退）；已安装走 happy path，锁线时断言
 * 全程零 LineFallback 且每步线路都是锁的那条。
 *
 * 断言（第 8 步不占脚本）：7 步全部成功后，聊天页屏幕文本必须出现「你好」。
 * 失败输出逐步 line/reason（含线路回退），直接定位是选择器漂移还是发送失败。
 */
@RunWith(AndroidJUnit4::class)
class RpaWechatHelloDeviceTest {

    @Test
    fun sevenStepWechatFixtureSendsHelloToDemoContact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()

        // 运行参数：-e imPackage 换目标 IM（默认微信）；-e runLine 锁线运行。
        val imPackage = args.getString(ARG_IM_PACKAGE)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_IM_PACKAGE
        val runLine = parseRunLine(args.getString(ARG_RUN_LINE))
        val contactName = args.getString(ARG_CONTACT_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: RpaWechatHelloFixture.DEFAULT_CONTACT_NAME

        // 1) 目标 IM 是否安装决定走 B1 边界还是 happy path；
        //    未安装不再「一跳了之」，而是验证 app_missing 干净失败。
        val imInstalled = isPackageInstalled(context, imPackage)
        Log.i(
            TAG,
            "instrumented start: model=${android.os.Build.MODEL} im=$imPackage " +
                "imInstalled=$imInstalled runLine=${runLine ?: "auto"}",
        )
        if (imInstalled) {
            // 2) 亮屏 + 解锁是自动化点击的物理前置，不满足时让人先处理。
            val power = context.getSystemService(PowerManager::class.java)
            assumeTrue(
                "屏幕未点亮：请先点亮并解锁真机",
                power?.isInteractive == true,
            )
            val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
            assumeTrue(
                "设备处于锁屏：请先解锁真机",
                keyguard?.isKeyguardLocked != true,
            )
        }

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            RpaAndroidDeviceEntryPoint::class.java,
        )
        val device = entryPoint.rpaDevice()

        // 3) 仪器测试会重启 App 进程，两条线由系统重新绑定/重推，给它一点时间。
        //    锁线运行必须等**锁的那条线**就绪（等「任一线」会因另一线先就绪
        //    提前返回，随后锁线检查秒挂，与外部重绑时机竞态）。
        awaitLineReady(device, runLine)
        // 就绪 ≠ 探针健康（2026-10-31 真机事故：a11y 服务重绑后立即查
        // rootInActiveWindow 会在 binder 层卡死 24 分钟）：先等本线前台探针
        // 真实返回一次再跑夹具，避开「服务刚连上」竞态并留诊断日志。
        awaitProbeAlive(device, runLine)
        val caps = device.capabilities
        assumeTrue(
            "两条执行线路都不可用（a11yReady=${caps.a11yReady}, " +
                "shizukuReady=${caps.shizukuReady}, visionReady=${caps.visionReady}）：" +
                "请开启好参谋无障碍服务，或启动 Shizuku 并授权投影",
            caps.a11yReady || caps.visionReady,
        )
        if (runLine != null) {
            val forcedReady = if (runLine == RpaLine.A11Y) caps.a11yReady else caps.visionReady
            assumeTrue("锁线 $runLine 未就绪：请先开启对应执行线路", forcedReady)
        }

        // 4) 微信 8.x 摘树解锁（AGENTS §8.11）：a11y 线要读目标 IM 的控件树，
        //    而微信在启用服务列表里没有 TalkBack 家族服务时会摘空自身树（空根），
        //    D 类就绪判定必然超时。按 FloatBallService 既有范式在测试会话级临时
        //    启用「随选朗读」解锁，收尾 release 严格配对还原；锁 VISION 线不碰，
        //    「无 a11y」线全程零 a11y 依赖独立跑通。
        var shell: ShellBridge? = null
        var unlockHeld = false
        if (imInstalled && runLine != RpaLine.VISION) {
            // Shizuku 通道优先生效；binder 投递竞态未就绪时降级（见 LocalShellBridge）。
            val realShell = entryPoint.shellBridge()
            val activeShell = if (awaitShellReady(realShell)) {
                Log.i(TAG, "shell: shizuku ready")
                realShell
            } else {
                Log.w(
                    TAG,
                    "shell: shizuku not ready (${realShell.state.value}), " +
                        "fallback to local shell",
                )
                LocalShellBridge(context)
            }
            shell = activeShell
            // 解锁只看活动窗口的树：先把目标 IM 拉到前台，让摘树判定看的是
            // 目标 App 的窗口（而非本 App），再交解锁门判断。
            runCatching {
                runBlocking { device.launchApp(imPackage, runLine ?: RpaLine.A11Y, false, false) }
            }.onFailure { Log.w(TAG, "pre-launch $imPackage failed: $it") }
            when (val outcome = runBlocking { WechatTreeUnlock.acquire(context, activeShell) }) {
                is WechatTreeUnlock.Outcome.Held -> {
                    Log.i(TAG, "tree unlock: Held")
                    unlockHeld = true
                    // 写 enabled_accessibility_services 会触发全部 a11y 服务解绑
                    // 重绑：等自身服务回归 + 探针健康再跑（同启动段口径）。
                    awaitLineReady(device, runLine)
                    awaitProbeAlive(device, runLine)
                }
                is WechatTreeUnlock.Outcome.Failed ->
                    assumeTrue(
                        "节点树解锁失败（${outcome.reason}）：请手动开启「随选朗读」后重跑",
                        false,
                    )
                else -> Unit
            }
        }

        try {
            val script = RpaWechatHelloFixture.build(imPackage = imPackage, contactName = contactName)
            val events = mutableListOf<RpaRunEvent>()

            Log.i(TAG, "start fixture: im=$imPackage contact=$contactName caps=$caps")
            val result = runBlocking {
                RpaRunner(device)
                    .run(script, forceLine = runLine) { event ->
                        events += event
                        Log.i(TAG, describe(event))
                    }
            }
            val transcript = events.joinToString(separator = "\n") { describe(it) }

            if (!imInstalled) {
                // B1 边界：目标 IM 未安装 → app_missing 干净失败：不换线掩盖、
                // 不再往下走，末事件 ScriptFinished 如实报因。
                assertFalse(
                    "IM 未安装（$imPackage）时应当失败：\n$transcript",
                    result.ok,
                )
                assertTrue(
                    "失败原因应为 app_missing，实际=${result.detail}：\n$transcript",
                    result.detail.startsWith("app_missing"),
                )
                assertTrue(
                    "B1 禁止线路回退：\n$transcript",
                    events.none { it is RpaRunEvent.LineFallback },
                )
                val finished = events.filterIsInstance<RpaRunEvent.ScriptFinished>().lastOrNull()
                assertNotNull("末事件应为 ScriptFinished：\n$transcript", finished)
                assertFalse(
                    "ScriptFinished 应为失败：\n$transcript",
                    finished!!.ok,
                )
                assertTrue(
                    "ScriptFinished 应报 app_missing，实际=${finished.detail}：\n$transcript",
                    finished.detail.startsWith("app_missing"),
                )
                Log.i(TAG, "b1 ok: app_missing abort ($imPackage)")
                return
            }

            assertTrue(
                "7 步夹具执行失败（im=$imPackage contact=$contactName " +
                    "runLine=${runLine ?: "auto"}）：\n$transcript",
                result.ok,
            )

            // 5) 锁线运行（§4.5）：全程零 LineFallback，每步线路都是锁的那条。
            if (runLine != null) {
                assertTrue(
                    "锁线 $runLine 运行出现线路回退：\n$transcript",
                    events.none { it is RpaRunEvent.LineFallback },
                )
                val stepLines = events.filterIsInstance<RpaRunEvent.StepStarted>().map { it.line }
                assertTrue(
                    "锁线 $runLine 运行出现异线步骤：$stepLines：\n$transcript",
                    stepLines.isNotEmpty() && stepLines.all { it == runLine },
                )
            }

            // 6) 独立校验发送结果：等一拍让气泡上屏，再读两种线路的屏内文本。
            Thread.sleep(SEND_SETTLE_MS)
            val a11yLines = runCatching { runBlocking { device.textLines(RpaLine.A11Y) } }
                .getOrDefault(emptyList())
            val visionLines = if (device.capabilities.visionReady) {
                runCatching { runBlocking { device.textLines(RpaLine.VISION) } }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val found = (a11yLines + visionLines).any { it.contains(EXPECTED_TEXT) }
            assertTrue(
                "发送后聊天页未见「$EXPECTED_TEXT」。\n" +
                    "a11y 文本=${a11yLines.take(40)}\n" +
                    "vision 文本=${visionLines.take(40)}\n" +
                    "运行记录：\n$transcript",
                found,
            )
            Log.i(TAG, "fixture ok: 「$EXPECTED_TEXT」已在聊天页出现")
        } finally {
            // 会话收尾配对还原（release 幂等，非 Held 零动作）。
            val activeShell = shell
            if (unlockHeld && activeShell != null) {
                runBlocking { WechatTreeUnlock.release(context, activeShell) }
            }
        }
    }

    /** `-e runLine` 解析：a11y|vision 锁线，auto / 空 = 自动选线。 */
    private fun parseRunLine(raw: String?): RpaLine? {
        return when (raw?.trim()?.lowercase().orEmpty()) {
            "", "auto" -> null
            "a11y" -> RpaLine.A11Y
            "vision" -> RpaLine.VISION
            else -> throw IllegalArgumentException(
                "runLine 取值非法：$raw（可选 a11y|vision|auto）",
            )
        }
    }

    /**
     * 等系统重新绑定无障碍服务 / 重推 Shizuku 连接，最多 [READY_TIMEOUT_MS]。
     * [required] 非空时等**指定线**就绪（锁线语义），为空时等任一线。
     */
    private fun awaitLineReady(device: RpaAndroidDevice, required: RpaLine?) {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val caps = device.capabilities
            val ready = when (required) {
                RpaLine.A11Y -> caps.a11yReady
                RpaLine.VISION -> caps.visionReady
                null -> caps.a11yReady || caps.visionReady
            }
            if (ready) return
            Thread.sleep(READY_POLL_MS)
        }
    }

    /**
     * 等本线前台探针真实返回一次（最多 [PROBE_ALIVE_TIMEOUT_MS]）。
     * 探针是验证「a11y 通道查询可用」的最小动作；连续超时也放行——后续
     * 夹具自身会按 launch_timeout 有界收口，这里只负责避开重绑瞬时竞态。
     */
    private fun awaitProbeAlive(device: RpaAndroidDevice, required: RpaLine?) {
        val line = required ?: if (device.capabilities.a11yReady) RpaLine.A11Y else RpaLine.VISION
        val deadline = SystemClock.elapsedRealtime() + PROBE_ALIVE_TIMEOUT_MS
        var last: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runCatching { runBlocking { device.foregroundPackage(line) } }.getOrNull()
            if (!last.isNullOrBlank()) break
            Thread.sleep(READY_POLL_MS)
        }
        Log.i(TAG, "probe alive: line=$line fg=$last")
    }

    /** 等 Shizuku shell 连接就绪（最多 [SHELL_READY_TIMEOUT_MS]）；锁线语义同上。 */
    private fun awaitShellReady(shell: ShellBridge): Boolean {
        val deadline = SystemClock.elapsedRealtime() + SHELL_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (shell.state.value == ShellState.Ready) return true
            Thread.sleep(READY_POLL_MS)
        }
        return shell.state.value == ShellState.Ready
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
        }.isSuccess

    private fun describe(event: RpaRunEvent): String = when (event) {
        is RpaRunEvent.ScriptStarted -> "script_started line=${event.line}"
        is RpaRunEvent.StepStarted ->
            "step#${event.index} start ${event.step.label} line=${event.line}"
        is RpaRunEvent.StepFinished ->
            "step#${event.index} ${if (event.ok) "ok" else "FAIL"} " +
                "${event.step.label} detail=${event.detail}"
        is RpaRunEvent.LineFallback ->
            "step#${event.index} fallback ${event.from}->${event.to}: ${event.reason}"
        is RpaRunEvent.Log -> "log: ${event.message}"
        is RpaRunEvent.ScriptFinished ->
            "script_finished ok=${event.ok} failedStep=${event.failedStepIndex} " +
                "detail=${event.detail}"
    }

    /**
     * 节点树解锁的降级 shell 通道（仅真机仪器测试装置）。
     *
     * 2026-10-31 真机取证：Shizuku 服务器只在 App 进程启动时推送一次 binder，
     * 而 `am instrument` 重启进程的窗口恰使这次推送落在
     * 「Unable to launch app ... process is bad」上且不重试——仪器进程永远等不到
     * binder（普通启动的进程可以）。故 Shizuku 未就绪时降级为同 UID 直写。
     *
     * `settings put|delete secure` 一律改走 [Settings.Secure] API（2026-10-31
     * 真机二次取证）：app uid 跑 `settings` CLI 必挂——命令入口先解析目标用户，
     * `getCurrentUser()` 要 `INTERACT_ACROSS_USERS`、`--user 0` 要 `QUERY_USERS`，
     * app uid 都没有，`WRITE_SECURE_SETTINGS` 根本走不到；而写 API 只查
     * `WRITE_SECURE_SETTINGS`（已由 `pm grant` 一次性授权），无用户解析门槛。
     * 写入失败返回 [ShellResult.Unavailable] 显式报因，绝不误报成功。
     * 解锁入口仍是 `WechatTreeUnlock` 唯一入口，acquire/release 严格配对语义不变。
     */
    private class LocalShellBridge(private val context: Context) : ShellBridge {

        override val state: StateFlow<ShellState> = MutableStateFlow(ShellState.Ready)

        override fun requestPermission() = Unit

        override suspend fun exec(
            command: String,
            timeoutMs: Long,
            maxOutputChars: Int,
        ): ShellResult = withContext(Dispatchers.IO) {
            settingsViaApi(command.trim())?.let { return@withContext it }
            runCatching {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val stdout = StringBuilder()
                val stderr = StringBuilder()
                val outThread = Thread {
                    runCatching {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine { line -> stdout.appendLine(line) }
                        }
                    }
                }
                val errThread = Thread {
                    runCatching {
                        process.errorStream.bufferedReader().use { reader ->
                            reader.forEachLine { line -> stderr.appendLine(line) }
                        }
                    }
                }
                outThread.start()
                errThread.start()
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) runCatching { process.destroy() }
                outThread.join(1_000)
                errThread.join(1_000)
                val out = stdout.toString().trim()
                val err = stderr.toString().trim()
                if (!finished) {
                    ShellResult.Timeout(out)
                } else {
                    ShellResult.Output(
                        stdout = out.take(maxOutputChars),
                        stderr = err.take(maxOutputChars / 2),
                        exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
                        truncated = out.length > maxOutputChars ||
                            err.length > maxOutputChars / 2,
                    )
                }
            }.getOrElse { ShellResult.Unavailable(it.message ?: it.javaClass.simpleName) }
        }

        /**
         * `settings put|delete secure <key> [value]` 走 [Settings.Secure] API；
         * 非此形态返回 null，照旧走 `sh -c`。delete = putString(null)（同
         * `settings delete` 语义：删除该设置项）。
         */
        private fun settingsViaApi(command: String): ShellResult? {
            val match = SETTINGS_CMD.matchEntire(command) ?: return null
            val op = match.groupValues[1]
            val key = match.groupValues[2]
            val rawValue = match.groupValues[3]
            return runCatching {
                when (op) {
                    "put" -> Settings.Secure.putString(
                        context.contentResolver,
                        key,
                        unquoteShellArg(rawValue),
                    )
                    else -> Settings.Secure.putString(context.contentResolver, key, null)
                }
                ShellResult.Output(
                    stdout = "ok",
                    stderr = "",
                    exitCode = 0,
                    truncated = false,
                )
            }.getOrElse { ShellResult.Unavailable(it.message ?: it.javaClass.simpleName) }
        }

        /** 还原 [com.verlintas.baic2.floatball.auto.ShizukuInjector.shellQuote] 的单引号包裹。 */
        private fun unquoteShellArg(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.length >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
                return trimmed.substring(1, trimmed.length - 1).replace("'\\''", "'")
            }
            return trimmed
        }

        private val SETTINGS_CMD =
            Regex("""settings (put|delete) secure (\S+)(?: (.*))?""")
    }

    private companion object {
        const val TAG = "RpaWechatDeviceTest"
        const val DEFAULT_IM_PACKAGE = RpaWechatHelloFixture.DEFAULT_IM_PACKAGE
        const val EXPECTED_TEXT = "你好"
        const val ARG_CONTACT_NAME = "contactName"
        const val ARG_IM_PACKAGE = "imPackage"
        const val ARG_RUN_LINE = "runLine"
        const val READY_TIMEOUT_MS = 20_000L
        const val READY_POLL_MS = 500L
        const val SHELL_READY_TIMEOUT_MS = 5_000L
        const val PROBE_ALIVE_TIMEOUT_MS = 10_000L
        const val SEND_SETTLE_MS = 1_500L
    }
}
