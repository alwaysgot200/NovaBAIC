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

package com.verlintas.baic2.floatball.auto

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.verlintas.baic2.floatball.rpa.analyze.RpaAnalyzeCapture
import com.verlintas.baic2.floatball.rpa.analyze.RpaRawNode
import com.ven.assists.service.AssistsService
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「微信节点树解锁不得以触摸探索常开为代价」的真机回归锚
 * （2026-10-29 性能事故后定版，取代旧的「脉冲打开触摸探索」断言）。
 *
 * 背景：微信 8.x `smartDisableAccessibility` 在「启用服务无微信可信白名单组件
 * 且触摸探索关闭」时摘空自身无障碍树（真机 381 字节空根）。解锁的**主路线**是
 * 微信可信白名单桩服务（`org.autojs.autojs/`
 * `com.stardust.autojs.core.accessibility.AccessibilityService`，由
 * `dev/build-a11y-whitelist-stub.ps1` 构建安装并启用）——桩出现在启用服务列表
 * 即让微信放行，全程零触摸探索。此前用静态 `flagRequestTouchExplorationMode`
 * 解锁导致触摸探索随服务绑定常开、全设备触摸变「先探索后激活」、悬浮球拖不动
 * （2026-10-29 用户投诉定案），本测试锁定「微信树可读」与「触摸探索保持关闭」
 * 必须同时成立。[TouchExplorationGate] 仅保留为树退化时的兜底脉冲（MIUI/HyperOS
 * 上运行时 setServiceInfo 为空操作），树就绪时必须跳过。
 *
 * 前置（真机手动准备，缺一即 assume 跳过）：
 * 1. 微信已安装并已登录；
 * 2. 屏幕已点亮、已解锁；
 * 3. 好参谋无障碍服务已启用；
 * 4. 白名单桩服务已启用（`adb shell settings get secure enabled_accessibility_services`
 *    含桩组件）。
 *
 * 运行：
 * ```
 * .\gradlew.bat :app:connectedDebugAndroidTest `
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.verlintas.baic2.floatball.auto.TouchExplorationGateDeviceTest
 * adb -s <serial> shell am instrument -w `
 *   -e class com.verlintas.baic2.floatball.auto.TouchExplorationGateDeviceTest `
 *   com.verlintas.baic2.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 判定提醒（2026-10-28 教训）：`am instrument` 对 `assumeTrue` 静默跳过同样报
 * `OK`，不能只看 OK——要用「实际断言数 + 就绪标记/截图」复核。
 */
@RunWith(AndroidJUnit4::class)
class TouchExplorationGateDeviceTest {

    /**
     * 生产采集路径端到端：微信前台 [RpaAnalyzeCapture.capture]（悬浮球「布局分析」
     * 同一代码路径）拿到真实节点树，且全程触摸探索保持关闭。
     */
    @Test
    fun wechatTreeReadableWithoutTouchExploration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        assumeTrue("微信未安装，跳过真机夹具", isPackageInstalled(context, WECHAT))
        val power = context.getSystemService(PowerManager::class.java)
        assumeTrue("屏幕未点亮：请先点亮并解锁真机", power?.isInteractive == true)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assumeTrue("设备处于锁屏：请先解锁真机", keyguard?.isKeyguardLocked != true)

        runBlocking {
            // 仪器测试重启 App 进程后无障碍服务由系统重新绑定，等待就绪。
            val bound = poll(SERVICE_BIND_TIMEOUT_MS) { AssistsService.getOrNull() != null }
            assumeTrue(
                "无障碍服务未启用/未绑定，请在 设置 → 无障碍 开启好参谋",
                bound,
            )
            val manager = context.getSystemService(AccessibilityManager::class.java)
            assumeTrue("无法获取 AccessibilityManager", manager != null)
            assumeTrue(
                "白名单桩未启用：先运行 dev/build-a11y-whitelist-stub.ps1 -Install 并启用桩服务",
                isStubEnabled(manager),
            )
            assertFalse(
                "触摸探索处于开启状态：禁止静态 flagRequestTouchExplorationMode 常开" +
                    "（2026-10-29 性能事故回归）",
                manager.isTouchExplorationEnabled,
            )

            instrumentation.uiAutomation
                .executeShellCommand("am start -n $WECHAT/.ui.LauncherUI")
                .close()
            poll(TREE_REBUILD_TIMEOUT_MS) {
                AssistsService.getOrNull()?.rootInActiveWindow?.packageName?.toString() == WECHAT
            }

            val capture = EntryPointAccessors
                .fromApplication(context, CaptureEntryPoint::class.java)
                .rpaAnalyzeCapture()
            val metrics = context.resources.displayMetrics
            val result = capture.capture(
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                selfPackage = context.packageName,
            )
            val snapshot = result.getOrNull()
            assertTrue(
                "布局分析采集失败：${result.exceptionOrNull()?.message}（白名单桩未生效？）",
                snapshot != null,
            )
            assertTrue(
                "节点退化：nodeCount=${snapshot!!.nodeCount}，期望 ≥2（微信树仍被摘空）",
                snapshot.nodeCount >= 2,
            )
            assertTrue(
                "采集结果缺少微信窗口：${snapshot.windows.map { it.packageName }}",
                snapshot.windows.any { it.packageName == WECHAT },
            )
            val labels = collectLabels(snapshot.windows.map { it.root })
            assertTrue("微信节点树无任何文本/描述（树仍被摘空）", labels.isNotEmpty())

            assertFalse(
                "采集过程打开了触摸探索：脉冲只允许出现在树退化的兜底路径",
                manager.isTouchExplorationEnabled,
            )
        }
    }

    /** 白名单桩生效时节点树本就就绪，[TouchExplorationGate] 不得再请求触摸探索。 */
    @Test
    fun gateDoesNotPulseWhenTreeSettled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        assumeTrue("微信未安装，跳过真机夹具", isPackageInstalled(context, WECHAT))
        runBlocking {
            val bound = poll(SERVICE_BIND_TIMEOUT_MS) { AssistsService.getOrNull() != null }
            assumeTrue("无障碍服务未启用/未绑定", bound)
            val manager = context.getSystemService(AccessibilityManager::class.java)
            assumeTrue("无法获取 AccessibilityManager", manager != null)
            assumeTrue("白名单桩未启用", isStubEnabled(manager))

            instrumentation.uiAutomation
                .executeShellCommand("am start -n $WECHAT/.ui.LauncherUI")
                .close()
            val treeUp = poll(TREE_REBUILD_TIMEOUT_MS) {
                (AssistsService.getOrNull()?.rootInActiveWindow?.childCount ?: 0) >= 2
            }
            assumeTrue("微信节点树未就绪（桩未生效？）", treeUp)

            TouchExplorationGate.acquire()
            try {
                assertFalse(
                    "节点树已就绪仍请求触摸探索：acquire() 必须跳过",
                    manager.isTouchExplorationEnabled,
                )
            } finally {
                TouchExplorationGate.release()
            }
        }
    }

    /** 深度遍历收集节点文本/描述（验证树里有真实内容而非空壳）。 */
    private fun collectLabels(roots: List<RpaRawNode>): List<String> {
        val labels = mutableListOf<String>()
        fun walk(node: RpaRawNode) {
            node.text.takeIf { it.isNotBlank() }?.let { labels += it }
            node.description.takeIf { it.isNotBlank() }?.let { labels += it }
            node.children.forEach { walk(it) }
        }
        roots.forEach { walk(it) }
        return labels
    }

    /** 微信可信白名单桩是否已在启用服务列表中（[STUB_COMPONENT] 组件 ID）。 */
    private fun isStubEnabled(manager: AccessibilityManager?): Boolean = runCatching {
        manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
            .any { it.id?.contains(STUB_COMPONENT) == true }
    }.getOrDefault(false)

    private suspend fun poll(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching { condition() }.getOrDefault(false)) return true
            delay(POLL_INTERVAL_MS)
        }
        return runCatching { condition() }.getOrDefault(false)
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private companion object {
        const val WECHAT = "com.tencent.mm"

        /** 微信可信白名单桩组件（dev/build-a11y-whitelist-stub.ps1 构建）。 */
        const val STUB_COMPONENT =
            "org.autojs.autojs/com.stardust.autojs.core.accessibility.AccessibilityService"
        const val SERVICE_BIND_TIMEOUT_MS = 5_000L
        const val TREE_REBUILD_TIMEOUT_MS = 8_000L
        const val POLL_INTERVAL_MS = 200L
    }

    /** 测试侧 Hilt 入口：取生产采集器 [RpaAnalyzeCapture] 单例（无需 @HiltAndroidTest）。 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CaptureEntryPoint {
        fun rpaAnalyzeCapture(): RpaAnalyzeCapture
    }
}
