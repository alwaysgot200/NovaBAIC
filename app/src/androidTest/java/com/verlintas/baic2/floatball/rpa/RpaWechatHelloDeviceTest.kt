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
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.verlintas.rpa.engine.RpaRunner
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaRunEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
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
 * 4. 设备已给好参谋开悬浮窗权限（后台拉起微信需要）。
 *
 * 运行（真机序列号以 `adb devices` 为准）：
 * ```
 * .\gradlew.bat :app:connectedDebugAndroidTest `
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.verlintas.baic2.floatball.rpa.RpaWechatHelloDeviceTest
 * adb -s <serial> shell am instrument -w `
 *   -e class com.verlintas.baic2.floatball.rpa.RpaWechatHelloDeviceTest `
 *   -e contactName "吴瑞曦topwayricky" `
 *   com.verlintas.baic2.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 断言（第 8 步不占脚本）：7 步全部成功后，聊天页屏幕文本必须出现「你好」。
 * 失败输出逐步 line/reason（含线路回退），直接定位是选择器漂移还是发送失败。
 */
@RunWith(AndroidJUnit4::class)
class RpaWechatHelloDeviceTest {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DeviceEntryPoint {
        fun rpaDevice(): RpaAndroidDevice
    }

    @Test
    fun sevenStepWechatFixtureSendsHelloToDemoContact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()

        // 1) 微信没装（模拟器）→ 明确跳过，不制造假红/假绿。
        val wechatInstalled =
            isPackageInstalled(context, RpaWechatHelloFixture.DEFAULT_IM_PACKAGE)
        Log.i(TAG, "instrumented start: model=${android.os.Build.MODEL} " +
            "wechatInstalled=$wechatInstalled")
        assumeTrue(
            "微信未安装（$DEFAULT_IM_PACKAGE），跳过真机夹具",
            wechatInstalled,
        )

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

        val device = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DeviceEntryPoint::class.java,
        ).rpaDevice()

        // 3) 仪器测试会重启 App 进程，无障碍服务由系统重新绑定，给它一点时间。
        awaitAnyLineReady(device)
        val caps = device.capabilities
        assumeTrue(
            "两条执行线路都不可用（a11yReady=${caps.a11yReady}, " +
                "shizukuReady=${caps.shizukuReady}, visionReady=${caps.visionReady}）：" +
                "请开启好参谋无障碍服务，或启动 Shizuku 并授权投影",
            caps.a11yReady || caps.visionReady,
        )

        val contactName = args.getString(ARG_CONTACT_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: RpaWechatHelloFixture.DEFAULT_CONTACT_NAME
        val script = RpaWechatHelloFixture.build(contactName = contactName)
        val events = mutableListOf<RpaRunEvent>()

        Log.i(TAG, "start fixture: contact=$contactName caps=$caps")
        val result = runBlocking {
            RpaRunner(device)
                .run(script) { event ->
                    events += event
                    Log.i(TAG, describe(event))
                }
        }
        val transcript = events.joinToString(separator = "\n") { describe(it) }
        assertTrue(
            "7 步夹具执行失败（contact=$contactName）：\n$transcript",
            result.ok,
        )

        // 4) 独立校验发送结果：等一拍让气泡上屏，再读两种线路的屏内文本。
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
    }

    /** 等系统重新绑定无障碍服务 / Shizuku 连接，最多 [READY_TIMEOUT_MS]。 */
    private fun awaitAnyLineReady(device: RpaAndroidDevice) {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val caps = device.capabilities
            if (caps.a11yReady || caps.visionReady) return
            Thread.sleep(READY_POLL_MS)
        }
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

    private companion object {
        const val TAG = "RpaWechatDeviceTest"
        const val DEFAULT_IM_PACKAGE = RpaWechatHelloFixture.DEFAULT_IM_PACKAGE
        const val EXPECTED_TEXT = "你好"
        const val ARG_CONTACT_NAME = "contactName"
        const val READY_TIMEOUT_MS = 20_000L
        const val READY_POLL_MS = 500L
        const val SEND_SETTLE_MS = 1_500L
    }
}
