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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.floatball.shizuku.ShizukuGrantActivity
import com.verlintas.rpa.model.RpaIssue
import com.verlintas.rpa.model.RpaIssueSeverity
import com.verlintas.rpa.model.RpaReadiness
import com.verlintas.rpa.model.RpaScriptFactory
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaPickKind
import com.verlintas.rpa.model.RpaPickRequest
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaValidator
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
    lateinit var runController: RpaRunController

    @Inject
    lateinit var pickCoordinator: RpaPickCoordinator

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
        val initialScriptId = intent.getStringExtra(EXTRA_SCRIPT_ID)
        val initialStepId = intent.getStringExtra(EXTRA_STEP_ID)
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
                    runController = runController,
                    pickCoordinator = pickCoordinator,
                    initialPackage = initialPackage,
                    initialScriptId = initialScriptId,
                    initialStepId = initialStepId,
                    onOpenShizuku = {
                        // 视觉线路的执行必须依赖 Shizuku 注入；ShizukuGrantActivity
                        // 覆盖安装 / 授权 / appops 三段引导，返回后由能力卡刷新确认。
                        startActivity(Intent(this@RpaActivity, ShizukuGrantActivity::class.java))
                    },
                    onClose = { finish() },
                    onCloseForPick = { finish() },
                    onStartRun = { scriptId -> RpaEntryPoints.startRun(this, scriptId) },
                    onStopRun = { RpaEntryPoints.stopRun(this) },
                )
            }
        }
    }

    companion object {
        const val EXTRA_IM_PACKAGE = "rpa_im_package"
        const val EXTRA_SCRIPT_ID = "rpa_script_id"
        const val EXTRA_STEP_ID = "rpa_step_id"

        fun intent(
            context: Context,
            imPackage: String? = null,
            scriptId: String? = null,
            stepId: String? = null,
        ): Intent =
            Intent(context, RpaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply {
                    imPackage?.let { putExtra(EXTRA_IM_PACKAGE, it) }
                    scriptId?.let {
                        putExtra(EXTRA_SCRIPT_ID, it)
                        stepId?.let { step -> putExtra(EXTRA_STEP_ID, step) }
                    }
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
    VISION,
}

@Composable
private fun RpaApp(
    device: RpaAndroidDevice,
    store: RpaScriptStore,
    runController: RpaRunController,
    pickCoordinator: RpaPickCoordinator,
    initialPackage: String?,
    initialScriptId: String?,
    initialStepId: String?,
    onOpenShizuku: () -> Unit,
    onClose: () -> Unit,
    onCloseForPick: () -> Unit,
    onStartRun: (String) -> Unit,
    onStopRun: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val initialScript = remember {
        initialScriptId?.let { runCatching { store.load(it) }.getOrNull() }
    }
    var screen by remember {
        mutableStateOf(if (initialScript != null) RpaScreen.EDITOR else RpaScreen.LIBRARY)
    }
    var scripts by remember { mutableStateOf(runCatching { store.list() }.getOrDefault(emptyList())) }
    var script by remember { mutableStateOf(initialScript) }
    var selectedStepId by remember {
        mutableStateOf(
            initialStepId?.takeIf { id -> initialScript?.steps?.any { it.id == id } == true }
                ?: initialScript?.steps?.firstOrNull()?.id,
        )
    }
    var editingStepId by remember { mutableStateOf<String?>(null) }
    var capabilities by remember { mutableStateOf(device.capabilities) }
    var blockedIssues by remember { mutableStateOf<List<RpaIssue>>(emptyList()) }
    // 拾取回跳只让属性面板打开一次；之后在库/编辑器之间往返按默认流程页。
    var pendingOpenProps by remember { mutableStateOf(initialStepId != null) }
    val runState by runController.state.collectAsStateWithLifecycle()
    val runEvents by runController.events.collectAsStateWithLifecycle()
    val runStartFailed = stringResource(R.string.float_rpa_run_start_failed)

    LaunchedEffect(initialScriptId, initialStepId) {
        if (initialScriptId != null && initialStepId != null) {
            Toast.makeText(context, R.string.float_rpa_pick_applied, Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshCapabilities() {
        capabilities = device.capabilities
    }

    fun openEditor(target: RpaScript) {
        script = target
        selectedStepId = target.steps.firstOrNull()?.id
        editingStepId = null
        pendingOpenProps = false
        screen = RpaScreen.EDITOR
    }

    /**
     * 从布局分析器 / 以图找图返回：选中被编辑的步骤并打开其属性页。
     * 分析器与以图找图的入口都在步骤属性里，返回时不能落到脚本级设置或流程页，
     * 否则用户会误以为刚生成的选择器/模板没有落到该动作上。
     */
    fun returnToStepProps() {
        selectedStepId = editingStepId ?: selectedStepId
        pendingOpenProps = true
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

    /**
     * 运行前统一门禁：先落盘（列表运行读的是 store 版本），再做校验 + 能力就绪；
     * 有 ERROR 时只弹问题列表，不启动服务，避免后台跑一半才失败。
     */
    fun requestRun(target: RpaScript) {
        val saved = persist(target) ?: target
        val issues = RpaValidator.validate(saved) +
            RpaReadiness.check(saved, device.capabilities) { sid, tid ->
                store.templateExists(sid, tid)
            }
        val errors = issues.filter { it.severity == RpaIssueSeverity.ERROR }
        if (errors.isNotEmpty()) {
            blockedIssues = errors
            return
        }
        runCatching { onStartRun(saved.id) }
            .onFailure {
                Toast.makeText(context, runStartFailed, Toast.LENGTH_SHORT).show()
            }
    }

    LaunchedEffect(screen) {
        refreshCapabilities()
    }

    /**
     * 进入拾取（方案 §7.8）：先落盘脚本，再武装请求并退出本页，把目标 App 让到
     * 前台；用户在悬浮球里打开布局分析 / 以图找图并点「应用到当前步骤」后，
     * 工具会带 scriptId / stepId 重新打开本页并定位到该步骤。
     */
    fun startPick(kind: RpaPickKind) {
        val active = script ?: return
        val saved = persist(active) ?: return
        val stepId = selectedStepId ?: saved.steps.firstOrNull()?.id ?: return
        val step = saved.steps.firstOrNull { it.id == stepId } ?: return
        pickCoordinator.arm(
            RpaPickRequest(
                requestId = RpaIds.newId("pick"),
                scriptId = saved.id,
                stepId = step.id,
                kind = kind,
                scriptName = saved.name,
                stepLabel = step.label.ifBlank {
                    rpaActionLabelRes(step.action).let { context.getString(it) }
                },
                createdAt = System.currentTimeMillis(),
            ),
        )
        Toast.makeText(context, R.string.float_rpa_pick_armed, Toast.LENGTH_LONG).show()
        onCloseForPick()
    }

    // 从 Shizuku 授权页 / 录屏授权弹窗返回时 Activity 会重新 resume；自动复查
    // 能力快照，避免用户误以为授权后仍不可用（也可手动点「刷新」）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshCapabilities()
    }

    BackHandler(enabled = screen != RpaScreen.LIBRARY) {
        when (screen) {
            RpaScreen.EDITOR -> leaveEditor()
            RpaScreen.VISION -> returnToStepProps()
            RpaScreen.LIBRARY -> Unit
        }
    }

    when (screen) {
        RpaScreen.LIBRARY -> RpaLibraryScreen(
            scripts = scripts,
            capabilities = capabilities,
            initialPackage = initialPackage,
            runState = runState,
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
            onRun = { target -> requestRun(target) },
            onStopRun = onStopRun,
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
                    store = store,
                    runState = runState,
                    runEvents = runEvents,
                    openPropsInitially = pendingOpenProps,
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
                    onOpenVision = { stepId ->
                        editingStepId = stepId
                        screen = RpaScreen.VISION
                    },
                    onStartPick = { kind -> startPick(kind) },
                    onRun = { requestRun(active) },
                    onStopRun = onStopRun,
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
                    onBack = { returnToStepProps() },
                    onApply = { spec: RpaVisionSpec ->
                        script = active.withVisionSpec(editingStepId, spec)
                        returnToStepProps()
                    },
                )
            }
        }
    }

    if (blockedIssues.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { blockedIssues = emptyList() },
            title = { Text(stringResource(R.string.float_rpa_run_blocked_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.float_rpa_run_blocked_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RpaIssueList(blockedIssues)
                }
            },
            confirmButton = {
                TextButton(onClick = { blockedIssues = emptyList() }) {
                    Text(stringResource(R.string.float_rpa_run_blocked_dismiss))
                }
            },
        )
    }
}
