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
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaCatalog
import com.verlintas.rpa.model.RpaFlowKind
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaIssue
import com.verlintas.rpa.model.RpaIssueSeverity
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaParams
import com.verlintas.rpa.model.RpaPickRequest
import com.verlintas.rpa.model.RpaReadiness
import com.verlintas.rpa.model.RpaRunEvent
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaScriptFactory
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaValidator
import com.verlintas.rpa.model.RpaVarSource
import com.verlintas.rpa.model.RpaVisionSpec
import com.verlintas.rpa.store.RpaExportFormat
import com.verlintas.rpa.store.RpaScriptStore
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RPA 自动化全屏入口（独立模块 UI）：
 *
 * 脚本库 → 脚本详情（属性）→ 浮窗动作面板（§7.5，模式门确认后拉起）→ 在目标应用
 * 拾取 / 以图找图 → 回填定位。入口有两处：好参谋设置页与画像页「聊天会话」行的
 * RPA 按钮；后者只预填 IM 包名，用于新建脚本时少填一项，不再生成任何 Demo。
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
    lateinit var chromeGate: RpaChromeGate

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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    chromeGate = chromeGate,
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
                    onStartRun = { scriptId, line -> RpaEntryPoints.startRun(this, scriptId, line) },
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

/** 就绪校验按所选运行线收口（D7）：只拦本线真缺的前置项，另一条线的缺项不拦。 */
private fun RpaIssue.hindersLine(line: RpaLine): Boolean = when (line) {
    RpaLine.A11Y -> code !in setOf(
        "ready_vision_missing",
        "ready_vision_injection_missing",
        "ready_ocr_missing",
        "ready_dual_vision_missing",
    )
    RpaLine.VISION -> code !in setOf("ready_a11y_missing", "ready_dual_a11y_missing")
}

private enum class RpaScreen {
    LIBRARY,
    PROPERTY,
    VISION,
}

/** 步骤弹框槽：被编辑的步骤 + 是否新增（确认时追加到尾部还是原位替换）。 */
private data class StepDialogSlot(val step: RpaStep, val isNew: Boolean)

/** 删除被跳转引用步骤的三选项请求（§六-6b）。 */
private data class DeleteJumpRequest(val stepId: String, val referrers: List<RpaStep>)

@Composable
private fun RpaApp(
    device: RpaAndroidDevice,
    store: RpaScriptStore,
    runController: RpaRunController,
    pickCoordinator: RpaPickCoordinator,
    chromeGate: RpaChromeGate,
    initialPackage: String?,
    initialScriptId: String?,
    initialStepId: String?,
    onOpenShizuku: () -> Unit,
    onClose: () -> Unit,
    onCloseForPick: () -> Unit,
    onStartRun: (String, RpaLine) -> Unit,
    onStopRun: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var screen by remember { mutableStateOf(RpaScreen.LIBRARY) }
    var scripts by remember { mutableStateOf<List<RpaScript>>(emptyList()) }
    var brokenScripts by remember { mutableStateOf(0) }
    var script by remember { mutableStateOf<RpaScript?>(null) }
    var capabilities by remember { mutableStateOf(device.capabilities) }
    var blockedIssues by remember { mutableStateOf<List<RpaIssue>>(emptyList()) }
    // 运行模式门请求（D4/D5）：非空即弹 RunModeSelectDialog。
    var runModeRequest by remember { mutableStateOf<RpaScript?>(null) }
    // 属性页「编辑」的模式确认门（§7.4）：确认后拉起浮窗动作面板。
    var modeGateVisible by remember { mutableStateOf(false) }
    // p2-6 浮窗会话与弹框槽：动作面板是悬浮覆盖层，业务弹框全部画进它的 overlay。
    var panelController by remember { mutableStateOf<RpaActionPanelController?>(null) }
    var stepDialog by remember { mutableStateOf<StepDialogSlot?>(null) }
    var settingsVisible by remember { mutableStateOf(false) }
    var deleteJump by remember { mutableStateOf<DeleteJumpRequest?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var logsVisible by remember { mutableStateOf(false) }
    var varsVisible by remember { mutableStateOf(false) }
    var exportVisible by remember { mutableStateOf(false) }
    // 面板打开后待落到步骤弹框的步骤 id（拾取回跳定位）。
    var pendingOpenStepId by remember { mutableStateOf<String?>(null) }
    // 以图找图页暂存的来源弹框步骤：返回时回到该步骤弹框。
    var visionSlot by remember { mutableStateOf<StepDialogSlot?>(null) }
    var panelImportDraft by remember { mutableStateOf<RpaImportDraft?>(null) }
    val runState by runController.state.collectAsStateWithLifecycle()
    val runEvents by runController.events.collectAsStateWithLifecycle()
    val runStartFailed = stringResource(R.string.float_rpa_run_start_failed)

    fun refreshCapabilities() {
        capabilities = device.capabilities
    }

    // 系统无障碍设置直达（模式门 / 运行能力卡 / 新建前置门共用）。
    val openAccessibilitySettings: () -> Unit = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Unit
    }

    /** 进入脚本详情页（§7.2）：列表单击 / 新建 / 导入 / 拾取回跳的统一落点。 */
    fun openProperty(target: RpaScript) {
        runController.dismissFinished()
        script = target
        screen = RpaScreen.PROPERTY
    }

    /**
     * 磁盘扫描与脚本加载全部离开主线程：脚本一多就是几十次 JSON 解析。
     * 局部函数先声明后使用。
     */
    suspend fun refreshScripts() {
        val report = withContext(Dispatchers.IO) { store.listReport() }
        scripts = report.scripts
        brokenScripts = report.failed.size
    }

    /**
     * 落盘是唯一的真相入口：保存失败必须显式报错并返回 null，
     * 调用方不得拿内存里的未保存版本继续校验 / 运行 / 拾取。
     */
    suspend fun persist(target: RpaScript?, notifySuccess: Boolean = false): RpaScript? {
        val value = target ?: return null
        val result = withContext(Dispatchers.IO) { store.save(value) }
        val saved = result.getOrNull()
        if (saved == null) {
            val detail = result.exceptionOrNull()?.message.orEmpty()
            Toast.makeText(
                context,
                context.getString(R.string.float_rpa_save_failed, detail),
                Toast.LENGTH_LONG,
            ).show()
            return null
        }
        script = saved
        refreshScripts()
        if (notifySuccess) {
            Toast.makeText(context, R.string.float_rpa_saved, Toast.LENGTH_SHORT).show()
        }
        return saved
    }

    /** 离开属性页回脚本库：保存失败就留在当前页，不静默丢编辑。 */
    suspend fun leaveToLibrary() {
        if (persist(script) == null) return
        screen = RpaScreen.LIBRARY
    }

    /**
     * 运行前统一门禁：先落盘（列表运行读的是 store 版本），再做校验 + 能力就绪；
     * 有 ERROR 时只弹问题列表，不启动服务，避免后台跑一半才失败。
     */
    suspend fun requestRun(target: RpaScript, line: RpaLine) {
        val saved = persist(target) ?: return
        val issues = withContext(Dispatchers.IO) {
            RpaValidator.validate(saved) +
                RpaReadiness.check(saved, device.capabilities) { sid, tid ->
                    store.templateExists(sid, tid)
                }
        }.filter { it.hindersLine(line) }
        val errors = issues.filter { it.severity == RpaIssueSeverity.ERROR }
        if (errors.isNotEmpty()) {
            blockedIssues = errors
            return
        }
        runCatching { onStartRun(saved.id, line) }
            .onFailure {
                Toast.makeText(context, runStartFailed, Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * 运行模式门（用户定案 D5）：DUAL 每次运行弹「选择运行模式」；单模式脚本唯一
     * 模式就绪→直跑，未就绪→弹修复门（确认钮置灰，先去开设置）。
     */
    fun requestRunWithModeGate(target: RpaScript) {
        val onlyLine = when (target.mode) {
            RpaMode.A11Y -> RpaLine.A11Y
            RpaMode.VISION -> RpaLine.VISION
            RpaMode.DUAL -> null
        }
        val ready = when (onlyLine) {
            RpaLine.A11Y -> capabilities.a11yModeReady()
            RpaLine.VISION -> capabilities.visionModeReady()
            null -> false
        }
        if (onlyLine != null && ready) {
            scope.launch { requestRun(target, onlyLine) }
        } else {
            runModeRequest = target
        }
    }

    /**
     * 导出并调起系统分享面板（§九）：字节先落 cacheDir/rpa_export，再经
     * FileProvider 以 content:// 交给分享目标，避免 file:// 暴露。
     */
    suspend fun exportScript(target: RpaScript, format: RpaExportFormat) {
        val exportFailed = { detail: String ->
            Toast.makeText(
                context,
                context.getString(R.string.float_rpa_export_failed, detail),
                Toast.LENGTH_LONG,
            ).show()
        }
        val bytes = withContext(Dispatchers.IO) {
            runCatching { store.exportBytes(target, format) }
        }.getOrElse { failure ->
            exportFailed(failure.message.orEmpty())
            return
        }
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "rpa_export").apply { mkdirs() }
                val safeName = target.name.ifBlank { target.id }
                    .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
                File(dir, "$safeName.${format.fileExtension}").apply { writeBytes(bytes) }
            }
        }.getOrElse { failure ->
            exportFailed(failure.message.orEmpty())
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.rpaexport", file)
        }.getOrElse { failure ->
            exportFailed(failure.message.orEmpty())
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.float_rpa_export)),
            )
        }.onFailure { failure -> exportFailed(failure.message.orEmpty()) }
    }

    /** 面板展示状态快照：脚本名 / 步骤表 / 选中步 / 运行态 / 日志开关。 */
    fun currentPanelState(): RpaActionPanelState = RpaActionPanelState(
        scriptName = script?.name.orEmpty(),
        steps = script?.steps.orEmpty(),
        selectedStepId = stepDialog?.step?.id,
        runState = runState,
        logsEnabled = logsVisible,
    )

    /** 脚本级改动落盘 + 刷面板（步骤表 / 全局设置共用入口）。 */
    fun mutateScript(transform: (RpaScript) -> RpaScript) {
        scope.launch {
            val active = script ?: return@launch
            persist(transform(active)) ?: return@launch
            panelController?.update(currentPanelState())
        }
    }

    /** 步骤表改动（增删改序）：统一落盘 + 刷面板。 */
    fun mutateSteps(transform: (List<RpaStep>) -> List<RpaStep>) {
        mutateScript { it.copy(steps = transform(it.steps)) }
    }

    /**
     * 进入拾取（方案 §7.8/§7.9）：先落盘脚本（弹框里的新步骤先把底稿插进步骤表，
     * 保证工具写回按 stepId 命中），再武装请求并退出本页，把目标 App 让到前台；
     * 随后**直拉对应工具面**（布局分析 / 以图找图，不经悬浮球菜单）——服务侧等
     * 本 App 退出前台后自动打开。用户完成拾取并「应用到当前步骤」后，带
     * scriptId / stepId 重新打开本页并定位到该步骤。
     */
    fun armPick(request: RpaPickRequest) {
        scope.launch {
            val active = script ?: return@launch
            val base = if (active.steps.any { it.id == request.stepId }) {
                active
            } else {
                val draft = stepDialog?.step ?: return@launch
                active.copy(steps = active.steps + draft)
            }
            persist(base) ?: return@launch
            withContext(Dispatchers.IO) { pickCoordinator.arm(request) }
            Toast.makeText(context, R.string.float_rpa_pick_armed, Toast.LENGTH_LONG).show()
            RpaEntryPoints.openPickTool(context)
            panelController?.close()
            panelController = null
            onCloseForPick()
        }
    }

    /** 面板导入落库：成功后关面板回属性页展示导入结果。 */
    fun importBytes(bytes: ByteArray) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.importBytes(bytes) }
            val saved = result.getOrNull()
            if (saved == null) {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.float_rpa_import_failed,
                        result.exceptionOrNull()?.message.orEmpty(),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                refreshScripts()
                panelController?.close()
                panelController = null
                openProperty(saved)
            }
        }
    }

    // 面板「导入」：系统文件选择 → 解析摘要 → 确认后落库（§九）。
    val panelImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val draft = uri?.let { readRpaImportDraft(context, it) }
        when {
            uri == null -> Unit
            draft == null -> Toast.makeText(
                context,
                context.getString(R.string.float_rpa_import_failed, ""),
                Toast.LENGTH_LONG,
            ).show()
            else -> panelImportDraft = draft
        }
    }

    /**
     * 面板弹框槽（画在 overlay 内；禁 android Dialog 的用 Ink 内窗）：
     * 全局设置 / 删除被引用跳转 / 清空动作 / 运行日志 / 脚本变量 /
     * 导出格式 / 导入确认。步骤编辑走动作编辑浮窗（[RpaActionPanelController.openEdit]）。
     */
    val panelDialogs: @Composable () -> Unit = {
        val active = script
        if (settingsVisible && active != null) {
            RpaGlobalSettingsDialog(
                global = active.global,
                onConfirm = { global ->
                    settingsVisible = false
                    mutateScript { it.copy(global = global) }
                },
                onDismiss = { settingsVisible = false },
            )
        }
        deleteJump?.let { request ->
            DeleteJumpDialog(
                referrerCount = request.referrers.size,
                onClearJumps = {
                    deleteJump = null
                    if (stepDialog?.step?.id == request.stepId) stepDialog = null
                    mutateScript { current ->
                        current.copy(
                            steps = current.steps
                                .filterNot { it.id == request.stepId }
                                .map { clearJumpTo(it, request.stepId) },
                            customActions = current.customActions.map { group ->
                                group.copy(
                                    steps = group.steps.map { clearJumpTo(it, request.stepId) },
                                )
                            },
                        )
                    }
                },
                onDeleteOnly = {
                    deleteJump = null
                    if (stepDialog?.step?.id == request.stepId) stepDialog = null
                    mutateSteps { steps -> steps.filterNot { it.id == request.stepId } }
                },
                onDismiss = { deleteJump = null },
            )
        }
        if (clearConfirm && active != null) {
            ClearStepsDialog(
                stepCount = active.steps.size,
                onConfirm = {
                    clearConfirm = false
                    stepDialog = null
                    mutateSteps { emptyList() }
                },
                onDismiss = { clearConfirm = false },
            )
        }
        if (logsVisible) {
            LogsDialog(
                runState = runState,
                events = runEvents,
                onClose = { logsVisible = false },
            )
        }
        if (varsVisible && active != null) {
            VariablesDialog(script = active, onClose = { varsVisible = false })
        }
        if (exportVisible && active != null) {
            ExportFormatDialog(
                onDismiss = { exportVisible = false },
                onConfirm = { format ->
                    exportVisible = false
                    scope.launch { exportScript(active, format) }
                },
            )
        }
        panelImportDraft?.let { draft ->
            ImportConfirmDialog(
                draft = draft,
                onDismiss = { panelImportDraft = null },
                onConfirm = {
                    panelImportDraft = null
                    importBytes(draft.bytes)
                },
            )
        }
    }

    // 浮窗动作面板的业务回调（p2-6）：浮窗只发事件，业务全在这里。
    val panelHost = remember {
        object : RpaActionPanelHost {
            override fun onOpenSettings() {
                settingsVisible = true
            }

            override fun onClose() {
                // ✕ 关闭退出：controller 已收窗并恢复悬浮球，这里收弹框态落回属性页。
                panelController = null
                stepDialog = null
                settingsVisible = false
                deleteJump = null
                clearConfirm = false
                exportVisible = false
                panelImportDraft = null
                screen = RpaScreen.PROPERTY
            }

            override fun onEditStep(stepId: String) {
                val step = script?.steps?.firstOrNull { it.id == stepId } ?: return
                stepDialog = StepDialogSlot(step, isNew = false)
            }

            override fun onMoveStep(from: Int, to: Int) {
                mutateSteps { steps ->
                    if (from !in steps.indices || to !in steps.indices || from == to) {
                        steps
                    } else {
                        steps.toMutableList().apply { add(to, removeAt(from)) }
                    }
                }
            }

            override fun onDuplicateStep(stepId: String) {
                mutateSteps { steps ->
                    val index = steps.indexOfFirst { it.id == stepId }
                    if (index < 0) {
                        steps
                    } else {
                        steps.toMutableList().apply {
                            add(index + 1, steps[index].copy(id = RpaIds.newId("step")))
                        }
                    }
                }
            }

            override fun onToggleStep(stepId: String) {
                mutateSteps { steps ->
                    steps.map { step ->
                        if (step.id == stepId) step.copy(enabled = !step.enabled) else step
                    }
                }
            }

            override fun onJumpTarget(stepId: String) {
                // 「跳转到此步」：在末尾追加一个 GOTO 指向该步的动作（§7.3）。
                mutateSteps { steps ->
                    steps + RpaCatalog.defaultStep(RpaAction.CONTROL_FLOW).copy(
                        params = RpaParams(flowKind = RpaFlowKind.GOTO, gotoStepId = stepId),
                    )
                }
                Toast.makeText(context, R.string.float_rpa_jump_here_added, Toast.LENGTH_SHORT)
                    .show()
            }

            override fun onDeleteStep(stepId: String) {
                val active = script ?: return
                val referrers = (active.steps + active.customActions.flatMap { it.steps })
                    .filter {
                        it.params.flowKind == RpaFlowKind.GOTO && it.params.gotoStepId == stepId
                    }
                if (referrers.isEmpty()) {
                    if (stepDialog?.step?.id == stepId) stepDialog = null
                    mutateSteps { steps -> steps.filterNot { it.id == stepId } }
                } else {
                    deleteJump = DeleteJumpRequest(stepId, referrers)
                }
            }

            override fun onAddStep() {
                stepDialog = StepDialogSlot(RpaCatalog.defaultStep(RpaAction.TAP), isNew = true)
            }

            override fun onRun() {
                script?.let { active -> requestRunWithModeGate(active) }
            }

            override fun onStop() = onStopRun()

            override fun onSave() {
                scope.launch { persist(script, notifySuccess = true) }
            }

            override fun onClearSteps() {
                clearConfirm = true
            }

            override fun onToggleLogs() {
                logsVisible = !logsVisible
                panelController?.update(currentPanelState())
            }

            override fun onOpenVariables() {
                varsVisible = true
            }

            override fun onExport() {
                exportVisible = true
            }

            override fun onImport() {
                panelImportLauncher.launch(IMPORT_MIME)
            }
        }
    }

    /**
     * 拉起浮窗动作面板（模式门确认 / 拾取回跳的统一落点）：每次会话新建
     * controller（close 后不可复用），弹框槽挂 [panelDialogs]。
     */
    fun openPanel(target: RpaScript, openStepId: String? = null) {
        script = target
        pendingOpenStepId = openStepId
        panelController?.dispose()
        val controller = RpaActionPanelController(context, chromeGate, panelHost)
        panelController = controller
        controller.update(currentPanelState())
        controller.open(extraDialogs = panelDialogs)
    }

    LaunchedEffect(initialScriptId) {
        val report = withContext(Dispatchers.IO) { store.listReport() }
        scripts = report.scripts
        brokenScripts = report.failed.size
        if (initialScriptId != null) {
            val target = withContext(Dispatchers.IO) { store.load(initialScriptId) }
            if (target == null) {
                Toast.makeText(context, R.string.float_rpa_script_missing, Toast.LENGTH_LONG)
                    .show()
            } else {
                openProperty(target)
                if (initialStepId != null) {
                    openPanel(target, initialStepId)
                    Toast.makeText(context, R.string.float_rpa_pick_applied, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    /** 从以图找图返回：回到来源步骤弹框；[apply] 非空时回填视觉定位。 */
    fun returnFromVision(apply: RpaVisionSpec? = null) {
        val slot = visionSlot
        visionSlot = null
        val active = script
        if (slot != null) {
            val updated = when {
                apply != null && active != null && active.steps.any { it.id == slot.step.id } -> {
                    val withSpec = active.withVisionSpec(slot.step.id, apply)
                    script = withSpec
                    withSpec.steps.first { it.id == slot.step.id }
                }

                apply != null -> slot.step.copy(
                    target = (slot.step.target ?: RpaTarget()).copy(vision = apply),
                )

                else -> slot.step
            }
            stepDialog = StepDialogSlot(
                step = updated,
                isNew = script?.steps?.none { it.id == updated.id } ?: true,
            )
        }
        screen = RpaScreen.PROPERTY
        panelController?.onExpand()
    }

    // 待开步骤：面板组合完成后落到步骤弹框（拾取回跳定位）。
    LaunchedEffect(pendingOpenStepId) {
        val id = pendingOpenStepId ?: return@LaunchedEffect
        val step = script?.steps?.firstOrNull { it.id == id }
        if (step != null) {
            pendingOpenStepId = null
            stepDialog = StepDialogSlot(step, isNew = false)
        }
    }

    // 步骤编辑 = 动作编辑浮窗（第二 overlay 窗口，§7.5/§7.6）：stepDialog 驱动开合。
    LaunchedEffect(stepDialog, panelController) {
        val controller = panelController
        val slot = stepDialog
        if (slot == null || controller == null) {
            controller?.closeEdit()
            return@LaunchedEffect
        }
        controller.openEdit(
            title = context.getString(
                if (slot.isNew) R.string.float_rpa_panel_add_step else R.string.float_rpa_stepdlg_title,
            ),
            onDismiss = { stepDialog = null },
        ) {
            // 内容读快照态组合：脚本 / 步骤槽变化即刷新（与原弹框槽口径一致）。
            val current = stepDialog
            val active = script
            if (current != null && active != null) {
                RpaStepDialog(
                    step = current.step,
                    script = active,
                    onArmPick = { request -> armPick(request) },
                    onOpenVisionPanel = {
                        // 图11 找图面板：暂存来源弹框步骤，收起面板进以图找图页。
                        visionSlot = current
                        stepDialog = null
                        panelController?.onCollapse()
                        screen = RpaScreen.VISION
                        runCatching {
                            context.startActivity(
                                Intent(context, RpaActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                            )
                        }
                    },
                    onConfirm = { updated ->
                        stepDialog = null
                        if (current.isNew) {
                            mutateSteps { steps -> steps + updated }
                        } else {
                            mutateSteps { steps ->
                                steps.map { step -> if (step.id == updated.id) updated else step }
                            }
                        }
                    },
                    onDismiss = { stepDialog = null },
                )
            }
        }
    }

    // 面板展示态与外部状态同步（脚本 / 运行态 / 日志开关 / 选中步）。
    LaunchedEffect(runState, script, logsVisible, stepDialog) {
        panelController?.update(currentPanelState())
    }

    // 弹框（含文本输入）打开期间面板窗口切可聚焦，IME 才能弹出。
    val anyDialogOpen = stepDialog != null || settingsVisible || deleteJump != null ||
        clearConfirm || logsVisible || varsVisible || exportVisible
    LaunchedEffect(anyDialogOpen, panelController) {
        panelController?.setDialogFocusable(anyDialogOpen)
    }

    // Activity 销毁即收面板；换会话时旧 controller 一并 dispose。
    val panelSession = panelController
    DisposableEffect(panelSession) {
        onDispose { panelSession?.dispose() }
    }

    // 从 Shizuku 授权页 / 录屏授权弹窗返回时 Activity 会重新 resume；自动复查
    // 能力快照，避免用户误以为授权后仍不可用（也可手动点「刷新」）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshCapabilities()
    }

    // 双模式状态实时刷新（用户定案「一开始就要实时判断」）：a11y 连接态 /
    // Shizuku shell 状态一变，运行能力卡与新建前置门立刻复检，不等下次 resume。
    LaunchedEffect(Unit) {
        device.readinessChanges.collect { refreshCapabilities() }
    }

    BackHandler(enabled = screen != RpaScreen.LIBRARY) {
        // §九面板收纳：返回键先把浮窗面板收成圆球，再走各页既有返回口径。
        panelController?.onCollapse()
        when (screen) {
            RpaScreen.PROPERTY -> scope.launch { leaveToLibrary() }
            RpaScreen.VISION -> returnFromVision()
            RpaScreen.LIBRARY -> Unit
        }
    }

    when (screen) {
        RpaScreen.LIBRARY -> RpaLibraryScreen(
            scripts = scripts,
            brokenScripts = brokenScripts,
            capabilities = capabilities,
            initialPackage = initialPackage,
            runState = runState,
            onBack = onClose,
            onRefreshCapabilities = { refreshCapabilities() },
            onOpenShizuku = onOpenShizuku,
            onOpenAccessibilitySettings = openAccessibilitySettings,
            onOpen = { target -> openProperty(target) },
            onCreate = { name, mode, pkg ->
                scope.launch {
                    val created = RpaScriptFactory.starter(name, mode, pkg)
                    val saved = withContext(Dispatchers.IO) { store.save(created) }.getOrNull()
                    if (saved == null) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.float_rpa_save_failed, "create"),
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        refreshScripts()
                        openProperty(saved)
                    }
                }
            },
            onRun = { target -> requestRunWithModeGate(target) },
            onStopRun = onStopRun,
            onEdit = { target -> openProperty(target) },
            onRename = { target, name ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        store.save(target.copy(name = name))
                    }
                    if (result.isSuccess) {
                        refreshScripts()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.float_rpa_rename_failed,
                                result.exceptionOrNull()?.message.orEmpty(),
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onExport = { target, format -> scope.launch { exportScript(target, format) } },
            onImport = { bytes ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) { store.importBytes(bytes) }
                    val saved = result.getOrNull()
                    if (saved == null) {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.float_rpa_import_failed,
                                result.exceptionOrNull()?.message.orEmpty(),
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        refreshScripts()
                        openProperty(saved)
                    }
                }
            },
            onDuplicate = { target ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) { store.duplicate(target) }
                    if (result.isSuccess) {
                        refreshScripts()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.float_rpa_duplicate_failed,
                                result.exceptionOrNull()?.message.orEmpty(),
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onDelete = { target ->
                scope.launch {
                    val removed = withContext(Dispatchers.IO) { store.delete(target.id) }
                    if (removed) {
                        if (script?.id == target.id) script = null
                        refreshScripts()
                    } else {
                        Toast.makeText(context, R.string.float_rpa_delete_failed, Toast.LENGTH_LONG)
                            .show()
                    }
                }
            },
        )

        RpaScreen.PROPERTY -> {
            val active = script
            if (active == null) {
                // 不在 composition 中直接写 state：交给 effect 回到脚本库。
                LaunchedEffect(Unit) { screen = RpaScreen.LIBRARY }
            } else {
                RpaPropertyScreen(
                    script = active,
                    capabilities = capabilities,
                    runState = runState,
                    onBack = { scope.launch { leaveToLibrary() } },
                    onEdit = { modeGateVisible = true },
                    onRun = { requestRunWithModeGate(active) },
                    onStopRun = onStopRun,
                    onShare = { format -> scope.launch { exportScript(active, format) } },
                    onRename = { name ->
                        scope.launch { persist(active.copy(name = name), notifySuccess = true) }
                    },
                    onDuplicate = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { store.duplicate(active) }
                            if (result.isSuccess) {
                                refreshScripts()
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.float_rpa_duplicate_failed,
                                        result.exceptionOrNull()?.message.orEmpty(),
                                    ),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) { store.delete(active.id) }
                            if (removed) {
                                script = null
                                refreshScripts()
                                screen = RpaScreen.LIBRARY
                            } else {
                                Toast.makeText(
                                    context,
                                    R.string.float_rpa_delete_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    onDescriptionChange = { description ->
                        scope.launch { persist(active.copy(description = description)) }
                    },
                    onRefreshCapabilities = { refreshCapabilities() },
                    onOpenShizuku = onOpenShizuku,
                )
                if (modeGateVisible) {
                    RpaModeGateDialog(
                        script = active,
                        capabilities = capabilities,
                        onDismiss = { modeGateVisible = false },
                        onConfirm = { mode ->
                            modeGateVisible = false
                            scope.launch {
                                val saved = persist(active.copy(mode = mode)) ?: return@launch
                                openPanel(saved)
                            }
                        },
                        onRefreshCapabilities = { refreshCapabilities() },
                        onOpenAccessibilitySettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                )
                            }
                        },
                        onRetryA11yConnection = {
                            scope.launch {
                                RpaEntryPoints.retryA11yBinding(context)
                                // 服务侧重绑 + 绑定轮询（约 2s）完成后复检徽标。
                                delay(2_500)
                                refreshCapabilities()
                            }
                        },
                        onOpenShizuku = onOpenShizuku,
                    )
                }
            }
        }

        RpaScreen.VISION -> {
            val active = script
            val slot = visionSlot
            if (active == null || slot == null) {
                // 不在 composition 中直接写 state：交给 effect 回属性页 / 脚本库。
                LaunchedEffect(active, slot) {
                    screen = if (active == null) RpaScreen.LIBRARY else RpaScreen.PROPERTY
                }
            } else {
                RpaVisionScreen(
                    device = device,
                    store = store,
                    scriptId = active.id,
                    initial = slot.step.target?.vision,
                    onBack = { returnFromVision() },
                    onApply = { spec -> returnFromVision(spec) },
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

    runModeRequest?.let { target ->
        RunModeSelectDialog(
            scriptMode = target.mode,
            capabilities = capabilities,
            onDismiss = { runModeRequest = null },
            onConfirm = { line ->
                runModeRequest = null
                scope.launch { requestRun(target, line) }
            },
            onOpenAccessibilitySettings = openAccessibilitySettings,
            onOpenShizuku = onOpenShizuku,
        )
    }
}

// ── 面板弹框（overlay 内窗）与公共小件 ─────────────────────────────────

/** ①「一并清除跳转」：引用步的 GOTO 目标清空，由 param_goto_missing 校验提示补齐。 */
private fun clearJumpTo(step: RpaStep, targetId: String): RpaStep =
    if (step.params.flowKind == RpaFlowKind.GOTO && step.params.gotoStepId == targetId) {
        step.copy(params = step.params.copy(gotoStepId = null))
    } else {
        step
    }

/** 墨板整行按钮：主键（primary 底）/ 次键（SurfaceHigh 底）。 */
@Composable
private fun InkActionButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) MaterialTheme.colorScheme.primary else InkDialog.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = InkTextStyles.Body,
            color = if (primary) MaterialTheme.colorScheme.onPrimary else InkDialog.OnSurface,
        )
    }
}

/**
 * 删除被跳转引用步骤的三选项（§六-6b，Ink 内窗）：
 * ① 一并清除跳转（引用步 gotoStepId 置空，校验器按 param_goto_missing 提示补齐）；
 * ② 仅删除（跳转悬空，由 jump_target_missing 校验与面板行标红提示）；③ 取消删除。
 */
@Composable
private fun DeleteJumpDialog(
    referrerCount: Int,
    onClearJumps: () -> Unit,
    onDeleteOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    InkDialogShell(
        title = stringResource(R.string.float_rpa_step_delete_jump_title),
        onDismiss = onDismiss,
    ) {
        Text(
            text = stringResource(R.string.float_rpa_step_delete_jump_message, referrerCount),
            style = InkTextStyles.Summary,
            color = InkDialog.OnSurfaceMuted,
        )
        Spacer(Modifier.height(12.dp))
        InkActionButton(
            text = stringResource(R.string.float_rpa_step_delete_clear_jumps),
            primary = true,
            onClick = onClearJumps,
        )
        Spacer(Modifier.height(8.dp))
        InkActionButton(
            text = stringResource(R.string.float_rpa_step_delete_keep_jumps),
            onClick = onDeleteOnly,
        )
        Spacer(Modifier.height(8.dp))
        InkActionButton(
            text = stringResource(R.string.float_rpa_step_delete_cancel),
            onClick = onDismiss,
        )
    }
}

/** 清空所有动作的轻确认（Ink 内窗）：明示将移除的步数并立即落盘。 */
@Composable
private fun ClearStepsDialog(stepCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    InkDialogShell(
        title = stringResource(R.string.float_rpa_panel_clear_title),
        onDismiss = onDismiss,
    ) {
        Text(
            text = stringResource(R.string.float_rpa_panel_clear_message, stepCount),
            style = InkTextStyles.Summary,
            color = InkDialog.OnSurfaceMuted,
        )
        Spacer(Modifier.height(12.dp))
        InkActionButton(
            text = stringResource(R.string.float_rpa_panel_clear_confirm),
            primary = true,
            onClick = onConfirm,
        )
        Spacer(Modifier.height(8.dp))
        InkActionButton(text = stringResource(R.string.float_cancel), onClick = onDismiss)
    }
}

/** 运行日志窗（Ink 内窗）：运行态摘要 + 运行事件中文行 + 关闭。 */
@Composable
private fun LogsDialog(
    runState: RpaRunController.State,
    events: List<RpaRunEvent>,
    onClose: () -> Unit,
) {
    InkDialogShell(
        title = stringResource(R.string.float_rpa_panel_logs_title),
        onDismiss = onClose,
    ) {
        Text(
            text = runStateSummary(runState),
            style = InkTextStyles.Summary,
            color = InkDialog.OnSurfaceMuted,
        )
        InkDivider()
        if (events.isEmpty()) {
            Text(
                text = stringResource(R.string.float_rpa_panel_logs_empty),
                style = InkTextStyles.Summary,
                color = InkDialog.OnSurfaceDisabled,
            )
        } else {
            events.forEach { event ->
                Text(
                    text = rpaLogLine(event),
                    style = InkTextStyles.Summary,
                    color = InkDialog.OnSurface,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        InkDivider()
        InkActionButton(text = stringResource(R.string.float_rpa_panel_close), onClick = onClose)
    }
}

@Composable
private fun runStateSummary(runState: RpaRunController.State): String = when (runState) {
    RpaRunController.State.Idle -> stringResource(R.string.float_rpa_log_idle)

    is RpaRunController.State.Running -> stringResource(
        R.string.float_rpa_log_running,
        runState.scriptName,
        runState.displayIndex,
        runState.stepCount,
    )

    is RpaRunController.State.Finished -> if (runState.ok) {
        stringResource(R.string.float_rpa_log_script_ok)
    } else {
        stringResource(R.string.float_rpa_log_script_fail, runState.detail)
    }
}

/** 运行事件 → 中文日志行（§六：失败 / 线路回退必须显式报因）。 */
@Composable
private fun rpaLogLine(event: RpaRunEvent): String = when (event) {
    is RpaRunEvent.ScriptStarted -> stringResource(R.string.float_rpa_log_script_started)

    is RpaRunEvent.StepStarted -> stringResource(
        R.string.float_rpa_log_step_started,
        event.index + 1,
        rpaStepName(event.step),
    )

    is RpaRunEvent.StepFinished -> if (event.ok) {
        stringResource(R.string.float_rpa_log_step_done, event.index + 1)
    } else {
        stringResource(R.string.float_rpa_log_step_error, event.index + 1, event.detail)
    }

    is RpaRunEvent.LineFallback -> stringResource(
        R.string.float_rpa_log_line_fallback,
        event.index + 1,
        event.reason,
    )

    is RpaRunEvent.Log -> event.message

    is RpaRunEvent.ScriptFinished -> if (event.ok) {
        stringResource(R.string.float_rpa_log_script_ok)
    } else {
        stringResource(R.string.float_rpa_log_script_fail, event.detail)
    }
}

@Composable
private fun rpaStepName(step: RpaStep): String =
    step.label.ifBlank { stringResource(rpaActionLabelRes(step.action)) }

/** 脚本变量窗（Ink 内窗）：变量名 | 来源 | 值（步骤赋值行尾标注来源步）。 */
@Composable
private fun VariablesDialog(script: RpaScript, onClose: () -> Unit) {
    val empty = script.variables.isEmpty() && script.steps.all { it.params.assignments.isEmpty() }
    InkDialogShell(
        title = stringResource(R.string.float_rpa_panel_vars_title),
        onDismiss = onClose,
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.float_rpa_panel_var_name),
                style = InkTextStyles.Badge,
                color = InkDialog.OnSurfaceMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.float_rpa_panel_var_source),
                style = InkTextStyles.Badge,
                color = InkDialog.OnSurfaceMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.float_rpa_panel_var_value),
                style = InkTextStyles.Badge,
                color = InkDialog.OnSurfaceMuted,
                modifier = Modifier.weight(1f),
            )
        }
        InkDivider()
        if (empty) {
            Text(
                text = stringResource(R.string.float_rpa_panel_vars_empty),
                style = InkTextStyles.Summary,
                color = InkDialog.OnSurfaceDisabled,
            )
        } else {
            script.variables.forEach { (name, value) ->
                VariableRow(
                    name = name,
                    source = stringResource(R.string.float_rpa_panel_var_source_script),
                    value = value,
                    fromStep = null,
                )
            }
            script.steps.forEachIndexed { index, step ->
                step.params.assignments.forEach { assign ->
                    if (assign.variableName.isNotBlank()) {
                        VariableRow(
                            name = assign.variableName,
                            source = rpaVarSourceLabel(assign.source),
                            value = assign.value,
                            fromStep = index + 1,
                        )
                    }
                }
            }
        }
        InkDivider()
        InkActionButton(text = stringResource(R.string.float_rpa_panel_close), onClick = onClose)
    }
}

@Composable
private fun VariableRow(name: String, source: String, value: String, fromStep: Int?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = name,
                style = InkTextStyles.Summary,
                color = InkDialog.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = source,
                style = InkTextStyles.Summary,
                color = InkDialog.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = InkTextStyles.Summary,
                color = InkDialog.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (fromStep != null) {
            Text(
                text = stringResource(R.string.float_rpa_panel_var_from_step, fromStep),
                style = InkTextStyles.Badge,
                color = InkDialog.OnSurfaceDisabled,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** 取值来源中文（与步骤弹框同口径）。 */
@Composable
private fun rpaVarSourceLabel(source: RpaVarSource): String = stringResource(
    when (source) {
        RpaVarSource.LITERAL -> R.string.float_rpa_stepdlg_source_literal
        RpaVarSource.UI_READ -> R.string.float_rpa_stepdlg_source_ui_read
        RpaVarSource.VARIABLE_REF -> R.string.float_rpa_stepdlg_source_var_ref
        RpaVarSource.INCREMENT -> R.string.float_rpa_stepdlg_source_increment
    },
)
