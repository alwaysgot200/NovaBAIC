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

package com.verlintas.baic2.floatball.rpa.quickfind

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Choreographer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.floatball.auto.ScreenCapture
import com.verlintas.baic2.floatball.rpa.RpaActivity
import com.verlintas.baic2.floatball.rpa.RpaAndroidDevice
import com.verlintas.baic2.floatball.rpa.RpaChromeGate
import com.verlintas.baic2.floatball.rpa.RpaPickCoordinator
import com.verlintas.baic2.floatball.rpa.withVisionSpec
import com.verlintas.baic2.floatball.shizuku.ShizukuGrantActivity
import com.verlintas.rpa.model.RpaModePolicy
import com.verlintas.rpa.model.RpaPickKind
import com.verlintas.rpa.model.RpaPickRequest
import com.verlintas.rpa.model.RpaVisionSpec
import com.verlintas.rpa.store.RpaScriptStore
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 「以图找图」透明全屏入口（方案 §6）：
 *
 * - 主题 `Theme.Translucent.NoTitleBar`：目标 App 始终可见，本页只画命中框
 *   与底部结果卡，不把抓帧铺成背景；
 * - 抓帧前把自身 `decorView.alpha` 置 0（不是 GONE，避免窗口重建），等 2 帧
 *   + [SETTLE_MS] 让合成层彻底不画本页，再走 [RpaAndroidDevice.quickFindFrame]
 *   （Shizuku / 投影，空白帧显式报因），回调 `finally` 恢复 alpha；
 * - 退出（含返回键）清 `cacheDir/rpa/quickfind`，并清 [RpaChromeGate] 的
 *   `quick_find` 闸门，让 FloatBallService 恢复被本工具隐藏的悬浮球；
 * - 不使用 a11y 截图：与「无 a11y 视觉线路」的执行面保持同源。
 *
 * 清单刻意**不加** `noHistory`：本页用 `PickVisualMedia` 选图，选图期间本页
 * 会被别的 Activity 覆盖，`noHistory` 会把本页提前销毁导致结果回调丢失。
 */
@AndroidEntryPoint
class RpaQuickFindActivity : ComponentActivity() {

    @Inject
    lateinit var device: RpaAndroidDevice

    @Inject
    lateinit var screenCapture: ScreenCapture

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var scriptStore: RpaScriptStore

    @Inject
    lateinit var pickCoordinator: RpaPickCoordinator

    @Inject
    lateinit var chromeGate: RpaChromeGate

    /** 应用拾取结果期间禁用重复点击；模板写盘 + 脚本落盘都在 IO 线程。 */
    private var applyingPick = false

    /** 只回报一次，避免配置变更重建等路径重复拉起悬浮球。 */
    private var finishedNotified = false

    /**
     * 选图 / 授权页等外部页面覆盖本页期间为 true：`onStop` 不清退。回到本页
     * （`onResume`）即复位。用于区分「用户真正离开工具」与「临时去选图/授权」。
     */
    private var externalFlowActive = false

    override fun attachBaseContext(newBase: Context) {
        val language = AppLocaleStore.readFrom(newBase)
        val tag = language.languageTag
        super.attachBaseContext(
            if (tag == null) newBase else newBase.withQuickFindLocale(Locale.forLanguageTag(tag)),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明浮层铺满全屏（含状态栏/导航栏区域），由 Compose 侧自行加 inset padding。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setDimAmount(0f)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        // 拾取请求在本页生命周期内不变：在进入 composition 前读一次，避免在
        // 组合期做 cache 目录 IO。
        val pickRequest = pickCoordinator.current()?.takeIf { it.kind == RpaPickKind.VISION }
        setContent {
            val themeMode by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val accent by settingsRepository.accentColor
                .collectAsStateWithLifecycle(initialValue = AccentColor.BLUE)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            Baic2Theme(darkTheme = darkTheme, accent = accent) {
                RpaQuickFindScreen(
                    device = device,
                    captureFrame = { captureWithSelfHidden() },
                    grantProjection = {
                        // 授权编排必须发生在本 App 前台 Activity（ScreenCapture 约定）；
                        // 弹窗可能整页盖住本页，先标记外部流程，避免 onStop 误清退。
                        externalFlowActive = true
                        try {
                            runCatching { screenCapture.ensureProjection() }.getOrDefault(false)
                        } finally {
                            // 弹窗未盖住本页时不会有下一次 onResume，这里主动复位；
                            // 若本页仍被盖住，复位后也不会再触发 onStop，安全。
                            externalFlowActive = false
                        }
                    },
                    onOpenShizuku = {
                        externalFlowActive = true
                        runCatching {
                            startActivity(Intent(this, ShizukuGrantActivity::class.java))
                        }.onFailure { externalFlowActive = false }
                    },
                    onExternalFlow = { externalFlowActive = true },
                    onClose = { finish() },
                    pickRequest = pickRequest,
                    onApplyPick = { spec, bitmap, templateId ->
                        applyPick(pickRequest, spec, bitmap, templateId)
                    },
                    onCancelPick = {
                        pickCoordinator.cancel()
                        Toast.makeText(
                            this@RpaQuickFindActivity,
                            R.string.float_rpa_pick_cancelled,
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    },
                )
            }
        }
    }

    /**
     * 自隐抓帧：`alpha = 0` 后至少等 2 帧 + [SETTLE_MS]，确保目标 App 的画面
     * 已经合成上来；无论成功失败都在 `finally` 恢复，绝不把页面永久隐藏。
     */
    private suspend fun captureWithSelfHidden(): Result<Bitmap> {
        val decor = window.decorView
        decor.alpha = 0f
        return try {
            repeat(SELF_HIDE_FRAMES) { awaitFrame() }
            delay(SETTLE_MS)
            device.quickFindFrame()
        } finally {
            decor.alpha = 1f
        }
    }

    /** 挂一次 vsync 回调；Activity 销毁导致的取消直接忽略。 */
    private suspend fun awaitFrame(): Unit = suspendCancellableCoroutine { continuation ->
        Choreographer.getInstance().postFrameCallback {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    /** 拾取回填失败时同时携带「哪一步失败」与细节，供 Toast 选择文案。 */
    private class PickApplyException(
        val messageRes: Int,
        val detail: String,
    ) : Exception(detail)

    /**
     * 拾取回填（方案 §7.8）：模板先写 `filesDir/rpa/templates/<scriptId>/`，再把
     * [RpaVisionSpec] 写进对应步骤，最后带 scriptId / stepId 重开编辑器。
     *
     * 只接受 VISION 线：a11y 拾取走布局分析覆盖层，不经过本页。成功提示由
     * [RpaActivity] 统一弹出，避免两页连续重复 Toast。
     */
    private fun applyPick(
        request: RpaPickRequest?,
        spec: RpaVisionSpec,
        template: Bitmap,
        templateId: String,
    ) {
        if (request == null || applyingPick) return
        applyingPick = true
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val png = ByteArrayOutputStream().use { out ->
                        if (!template.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            throw PickApplyException(
                                R.string.float_rpa_pick_save_failed,
                                "template_encode_failed",
                            )
                        }
                        out.toByteArray()
                    }
                    val script = scriptStore.load(request.scriptId)
                        ?: throw PickApplyException(
                            R.string.float_rpa_pick_store_failed,
                            "script_missing",
                        )
                    if (!RpaModePolicy.visionEnabled(script.mode)) {
                        throw PickApplyException(
                            R.string.float_rpa_pick_kind_mismatch,
                            "mode_without_vision",
                        )
                    }
                    // 先落模板再落脚本：脚本里引用的 templateId 必须已经存在。
                    scriptStore.saveTemplate(request.scriptId, templateId, png).getOrElse { error ->
                        throw PickApplyException(
                            R.string.float_rpa_pick_save_failed,
                            error.message ?: "template_save_failed",
                        )
                    }
                    scriptStore.save(script.withVisionSpec(request.stepId, spec))
                        .getOrElse { error ->
                            throw PickApplyException(
                                R.string.float_rpa_pick_store_failed,
                                error.message ?: "script_save_failed",
                            )
                        }
                }
            }
            applyingPick = false
            outcome.fold(
                onSuccess = {
                    pickCoordinator.cancel()
                    runCatching {
                        startActivity(
                            RpaActivity.intent(
                                this@RpaQuickFindActivity,
                                scriptId = request.scriptId,
                                stepId = request.stepId,
                            ),
                        )
                    }.onFailure { error ->
                        Toast.makeText(
                            this@RpaQuickFindActivity,
                            getString(
                                R.string.float_rpa_pick_store_failed,
                                error.message ?: "open_editor_failed",
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    finish()
                },
                onFailure = { error ->
                    val res = (error as? PickApplyException)?.messageRes
                        ?: R.string.float_rpa_pick_store_failed
                    val detail = (error as? PickApplyException)?.detail
                        ?: error.message
                        ?: "apply_failed"
                    Toast.makeText(
                        this@RpaQuickFindActivity,
                        // 模式不支持是完整句子，只有模板/脚本失败才带细节。
                        if (res == R.string.float_rpa_pick_kind_mismatch) {
                            getString(res)
                        } else {
                            getString(res, detail)
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 从选图/授权页回到本页：外部流程结束，后续真正离开时按 onStop 清退。
        externalFlowActive = false
    }

    override fun onStop() {
        super.onStop()
        // 用户离开工具（Home / 切到别的任务）立即清退：本页在独立任务且
        // 不进最近任务，留着会变成回不去的「幽灵页」，还一直占着悬浮球。
        // 选图/授权等临时覆盖、旋转重建、主动关闭都不在此列。
        if (isChangingConfigurations || isFinishing || externalFlowActive) return
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 旋转/其它配置变更会重建 Activity：此时本工具仍在使用，不能清缓存或恢复球。
        if (isChangingConfigurations || finishedNotified) return
        finishedNotified = true
        runCatching { File(cacheDir, CACHE_DIR).deleteRecursively() }
        // 悬浮球由 FloatBallService 观察 RpaChromeGate；进程内直接清闸门即可。
        // 不再从后台 startForegroundService（Android 12+ 可能被系统拒绝且无提示）。
        chromeGate.show(RpaChromeGate.REASON_QUICK_FIND)
    }

    companion object {
        /** 临时模板目录（相对 cacheDir），退出即删；不写 filesDir/rpa。 */
        private const val CACHE_DIR = "rpa/quickfind"

        /** 自隐后等待的合成帧数（2 帧覆盖大多数 60/120Hz 设备的一帧延迟）。 */
        private const val SELF_HIDE_FRAMES = 2

        /** 自隐稳定时间：给窗口 alpha 动画 / SurfaceFlinger 合成留余量。 */
        private const val SETTLE_MS = 160L
    }
}

/** 与 RpaActivity / ShizukuGrantActivity 同口径的语言上下文。 */
private fun Context.withQuickFindLocale(locale: Locale): Context {
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}
