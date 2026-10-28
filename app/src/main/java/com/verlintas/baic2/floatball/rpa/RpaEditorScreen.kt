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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.AdvisorPage
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaCatalog
import com.verlintas.rpa.model.RpaDirection
import com.verlintas.rpa.model.RpaExpect
import com.verlintas.rpa.model.RpaFailurePolicy
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaIssue
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaModePolicy
import com.verlintas.rpa.model.RpaPickKind
import com.verlintas.rpa.model.RpaReadiness
import com.verlintas.rpa.model.RpaRunEvent
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaPick
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaSelector
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaValidator
import com.verlintas.rpa.model.RpaVisionSpec
import com.verlintas.rpa.store.RpaScriptStore
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// ---------------------------- 脚本编辑辅助 ----------------------------

/** 布局分析器回填 a11y 定位：只改目标，不触碰视觉定位与其它属性。 */
internal fun RpaScript.withA11ySpec(stepId: String?, spec: RpaA11ySpec): RpaScript {
    if (stepId == null) return this
    return copy(
        steps = steps.map { step ->
            if (step.id != stepId) {
                step
            } else {
                val target = step.target ?: RpaTarget()
                step.copy(target = target.copy(a11y = spec.takeIf { it.isConfigured() }))
            }
        },
    )
}

/** 以图找图配置页回填视觉定位：兜底定位未配置时自动省略。 */
internal fun RpaScript.withVisionSpec(stepId: String?, spec: RpaVisionSpec): RpaScript {
    if (stepId == null) return this
    val normalized = spec.copy(
        fallback = spec.fallback?.takeIf { it.isConfigured() },
    )
    return copy(
        steps = steps.map { step ->
            if (step.id != stepId) {
                step
            } else {
                val target = step.target ?: RpaTarget()
                step.copy(
                    target = target.copy(
                        vision = normalized.takeIf { it.primary.isConfigured() },
                    ),
                )
            }
        },
    )
}

private enum class EditorTab { FLOW, PROPS }

private enum class DragSource { LIBRARY, STEP }

private data class DragPayload(
    val source: DragSource,
    val action: RpaAction? = null,
    val stepId: String? = null,
    val label: String,
    val position: Offset,
    val targetIndex: Int?,
)

/** 撤销栈上限：只保留结构性改动的最近 20 步，避免长编辑占内存。 */
private const val UNDO_LIMIT = 20

/** 拖到列表边缘时每帧的滚动像素。 */
private const val EDGE_SCROLL_PX = 18f

/**
 * RPA 拖拽式编辑器：
 *
 * - 顶部先固定「运行模式」（开启 a11y / 无 a11y / 双模式），模式决定属性面板
 *   中哪条定位线路可用、哪条必须补齐；
 * - 「动作流程」页：动作库点击追加 / 长按拖入插入点，步骤行拖动手柄排序，
 *   按数组顺序执行；
 * - 「动作属性」页：动作参数 + 双线路定位 + 变量 + 校验/就绪 + 运行日志。
 */
@Composable
internal fun RpaEditorScreen(
    script: RpaScript,
    selectedStepId: String?,
    capabilities: RpaCapabilities,
    store: RpaScriptStore,
    runState: RpaRunController.State,
    runEvents: List<RpaRunEvent>,
    openPropsInitially: Boolean = false,
    onBack: () -> Unit,
    onSelectStep: (String?) -> Unit,
    onScriptChange: (RpaScript) -> Unit,
    onSave: () -> Unit,
    onRefreshCapabilities: () -> Unit,
    onGrantProjection: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenVision: (String) -> Unit,
    onStartPick: (RpaPickKind) -> Unit,
    onRun: () -> Unit,
    onStopRun: () -> Unit,
) {
    var tab by remember {
        mutableStateOf(if (openPropsInitially) EditorTab.PROPS else EditorTab.FLOW)
    }
    val runningState = runState as? RpaRunController.State.Running
    val finishedState = runState as? RpaRunController.State.Finished
    val running = runningState?.scriptId == script.id
    val otherRunning = runningState != null && runningState.scriptId != script.id
    // 只展示当前脚本的事件；换脚本运行时 start() 已清空旧事件。
    val events = if (running || finishedState?.scriptId == script.id) {
        runEvents
    } else {
        emptyList()
    }

    val selectedStep = script.steps.firstOrNull { it.id == selectedStepId }
    // 拾取 / 分析器 / 以图找图回跳：属性页首帧把该步骤滚到视口顶部，
    // 否则视口停在脚本设置卡，用户看不到刚写入该动作的定位结果。
    var focusStepOnce by remember {
        mutableStateOf(openPropsInitially && selectedStep != null)
    }
    val issues: List<RpaIssue> = remember(script, capabilities) {
        RpaValidator.validate(script) +
            RpaReadiness.check(script, capabilities) { sid, tid -> store.templateExists(sid, tid) }
    }
    val canRun = !RpaValidator.hasErrors(issues) &&
        !RpaReadiness.hasErrors(issues) &&
        script.enabledSteps().isNotEmpty()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AdvisorPage(
            title = script.name.ifBlank { stringResource(R.string.float_rpa_title) },
            onBack = onBack,
        ) { inner ->
            Column(modifier = Modifier.fillMaxSize().padding(inner)) {
                EditorHeader(
                    script = script,
                    running = running,
                    onScriptChange = onScriptChange,
                    onSave = onSave,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    FilterChip(
                        selected = tab == EditorTab.FLOW,
                        onClick = { tab = EditorTab.FLOW },
                        enabled = !running,
                        label = { Text(stringResource(R.string.float_rpa_editor_flow)) },
                    )
                    FilterChip(
                        selected = tab == EditorTab.PROPS,
                        onClick = { tab = EditorTab.PROPS },
                        enabled = !running,
                        label = { Text(stringResource(R.string.float_rpa_editor_props)) },
                    )
                }
                when (tab) {
                    EditorTab.FLOW -> FlowPane(
                        modifier = Modifier.weight(1f),
                        script = script,
                        selectedStepId = selectedStepId,
                        capabilities = capabilities,
                        canRun = canRun && !otherRunning,
                        running = running,
                        runningStepIndex = if (running) runningState?.stepIndex else null,
                        issues = issues,
                        events = events,
                        onSelectStep = {
                            onSelectStep(it)
                            tab = EditorTab.PROPS
                        },
                        onScriptChange = onScriptChange,
                        onSave = onSave,
                        onRun = onRun,
                        onStop = onStopRun,
                        onRefreshCapabilities = onRefreshCapabilities,
                        onGrantProjection = onGrantProjection,
                        onOpenShizuku = onOpenShizuku,
                    )
                    EditorTab.PROPS -> PropertiesPane(
                        modifier = Modifier.weight(1f),
                        script = script,
                        step = selectedStep,
                        capabilities = capabilities,
                        running = running,
                        scrollToStep = focusStepOnce,
                        onScriptChange = onScriptChange,
                        onOpenVision = onOpenVision,
                        onStartPick = onStartPick,
                        onBackToFlow = { tab = EditorTab.FLOW },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorHeader(
    script: RpaScript,
    running: Boolean,
    onScriptChange: (RpaScript) -> Unit,
    onSave: () -> Unit,
) {
    RpaCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name.ifBlank { stringResource(R.string.float_rpa_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = script.appPackage.ifBlank {
                        stringResource(R.string.float_rpa_no_app_package)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) {
                Text(
                    text = stringResource(R.string.float_rpa_running),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            TextButton(onClick = onSave, enabled = !running) {
                Text(stringResource(R.string.float_rpa_save))
            }
        }
        RpaModeSelector(
            mode = script.mode,
            enabled = !running,
            onMode = { mode -> onScriptChange(script.copy(mode = mode)) },
        )
    }
}

// ---------------------------- 动作流程页 ----------------------------

@Composable
private fun FlowPane(
    modifier: Modifier,
    script: RpaScript,
    selectedStepId: String?,
    capabilities: RpaCapabilities,
    canRun: Boolean,
    running: Boolean,
    runningStepIndex: Int?,
    issues: List<RpaIssue>,
    events: List<RpaRunEvent>,
    onSelectStep: (String) -> Unit,
    onScriptChange: (RpaScript) -> Unit,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onRefreshCapabilities: () -> Unit,
    onGrantProjection: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val edgePx = with(LocalDensity.current) { 72.dp.toPx() }
    var drag by remember { mutableStateOf<DragPayload?>(null) }
    val anchors = remember { mutableStateMapOf<String, Float>() }
    val stepTops = remember { mutableStateMapOf<String, Float>() }
    val stepBottoms = remember { mutableStateMapOf<String, Float>() }
    val undoStack = remember { mutableStateListOf<RpaScript>() }
    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }
    var rootHeight by remember { mutableStateOf(0f) }
    var autoScrollDirection by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val activeStepId = runningStepIndex?.let { script.steps.getOrNull(it)?.id }

    // 运行中把当前步骤滚到可见：前两项是动作库与流程标题，步骤从下标 2 开始。
    LaunchedEffect(runningStepIndex, script.id) {
        val index = runningStepIndex ?: return@LaunchedEffect
        listState.animateScrollToItem(index = (index + 2).coerceAtLeast(0))
    }

    // 拖到列表上下边缘时持续滚动；方向回到 0 或拖拽结束即停。
    LaunchedEffect(autoScrollDirection) {
        if (autoScrollDirection == 0) return@LaunchedEffect
        while (isActive) {
            if (drag == null) break
            listState.scrollBy(autoScrollDirection * EDGE_SCROLL_PX)
            delay(16L)
        }
    }

    fun pushUndo() {
        undoStack.add(script)
        while (undoStack.size > UNDO_LIMIT) undoStack.removeAt(0)
    }

    /** 结构性改动（增 / 删 / 移 / 启停）才进撤销栈，属性输入不逐步压栈。 */
    fun applyStructural(updated: RpaScript) {
        pushUndo()
        onScriptChange(updated)
    }

    fun updateEdgeScroll(localY: Float) {
        autoScrollDirection = when {
            rootHeight <= edgePx * 2f -> 0
            localY < edgePx -> -1
            localY > rootHeight - edgePx -> 1
            else -> 0
        }
    }

    fun targetIndex(y: Float): Int {
        val ordered = script.steps.mapNotNull { step ->
            anchors[step.id]?.let { step.id to it }
        }
        return ordered.count { (_, center) -> center < y }
    }

    fun beginDrag(
        source: DragSource,
        action: RpaAction?,
        stepId: String?,
        label: String,
        position: Offset,
    ) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        updateEdgeScroll(position.y - rootTopLeft.y)
        drag = DragPayload(
            source = source,
            action = action,
            stepId = stepId,
            label = label,
            position = position,
            targetIndex = targetIndex(position.y),
        )
    }

    fun updateDrag(delta: Offset) {
        val current = drag ?: return
        val next = current.position + delta
        updateEdgeScroll(next.y - rootTopLeft.y)
        drag = current.copy(position = next, targetIndex = targetIndex(next.y))
    }

    fun drop() {
        val current = drag ?: return
        drag = null
        autoScrollDirection = 0
        val insert = (current.targetIndex ?: script.steps.size)
            .coerceIn(0, script.steps.size)
        when (current.source) {
            DragSource.LIBRARY -> {
                val action = current.action ?: return
                val step = RpaCatalog.defaultStep(action, RpaIds.newId("step"))
                val list = script.steps.toMutableList()
                list.add(insert, step)
                applyStructural(script.copy(steps = list))
                onSelectStep(step.id)
            }
            DragSource.STEP -> {
                val id = current.stepId ?: return
                val from = script.steps.indexOfFirst { it.id == id }
                if (from < 0) return
                val list = script.steps.toMutableList()
                val step = list.removeAt(from)
                val target = (if (insert > from) insert - 1 else insert)
                    .coerceIn(0, list.size)
                if (target != from) {
                    list.add(target, step)
                    applyStructural(script.copy(steps = list))
                }
            }
        }
    }

    /** 插入线在窗口坐标中的 Y：用相邻步骤的行边界推算。 */
    fun insertionY(index: Int): Float? {
        val steps = script.steps
        if (steps.isEmpty()) return null
        return when {
            index <= 0 -> stepTops[steps.first().id]
            index >= steps.size -> stepBottoms[steps.last().id]
            else -> stepBottoms[steps[index - 1].id] ?: stepTops[steps[index].id]
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootTopLeft = it.boundsInWindow().topLeft
                rootHeight = it.size.height.toFloat()
            },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                ActionLibrary(
                    enabled = !running,
                    onAppend = { action ->
                        val step = RpaCatalog.defaultStep(action, RpaIds.newId("step"))
                        applyStructural(script.copy(steps = script.steps + step))
                        onSelectStep(step.id)
                    },
                    onDragStart = { action, label, position ->
                        beginDrag(DragSource.LIBRARY, action, null, label, position)
                    },
                    onDrag = { delta -> updateDrag(delta) },
                    onDragEnd = { drop() },
                    onDragCancel = { drag = null },
                )
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 4.dp),
                ) {
                    RpaSectionTitle(
                        text = stringResource(R.string.float_rpa_steps),
                        modifier = Modifier.weight(1f),
                    )
                    if (drag != null) {
                        Text(
                            text = stringResource(
                                R.string.float_rpa_insert_here,
                                (drag?.targetIndex ?: script.steps.size) + 1,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (undoStack.isNotEmpty()) {
                        TextButton(onClick = {
                            drag = null
                            autoScrollDirection = 0
                            onScriptChange(undoStack.removeAt(undoStack.lastIndex))
                        }) {
                            Text(stringResource(R.string.float_rpa_undo))
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.float_rpa_steps_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (script.steps.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.float_rpa_steps_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            itemsIndexed(script.steps, key = { _, step -> step.id }) { index, step ->
                val dragLabel = if (step.label.isBlank()) {
                    rpaActionLabel(step.action)
                } else {
                    step.label
                }
                StepRow(
                    index = index,
                    step = step,
                    mode = script.mode,
                    selected = step.id == selectedStepId,
                    active = step.id == activeStepId,
                    enabled = !running,
                    isFirst = index == 0,
                    isLast = index == script.steps.lastIndex,
                    onSelect = { onSelectStep(step.id) },
                    onToggle = { checked ->
                        applyStructural(
                            script.copy(
                                steps = script.steps.map {
                                    if (it.id == step.id) it.copy(enabled = checked) else it
                                },
                            ),
                        )
                    },
                    onMove = { delta ->
                        val from = script.steps.indexOfFirst { it.id == step.id }
                        val to = (from + delta).coerceIn(0, script.steps.lastIndex)
                        if (from >= 0 && from != to) {
                            val list = script.steps.toMutableList()
                            val moved = list.removeAt(from)
                            list.add(to, moved)
                            applyStructural(script.copy(steps = list))
                        }
                    },
                    onDelete = {
                        applyStructural(
                            script.copy(steps = script.steps.filterNot { it.id == step.id }),
                        )
                    },
                    onAnchor = { centerY, top, bottom ->
                        anchors[step.id] = centerY
                        stepTops[step.id] = top
                        stepBottoms[step.id] = bottom
                    },
                    onDragStart = { position ->
                        beginDrag(
                            source = DragSource.STEP,
                            action = step.action,
                            stepId = step.id,
                            label = dragLabel,
                            position = position,
                        )
                    },
                    onDrag = { delta -> updateDrag(delta) },
                    onDragEnd = { drop() },
                    onDragCancel = {
                        drag = null
                        autoScrollDirection = 0
                    },
                )
            }
            item {
                RunPanel(
                    capabilities = capabilities,
                    issues = issues,
                    canRun = canRun,
                    running = running,
                    events = events,
                    onSave = onSave,
                    onRun = onRun,
                    onStop = onStop,
                    onRefreshCapabilities = onRefreshCapabilities,
                    onGrantProjection = onGrantProjection,
                    onOpenShizuku = onOpenShizuku,
                )
            }
        }

        drag?.let { payload ->
            val local = payload.position - rootTopLeft
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .offset { IntOffset(local.x.roundToInt(), local.y.roundToInt()) }
                    .widthIn(max = 200.dp)
                    .alpha(0.92f),
            ) {
                Text(
                    text = payload.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.float_rpa_insert_here,
                        (payload.targetIndex ?: script.steps.size) + 1,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            // 可见插入线：吸附在相邻步骤的行边界上，滚出可视区时自动裁掉。
            val lineY = insertionY(payload.targetIndex ?: script.steps.size)
            if (lineY != null && rootHeight > 0f) {
                val localY = (lineY - rootTopLeft.y).coerceIn(0f, rootHeight - 2f)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, localY.roundToInt()) }
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionLibrary(
    enabled: Boolean,
    onAppend: (RpaAction) -> Unit,
    onDragStart: (RpaAction, String, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    RpaCard {
        RpaSectionTitle(text = stringResource(R.string.float_rpa_action_library))
        Text(
            text = stringResource(R.string.float_rpa_action_library_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        RpaCatalog.actions.groupBy { it.category }.forEach { (category, specs) ->
            Text(
                text = rpaCategoryLabel(category),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                specs.forEach { spec ->
                    var topLeft by remember(spec.action) { mutableStateOf(Offset.Zero) }
                    val label = rpaActionLabel(spec.action)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .onGloballyPositioned {
                                topLeft = it.boundsInWindow().topLeft
                            }
                            .pointerInput(spec.action, enabled) {
                                if (!enabled) return@pointerInput
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        onDragStart(spec.action, label, topLeft + offset)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        onDrag(amount)
                                    },
                                    onDragEnd = { onDragEnd() },
                                    onDragCancel = { onDragCancel() },
                                )
                            }
                            .clickable(enabled = enabled) { onAppend(spec.action) }
                            .alpha(if (enabled) 1f else 0.5f),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: RpaStep,
    mode: RpaMode,
    selected: Boolean,
    active: Boolean,
    enabled: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onSelect: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onAnchor: (centerY: Float, top: Float, bottom: Float) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var rowTop by remember(step.id) { mutableStateOf(0f) }
    var rowLeft by remember(step.id) { mutableStateOf(0f) }
    val actionLabel = rpaActionLabel(step.action)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = when {
            active -> MaterialTheme.colorScheme.tertiaryContainer
            selected -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (active) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary)
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                rowTop = bounds.top
                rowLeft = bounds.left
                onAnchor(rowTop + it.size.height / 2f, rowTop, rowTop + it.size.height)
            }
            .clickable(enabled = enabled, onClick = onSelect)
            .alpha(if (step.enabled) 1f else 0.45f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .pointerInput(step.id, enabled) {
                        if (!enabled) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                onDragStart(Offset(rowLeft + offset.x, rowTop + offset.y))
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                onDrag(amount)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragCancel() },
                        )
                    },
            ) {
                Text(
                    text = "≡",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.float_rpa_step_n, index + 1) +
                        " · " + step.label.ifBlank { actionLabel },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active) {
                        Text(
                            text = stringResource(R.string.float_rpa_running),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TargetSummary(
                        mode = mode,
                        step = step,
                    )
                }
            }
            Switch(
                checked = step.enabled,
                onCheckedChange = onToggle,
                enabled = enabled,
            )
            IconButton(onClick = { onMove(-1) }, enabled = enabled && !isFirst) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.float_rpa_move_up),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onMove(1) }, enabled = enabled && !isLast) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.float_rpa_move_down),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.float_rpa_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TargetSummary(mode: RpaMode, step: RpaStep) {
    if (!step.needsTarget()) return
    if (RpaModePolicy.a11yEnabled(mode)) {
        Text(
            text = stringResource(
                if (step.target?.a11y?.isConfigured() == true) {
                    R.string.float_rpa_target_a11y_ok
                } else {
                    R.string.float_rpa_target_a11y_missing
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (step.target?.a11y?.isConfigured() == true) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
    if (RpaModePolicy.visionEnabled(mode)) {
        Text(
            text = stringResource(
                if (step.target?.vision?.isConfigured() == true) {
                    R.string.float_rpa_target_vision_ok
                } else {
                    R.string.float_rpa_target_vision_missing
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (step.target?.vision?.isConfigured() == true) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun RunPanel(
    capabilities: RpaCapabilities,
    issues: List<RpaIssue>,
    canRun: Boolean,
    running: Boolean,
    events: List<RpaRunEvent>,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onRefreshCapabilities: () -> Unit,
    onGrantProjection: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    RpaCard(modifier = Modifier.padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RpaSectionTitle(
                text = stringResource(R.string.float_rpa_validation),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefreshCapabilities, enabled = !running) {
                Text(stringResource(R.string.float_rpa_cap_refresh))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            RpaStatusBadge(
                text = stringResource(R.string.float_rpa_cap_a11y),
                ok = capabilities.a11yReady,
            )
            RpaStatusBadge(
                text = stringResource(R.string.float_rpa_cap_shizuku),
                ok = capabilities.shizukuReady,
            )
            RpaStatusBadge(
                text = stringResource(R.string.float_rpa_cap_projection),
                ok = capabilities.projectionReady,
            )
        }
        if (!capabilities.projectionReady && !capabilities.shizukuReady) {
            OutlinedButton(
                onClick = onGrantProjection,
                enabled = !running,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(stringResource(R.string.float_rpa_grant_projection))
            }
        }
        if (!capabilities.shizukuReady) {
            OutlinedButton(
                onClick = onOpenShizuku,
                enabled = !running,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(stringResource(R.string.float_rpa_open_shizuku))
            }
        }
        if (issues.isEmpty()) {
            Text(
                text = stringResource(R.string.float_rpa_validation_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                RpaIssueList(issues)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Button(onClick = onRun, enabled = canRun && !running) {
                Text(stringResource(R.string.float_rpa_run))
            }
            if (running) {
                OutlinedButton(onClick = onStop) {
                    Text(stringResource(R.string.float_rpa_stop))
                }
            }
            OutlinedButton(onClick = onSave, enabled = !running) {
                Text(stringResource(R.string.float_rpa_save))
            }
        }
        RpaSectionTitle(
            text = stringResource(R.string.float_rpa_log),
            modifier = Modifier.padding(top = 12.dp),
        )
        if (events.isEmpty()) {
            Text(
                text = stringResource(R.string.float_rpa_log_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                events.takeLast(80).forEach { event -> RpaEventLine(event) }
            }
        }
    }
}

@Composable
private fun RpaEventLine(event: RpaRunEvent) {
    val text = when (event) {
        is RpaRunEvent.ScriptStarted -> {
            val line = event.line?.let { rpaLineLabel(it) }
            if (line == null) {
                stringResource(R.string.float_rpa_log_started_no_line, event.script.name)
            } else {
                stringResource(R.string.float_rpa_log_started, event.script.name, line)
            }
        }
        is RpaRunEvent.StepStarted -> stringResource(
            R.string.float_rpa_log_step,
            event.index + 1,
            stepTitleText(event.step),
        )
        is RpaRunEvent.StepFinished -> stringResource(
            R.string.float_rpa_log_step_finished,
            event.index + 1,
            stepTitleText(event.step),
            stringResource(
                if (event.ok) R.string.float_rpa_log_step_ok else R.string.float_rpa_log_step_fail,
            ),
            event.detail,
        )
        is RpaRunEvent.LineFallback -> stringResource(
            R.string.float_rpa_log_fallback,
            rpaLineLabel(event.from),
            rpaLineLabel(event.to),
            event.reason,
        )
        is RpaRunEvent.Log -> event.message
        is RpaRunEvent.ScriptFinished -> if (event.ok) {
            stringResource(R.string.float_rpa_log_finished_ok)
        } else {
            val failed = event.failedStepIndex?.let {
                stringResource(R.string.float_rpa_log_failed_step, it + 1)
            } ?: ""
            stringResource(
                R.string.float_rpa_log_finished_fail,
                event.detail.ifBlank { "failed" },
            ) + if (failed.isBlank()) "" else " · $failed"
        }
    }
    val color = when (event) {
        is RpaRunEvent.StepFinished -> if (event.ok) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        }
        is RpaRunEvent.LineFallback -> MaterialTheme.colorScheme.tertiary
        is RpaRunEvent.ScriptFinished -> if (event.ok) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        }
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun stepTitleText(step: RpaStep): String =
    step.label.ifBlank { rpaActionLabel(step.action) }

// ---------------------------- 动作属性页 ----------------------------

@Composable
private fun PropertiesPane(
    modifier: Modifier,
    script: RpaScript,
    step: RpaStep?,
    capabilities: RpaCapabilities,
    running: Boolean,
    scrollToStep: Boolean,
    onScriptChange: (RpaScript) -> Unit,
    onOpenVision: (String) -> Unit,
    onStartPick: (RpaPickKind) -> Unit,
    onBackToFlow: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToStep, step?.id) {
        if (scrollToStep && step != null) {
            // 等首帧布局完成再滚：否则滚动请求会早于 LazyColumn 首次 measure
            // 被忽略，视口仍停在脚本设置卡。第 0 项恒为脚本设置卡，步骤
            // 属性从第 1 项开始。
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it > 1 }
            listState.scrollToItem(1)
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item {
            ScriptSettingsCard(
                script = script,
                enabled = !running,
                onScriptChange = onScriptChange,
            )
        }
        if (step == null) {
            item {
                Text(
                    text = stringResource(R.string.float_rpa_prop_no_step),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                OutlinedButton(onClick = onBackToFlow) {
                    Text(stringResource(R.string.float_rpa_editor_flow))
                }
            }
        } else {
            item {
                StepBasicCard(
                    step = step,
                    enabled = !running,
                    onStepChange = { updated ->
                        onScriptChange(
                            script.copy(
                                steps = script.steps.map {
                                    if (it.id == updated.id) updated else it
                                },
                            ),
                        )
                    },
                )
            }
            item {
                StepParamsCard(
                    step = step,
                    enabled = !running,
                    onStepChange = { updated ->
                        onScriptChange(
                            script.copy(
                                steps = script.steps.map {
                                    if (it.id == updated.id) updated else it
                                },
                            ),
                        )
                    },
                )
            }
            if (step.needsTarget()) {
                item {
                    TargetCards(
                        script = script,
                        step = step,
                        enabled = !running,
                        onStepChange = { updated ->
                            onScriptChange(
                                script.copy(
                                    steps = script.steps.map {
                                        if (it.id == updated.id) updated else it
                                    },
                                ),
                            )
                        },
                        onOpenVision = { onOpenVision(step.id) },
                        onPickA11y = { onStartPick(RpaPickKind.A11Y) },
                        onPickVision = { onStartPick(RpaPickKind.VISION) },
                    )
                }
            } else {
                item {
                    RpaCard(modifier = Modifier.padding(top = 8.dp)) {
                        RpaSectionTitle(text = stringResource(R.string.float_rpa_prop_target))
                        Text(
                            text = stringResource(R.string.float_rpa_prop_no_target),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        item {
            VariablesCard(
                script = script,
                enabled = !running,
                onScriptChange = onScriptChange,
            )
        }
        item {
            RpaSectionTitle(
                text = stringResource(R.string.float_rpa_capabilities),
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RpaStatusBadge(
                    text = stringResource(R.string.float_rpa_cap_a11y),
                    ok = capabilities.a11yReady,
                )
                RpaStatusBadge(
                    text = stringResource(R.string.float_rpa_cap_shizuku),
                    ok = capabilities.shizukuReady,
                )
                RpaStatusBadge(
                    text = stringResource(R.string.float_rpa_cap_ocr),
                    ok = capabilities.ocrReady,
                )
            }
        }
    }
}

@Composable
private fun ScriptSettingsCard(
    script: RpaScript,
    enabled: Boolean,
    onScriptChange: (RpaScript) -> Unit,
) {
    RpaCard {
        RpaSectionTitle(text = stringResource(R.string.float_rpa_script_settings))
        OutlinedTextField(
            value = script.name,
            onValueChange = { onScriptChange(script.copy(name = it)) },
            label = { Text(stringResource(R.string.float_rpa_new_name)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        OutlinedTextField(
            value = script.appPackage,
            onValueChange = { onScriptChange(script.copy(appPackage = it)) },
            label = { Text(stringResource(R.string.float_rpa_editor_app_package)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        OutlinedTextField(
            value = script.description,
            onValueChange = { onScriptChange(script.copy(description = it)) },
            label = { Text(stringResource(R.string.float_rpa_script_description)) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

@Composable
private fun StepBasicCard(
    step: RpaStep,
    enabled: Boolean,
    onStepChange: (RpaStep) -> Unit,
) {
    RpaCard(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RpaSectionTitle(
                text = stringResource(R.string.float_rpa_prop_action),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = rpaActionLabel(step.action) + " · " +
                    rpaCategoryLabel(RpaCatalog.specOf(step.action).category),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.float_rpa_prop_enabled),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = step.enabled,
                onCheckedChange = { onStepChange(step.copy(enabled = it)) },
                enabled = enabled,
            )
        }
        OutlinedTextField(
            value = step.label,
            onValueChange = { onStepChange(step.copy(label = it)) },
            label = { Text(stringResource(R.string.float_rpa_prop_label)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = step.delayBeforeMs.toString(),
                onValueChange = { raw ->
                    onStepChange(step.copy(delayBeforeMs = raw.clampDelayMs()))
                },
                label = { Text(stringResource(R.string.float_rpa_prop_delay)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 4.dp),
            )
            OutlinedTextField(
                value = step.delayAfterMs.toString(),
                onValueChange = { raw ->
                    onStepChange(step.copy(delayAfterMs = raw.clampDelayMs()))
                },
                label = { Text(stringResource(R.string.float_rpa_prop_delay_after)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = step.timeoutMs.toString(),
                onValueChange = { raw ->
                    onStepChange(step.copy(timeoutMs = raw.toLongOrNull() ?: 0L))
                },
                label = { Text(stringResource(R.string.float_rpa_prop_timeout)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 4.dp),
            )
            OutlinedTextField(
                value = step.retries.toString(),
                onValueChange = { raw ->
                    onStepChange(step.copy(retries = raw.toIntOrNull() ?: 0))
                },
                label = { Text(stringResource(R.string.float_rpa_prop_retries)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 4.dp),
            )
        }
        Text(
            text = stringResource(R.string.float_rpa_prop_failure),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = step.onFailure == RpaFailurePolicy.STOP,
                onClick = { onStepChange(step.copy(onFailure = RpaFailurePolicy.STOP)) },
                enabled = enabled,
                label = { Text(stringResource(R.string.float_rpa_prop_failure_stop)) },
            )
            FilterChip(
                selected = step.onFailure == RpaFailurePolicy.CONTINUE,
                onClick = {
                    onStepChange(step.copy(onFailure = RpaFailurePolicy.CONTINUE))
                },
                enabled = enabled,
                label = { Text(stringResource(R.string.float_rpa_prop_failure_continue)) },
            )
        }
        OutlinedTextField(
            value = step.note,
            onValueChange = { onStepChange(step.copy(note = it)) },
            label = { Text(stringResource(R.string.float_rpa_prop_note)) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepParamsCard(
    step: RpaStep,
    enabled: Boolean,
    onStepChange: (RpaStep) -> Unit,
) {
    RpaCard(modifier = Modifier.padding(top = 8.dp)) {
        RpaSectionTitle(text = stringResource(R.string.float_rpa_prop_params))
        Text(
            text = stringResource(R.string.float_rpa_param_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )
        when (step.action) {
            RpaAction.LAUNCH_APP -> AppPickerField(
                value = step.params.packageName.orEmpty(),
                label = stringResource(R.string.float_rpa_param_package),
                enabled = enabled,
                onChange = { value ->
                    onStepChange(step.copy(params = step.params.copy(packageName = value)))
                },
            )
            RpaAction.OPEN_CHAT -> {
                AppPickerField(
                    value = step.params.imPackage.orEmpty(),
                    label = stringResource(R.string.float_rpa_param_im_package),
                    enabled = enabled,
                    onChange = { value ->
                        onStepChange(step.copy(params = step.params.copy(imPackage = value)))
                    },
                )
                ParamField(
                    value = step.params.contactName.orEmpty(),
                    label = stringResource(R.string.float_rpa_param_contact),
                    enabled = enabled,
                    onChange = { value ->
                        onStepChange(step.copy(params = step.params.copy(contactName = value)))
                    },
                )
            }
            RpaAction.WAIT -> ParamField(
                value = (step.params.waitMs ?: 0).toString(),
                label = stringResource(R.string.float_rpa_param_wait),
                enabled = enabled,
                onChange = { value ->
                    onStepChange(
                        step.copy(
                            params = step.params.copy(waitMs = value.toLongOrNull() ?: 0L),
                        ),
                    )
                },
            )
            RpaAction.INPUT_TEXT -> {
                ParamField(
                    value = step.params.text.orEmpty(),
                    label = stringResource(R.string.float_rpa_param_text),
                    enabled = enabled,
                    multiline = true,
                    onChange = { value ->
                        onStepChange(step.copy(params = step.params.copy(text = value)))
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.float_rpa_param_clear_first),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = step.params.clearFirst,
                        onCheckedChange = { checked ->
                            onStepChange(
                                step.copy(params = step.params.copy(clearFirst = checked)),
                            )
                        },
                        enabled = enabled,
                    )
                }
            }
            RpaAction.PRESS_KEY -> {
                Text(
                    text = stringResource(R.string.float_rpa_param_key),
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("back", "home", "recents", "notifications", "enter")
                        .forEach { key ->
                            FilterChip(
                                selected = step.params.key == key,
                                onClick = {
                                    onStepChange(
                                        step.copy(params = step.params.copy(key = key)),
                                    )
                                },
                                enabled = enabled,
                                label = { Text(key) },
                            )
                        }
                }
            }
            RpaAction.SWIPE,
            RpaAction.SCROLL,
            -> {
                Text(
                    text = stringResource(R.string.float_rpa_param_direction),
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RpaDirection.entries.forEach { direction ->
                        FilterChip(
                            selected = step.params.direction == direction,
                            onClick = {
                                onStepChange(
                                    step.copy(
                                        params = step.params.copy(direction = direction),
                                    ),
                                )
                            },
                            enabled = enabled,
                            label = { Text(rpaDirectionLabel(direction)) },
                        )
                    }
                }
                val ratio = (step.params.distanceRatio ?: 0.5f).coerceIn(0.05f, 0.9f)
                Text(
                    text = stringResource(
                        R.string.float_rpa_param_distance,
                        "%.2f".format(ratio),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = ratio,
                    onValueChange = { value ->
                        onStepChange(
                            step.copy(params = step.params.copy(distanceRatio = value)),
                        )
                    },
                    valueRange = 0.05f..0.9f,
                    enabled = enabled,
                )
                ParamField(
                    value = (step.params.durationMs ?: 300).toString(),
                    label = stringResource(R.string.float_rpa_param_duration),
                    enabled = enabled,
                    onChange = { value ->
                        onStepChange(
                            step.copy(
                                params = step.params.copy(
                                    durationMs = value.toIntOrNull() ?: 0,
                                ),
                            ),
                        )
                    },
                )
            }
            RpaAction.ASSERT_TEXT -> {
                val expect = step.expect ?: RpaExpect()
                ParamField(
                    value = expect.text,
                    label = stringResource(R.string.float_rpa_prop_expect),
                    enabled = enabled,
                    multiline = true,
                    onChange = { value ->
                        onStepChange(step.copy(expect = expect.copy(text = value)))
                    },
                )
                Text(
                    text = stringResource(R.string.float_rpa_prop_match),
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RpaMatchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = expect.match == mode,
                            onClick = {
                                onStepChange(step.copy(expect = expect.copy(match = mode)))
                            },
                            enabled = enabled,
                            label = { Text(rpaMatchLabel(mode)) },
                        )
                    }
                }
                ParamField(
                    value = expect.timeoutMs.toString(),
                    label = stringResource(R.string.float_rpa_prop_timeout_expect),
                    enabled = enabled,
                    onChange = { value ->
                        onStepChange(
                            step.copy(
                                expect = expect.copy(
                                    timeoutMs = value.toLongOrNull() ?: 0L,
                                ),
                            ),
                        )
                    },
                )
            }
            RpaAction.TAP,
            RpaAction.LONG_PRESS,
            RpaAction.WAIT_FOR_TARGET,
            RpaAction.COMMENT,
            -> Text(
                text = stringResource(R.string.float_rpa_param_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ParamField(
    value: String,
    label: String,
    enabled: Boolean,
    multiline: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    )
}

/**
 * 包名输入 + 系统应用选择器。
 *
 * 保留手输（跨设备脚本 / 变量占位），旁边按钮弹出已安装启动器应用清单，
 * 选中后覆盖输入框，避免记错包名导致运行期 `launch_missing`。
 */
@Composable
private fun AppPickerField(
    value: String,
    label: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { pickerOpen = true }, enabled = enabled) {
            Text(stringResource(R.string.float_rpa_app_picker_pick))
        }
    }
    if (pickerOpen) {
        AppPickerDialog(
            current = value,
            onDismiss = { pickerOpen = false },
            onPick = { app ->
                pickerOpen = false
                onChange(app.packageName)
            },
        )
    }
}

@Composable
private fun AppPickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onPick: (RpaInstalledApp) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<RpaInstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { RpaInstalledApps.load(context) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_rpa_app_picker_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.float_rpa_app_picker_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val loaded = apps
                val filtered = loaded?.let {
                    val keyword = query.trim()
                    if (keyword.isEmpty()) {
                        it
                    } else {
                        it.filter { app ->
                            app.label.contains(keyword, ignoreCase = true) ||
                                app.packageName.contains(keyword, ignoreCase = true)
                        }
                    }
                }
                when {
                    loaded == null -> Text(
                        text = stringResource(R.string.float_rpa_app_picker_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )

                    filtered.isNullOrEmpty() -> Text(
                        text = stringResource(R.string.float_rpa_app_picker_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .padding(top = 8.dp),
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(app) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (app.packageName == current) {
                                    Text(
                                        text = stringResource(R.string.float_rpa_app_picker_current),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_rpa_app_picker_cancel))
            }
        },
    )
}

@Composable
private fun TargetCards(
    script: RpaScript,
    step: RpaStep,
    enabled: Boolean,
    onStepChange: (RpaStep) -> Unit,
    onOpenVision: () -> Unit,
    onPickA11y: () -> Unit,
    onPickVision: () -> Unit,
) {
    RpaSectionTitle(
        text = stringResource(R.string.float_rpa_prop_target),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    Text(
        text = stringResource(R.string.float_rpa_prop_target_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val a11yEnabled = RpaModePolicy.a11yEnabled(script.mode)
    val visionEnabled = RpaModePolicy.visionEnabled(script.mode)
    val a11y = step.target?.a11y
    val vision = step.target?.vision

    RpaCard(modifier = Modifier.padding(top = 8.dp).alpha(if (a11yEnabled) 1f else 0.55f)) {
        RpaSectionTitle(text = stringResource(R.string.float_rpa_a11y_section))
        Text(
            text = stringResource(R.string.float_rpa_a11y_external_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(
            onClick = onPickA11y,
            enabled = enabled && a11yEnabled,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.float_rpa_pick_on_screen))
        }
        if (!a11yEnabled) {
            Text(
                text = stringResource(R.string.float_rpa_a11y_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (a11y == null || !a11y.isConfigured()) {
            Text(
                text = stringResource(R.string.float_rpa_a11y_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = stringResource(
                    R.string.float_rpa_a11y_configured,
                    a11y.selectors.count { !it.isEmpty() },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            a11y.selectors.forEachIndexed { index, selector ->
                Text(
                    text = stringResource(R.string.float_rpa_a11y_selector_n, index + 1) +
                        " · " + rpaSelectorSummary(selector),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = stringResource(R.string.float_rpa_a11y_pick),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RpaPick.entries.forEach { pick ->
                    FilterChip(
                        selected = a11y.pick == pick,
                        onClick = {
                            onStepChange(
                                step.withTarget(
                                    step.target.copy(a11y = a11y.copy(pick = pick)),
                                ),
                            )
                        },
                        enabled = enabled,
                        label = { Text(rpaPickLabel(pick)) },
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    onStepChange(
                        step.withTarget(step.target.copy(a11y = null)),
                    )
                },
                enabled = enabled,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.float_rpa_a11y_clear))
            }
        }
    }

    RpaCard(modifier = Modifier.padding(top = 8.dp).alpha(if (visionEnabled) 1f else 0.55f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RpaSectionTitle(
                text = stringResource(R.string.float_rpa_vision_section),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onOpenVision,
                enabled = enabled && visionEnabled,
            ) {
                Text(stringResource(R.string.float_rpa_vision_open))
            }
        }
        OutlinedButton(
            onClick = onPickVision,
            enabled = enabled && visionEnabled,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.float_rpa_pick_on_screen))
        }
        if (!visionEnabled) {
            Text(
                text = stringResource(R.string.float_rpa_vision_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (vision == null || !vision.isConfigured()) {
            Text(
                text = stringResource(R.string.float_rpa_vision_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = stringResource(
                    R.string.float_rpa_vision_configured,
                    rpaVisionKindLabel(vision.primary.kind),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            vision.fallback?.let { fallback ->
                Text(
                    text = stringResource(
                        R.string.float_rpa_vision_fallback_configured,
                        rpaVisionKindLabel(fallback.kind),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = {
                    onStepChange(
                        step.withTarget(step.target.copy(vision = null)),
                    )
                },
                enabled = enabled,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.float_rpa_vision_clear))
            }
        }
    }
}

@Composable
private fun VariablesCard(
    script: RpaScript,
    enabled: Boolean,
    onScriptChange: (RpaScript) -> Unit,
) {
    var newKey by remember(script.id) { mutableStateOf("") }
    var newValue by remember(script.id) { mutableStateOf("") }
    val keys = (script.variables.keys + script.variableKeys()).sorted()

    RpaCard(modifier = Modifier.padding(top = 8.dp)) {
        RpaSectionTitle(text = stringResource(R.string.float_rpa_variables))
        Text(
            text = stringResource(R.string.float_rpa_variables_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        keys.forEach { key ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(88.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = script.variables[key].orEmpty(),
                    onValueChange = { value ->
                        onScriptChange(script.copy(variables = script.variables + (key to value)))
                    },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        onScriptChange(
                            script.copy(variables = script.variables - key),
                        )
                    },
                    enabled = enabled,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.float_rpa_variable_remove),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            OutlinedTextField(
                value = newKey,
                onValueChange = { raw ->
                    newKey = raw.filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
                },
                label = { Text(stringResource(R.string.float_rpa_variable_key)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                label = { Text(stringResource(R.string.float_rpa_variable_value)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(2.dp))
        }
        OutlinedButton(
            onClick = {
                val key = newKey.trim()
                if (key.isNotEmpty()) {
                    onScriptChange(
                        script.copy(variables = script.variables + (key to newValue)),
                    )
                    newKey = ""
                    newValue = ""
                }
            },
            enabled = enabled && newKey.isNotBlank(),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.float_rpa_variable_add))
        }
    }
}

private fun RpaStep.withTarget(target: RpaTarget?): RpaStep = copy(target = target)

/** 执行前 / 后等待统一 0..60s 钳制；非法输入归零。 */
private fun String.clampDelayMs(): Long = (toLongOrNull() ?: 0L).coerceIn(0L, 60_000L)
