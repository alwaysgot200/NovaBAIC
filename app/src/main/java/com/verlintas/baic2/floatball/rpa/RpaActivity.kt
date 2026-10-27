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
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.floatball.shizuku.ShizukuGrantActivity
import com.verlintas.rpa.model.RpaScriptFactory
import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaVisionSpec
import com.verlintas.rpa.store.RpaScriptStore
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * RPA 自动化全屏入口（独立模块 UI）：
 *
 * 脚本库 → 编辑器（三模式 + 动作库拖拽 + 属性面板）→ 布局分析器 / 以图找图
 * 配置 → 运行日志。入口有两处：好参谋设置页与画像页「聊天会话」行的 RPA
 * 按钮；后者只预填 IM 包名，用于新建脚本时少填一项，不再生成任何 Demo。
 */
@AndroidEntryPoint
class RpaActivity : ComponentActivity() {

    @Inject
    lateinit var device: RpaAndroidDevice

    @Inject
    lateinit var store: RpaScriptStore

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun attachBaseContext(newBase: Context) {
        val language = AppLocaleStore.readFrom(newBase)
        val tag = language.languageTag
        super.attachBaseContext(
            if (tag == null) newBase else newBase.withRpaLocale(Locale.forLanguageTag(tag)),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initialPackage = intent.getStringExtra(EXTRA_IM_PACKAGE)
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
            val view = LocalView.current
            androidx.compose.runtime.SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            Baic2Theme(darkTheme = darkTheme, accent = accent) {
                RpaApp(
                    device = device,
                    store = store,
                    initialPackage = initialPackage,
                    onOpenShizuku = {
                        // 视觉线路的执行必须依赖 Shizuku 注入；ShizukuGrantActivity
                        // 覆盖安装 / 授权 / appops 三段引导，返回后由能力卡刷新确认。
                        startActivity(Intent(this@RpaActivity, ShizukuGrantActivity::class.java))
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_IM_PACKAGE = "rpa_im_package"

        fun intent(context: Context, imPackage: String? = null): Intent =
            Intent(context, RpaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply {
                    imPackage?.let { putExtra(EXTRA_IM_PACKAGE, it) }
                }
    }
}

/** 与 AdvisorPlaybookActivity 同口径的语言上下文（RPA 页面独立 Activity）。 */
private fun Context.withRpaLocale(locale: Locale): Context {
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}

private enum class RpaScreen {
    LIBRARY,
    EDITOR,
    INSPECTOR,
    VISION,
}

@Composable
private fun RpaApp(
    device: RpaAndroidDevice,
    store: RpaScriptStore,
    initialPackage: String?,
    onOpenShizuku: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(RpaScreen.LIBRARY) }
    var scripts by remember { mutableStateOf(runCatching { store.list() }.getOrDefault(emptyList())) }
    var script by remember { mutableStateOf<RpaScript?>(null) }
    var selectedStepId by remember { mutableStateOf<String?>(null) }
    var editingStepId by remember { mutableStateOf<String?>(null) }
    var capabilities by remember { mutableStateOf(device.capabilities) }

    fun refreshCapabilities() {
        capabilities = device.capabilities
    }

    fun openEditor(target: RpaScript) {
        script = target
        selectedStepId = target.steps.firstOrNull()?.id
        editingStepId = null
        screen = RpaScreen.EDITOR
    }

    fun persist(target: RpaScript?): RpaScript? {
        val value = target ?: return null
        val saved = store.save(value).getOrNull() ?: value
        script = saved
        scripts = runCatching { store.list() }.getOrDefault(scripts)
        return saved
    }

    fun leaveEditor() {
        persist(script)
        screen = RpaScreen.LIBRARY
    }

    LaunchedEffect(screen) {
        refreshCapabilities()
    }

    // 从 Shizuku 授权页 / 录屏授权弹窗返回时 Activity 会重新 resume；自动复查
    // 能力快照，避免用户误以为授权后仍不可用（也可手动点「刷新」）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshCapabilities()
    }

    BackHandler(enabled = screen != RpaScreen.LIBRARY) {
        when (screen) {
            RpaScreen.EDITOR -> leaveEditor()
            RpaScreen.INSPECTOR,
            RpaScreen.VISION,
            -> {
                screen = RpaScreen.EDITOR
            }
            RpaScreen.LIBRARY -> Unit
        }
    }

    when (screen) {
        RpaScreen.LIBRARY -> RpaLibraryScreen(
            scripts = scripts,
            capabilities = capabilities,
            initialPackage = initialPackage,
            onBack = onClose,
            onRefreshCapabilities = { refreshCapabilities() },
            onGrantProjection = {
                scope.launch {
                    runCatching { device.ensureVisionReady() }
                    refreshCapabilities()
                }
            },
            onOpenShizuku = onOpenShizuku,
            onOpen = { target ->
                openEditor(store.load(target.id) ?: target)
            },
            onCreate = { name, mode, pkg ->
                val created = RpaScriptFactory.starter(name, mode, pkg)
                store.save(created).getOrNull()?.let { saved ->
                    scripts = runCatching { store.list() }.getOrDefault(scripts)
                    openEditor(saved)
                }
            },
            onDuplicate = { target ->
                store.duplicate(target)
                scripts = runCatching { store.list() }.getOrDefault(scripts)
            },
            onDelete = { target ->
                store.delete(target.id)
                scripts = runCatching { store.list() }.getOrDefault(scripts)
                if (script?.id == target.id) script = null
            },
        )

        RpaScreen.EDITOR -> {
            val active = script
            if (active == null) {
                // 不在 composition 中直接写 state：交给 effect 回到脚本库。
                LaunchedEffect(Unit) { screen = RpaScreen.LIBRARY }
            } else {
                RpaEditorScreen(
                    script = active,
                    selectedStepId = selectedStepId,
                    capabilities = capabilities,
                    device = device,
                    store = store,
                    onBack = { leaveEditor() },
                    onSelectStep = { selectedStepId = it },
                    onScriptChange = { updated ->
                        script = updated
                        if (selectedStepId != null &&
                            updated.steps.none { it.id == selectedStepId }
                        ) {
                            selectedStepId = updated.steps.firstOrNull()?.id
                        }
                    },
                    onSave = { persist(script) },
                    onRefreshCapabilities = { refreshCapabilities() },
                    onGrantProjection = {
                        scope.launch {
                            runCatching { device.ensureVisionReady() }
                            refreshCapabilities()
                        }
                    },
                    onOpenShizuku = onOpenShizuku,
                    onOpenInspector = { stepId ->
                        editingStepId = stepId
                        screen = RpaScreen.INSPECTOR
                    },
                    onOpenVision = { stepId ->
                        editingStepId = stepId
                        screen = RpaScreen.VISION
                    },
                )
            }
        }

        RpaScreen.INSPECTOR -> {
            val active = script
            if (active == null || editingStepId == null) {
                LaunchedEffect(active, editingStepId) {
                    screen = if (active == null) RpaScreen.LIBRARY else RpaScreen.EDITOR
                }
            } else {
                RpaInspectorScreen(
                    device = device,
                    onBack = { screen = RpaScreen.EDITOR },
                    onApply = { spec: RpaA11ySpec ->
                        script = active.withA11ySpec(editingStepId, spec)
                        screen = RpaScreen.EDITOR
                    },
                )
            }
        }

        RpaScreen.VISION -> {
            val active = script
            if (active == null || editingStepId == null) {
                LaunchedEffect(active, editingStepId) {
                    screen = if (active == null) RpaScreen.LIBRARY else RpaScreen.EDITOR
                }
            } else {
                RpaVisionScreen(
                    device = device,
                    store = store,
                    scriptId = active.id,
                    initial = active.steps
                        .firstOrNull { it.id == editingStepId }
                        ?.target
                        ?.vision,
                    onBack = { screen = RpaScreen.EDITOR },
                    onApply = { spec: RpaVisionSpec ->
                        script = active.withVisionSpec(editingStepId, spec)
                        screen = RpaScreen.EDITOR
                    },
                )
            }
        }
    }
}
