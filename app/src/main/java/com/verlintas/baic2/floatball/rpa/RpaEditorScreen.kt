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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.AdvisorPage
import com.verlintas.rpa.engine.RpaRunner
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    device: RpaAndroidDevice,
    store: RpaScriptStore,
    onBack: () -> Unit,
    onSelectStep: (String?) -> Unit,
    onScriptChange: (RpaScript) -> Unit,
    onSave: () -> Unit,
    onRefreshCapabilities: () -> Unit,
    onGrantProjection: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenInspector: (String) -> Unit,
    onOpenVision: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(EditorTab.FLOW) }
    var running by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf<List<RpaRunEvent>>(emptyList()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val selectedStep = script.steps.firstOrNull { it.id == selectedStepId }
    val issues: List<RpaIssue> = remember(script, capabilities) {
        RpaValidator.validate(script) +
            RpaReadiness.check(script, capabilities) { sid, tid -> store.templateExists(sid, tid) }
    }
    val canRun = !RpaValidator.hasErrors(issues) &&
        !RpaReadiness.hasErrors(issues) &&
        script.enabledSteps().isNotEmpty()

    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    fun startRun() {
        if (running || !canRun) return
        val snapshot = script
        events = emptyList()
        running = true
        job = scope.launch {
            try {
                val runner = RpaRunner(
                    device = device,
                    templateExists = { sid, tid -> store.templateExists(sid, tid) },
                )
                runner.run(snapshot) { event ->
                    events = (events + event).takeLast(200)
                }
            } finally {
                running = false
            }
        }
    }

    fun stopRun() {
        job?.cancel()
        job = null
        running = false
    }

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
                        canRun = canRun,
                        running = running,
                        issues = issues,
                        events = events,
                        onSelectStep = {
                            onSelectStep(it)
                            tab = EditorTab.PROPS
                        },
                        onScriptChange = onScriptChange,
                        onSave = onSave,
                        onRun = { startRun() },
                        onStop = { stopRun() },
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
                        onScriptChange = onScriptChange,
                        onOpenInspector = onOpenInspector,
                        onOpenVision = onOpenVision,
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
    var drag by remember { mutableStateOf<DragPayload?>(null) }
    val anchors = remember { mutableStateMapOf<String, Float>() }
    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }

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
        drag = current.copy(position = next, targetIndex = targetIndex(next.y))
    }

    fun drop() {
        val current = drag ?: return
        val insert = (current.targetIndex ?: script.steps.size)
            .coerceIn(0, script.steps.size)
        when (current.source) {
            DragSource.LIBRARY -> {
                val action = current.action ?: return
                val step = RpaCatalog.defaultStep(action, RpaIds.newId("step"))
                val list = script.steps.toMutableList()
                list.add(insert, step)
                onScriptChange(script.copy(steps = list))
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
                    onScriptChange(script.copy(steps = list))
                }
            }
        }
        drag = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootTopLeft = it.boundsInWindow().topLeft },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                ActionLibrary(
                    enabled = !running,
                    onAppend = { action ->
                        val step = RpaCatalog.defaultStep(action, RpaIds.newId("step"))
                        onScriptChange(script.copy(steps = script.steps + step))
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
                    enabled = !running,
                    isFirst = index == 0,
                    isLast = index == script.steps.lastIndex,
                    onSelect = { onSelectStep(step.id) },
                    onToggle = { checked ->
                        onScriptChange(
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
                            onScriptChange(script.copy(steps = list))
                        }
                    },
                    onDelete = {
                        onScriptChange(
                            script.copy(steps = script.steps.filterNot { it.id == step.id }),
                        )
                    },
                    onAnchor = { centerY -> anchors[step.id] = centerY },
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
                    onDragCancel = { drag = null },
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
    enabled: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onSelect: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onAnchor: (Float) -> Unit,
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
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .onGloballyPositioned {
                rowTop = it.boundsInWindow().top
                rowLeft = it.boundsInWindow().left
                onAnchor(rowTop + it.size.height / 2f)
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
    onScriptChange: (RpaScript) -> Unit,
    onOpenInspector: (String) -> Unit,
    onOpenVision: (String) -> Unit,
    onBackToFlow: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
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
                        onOpenInspector = { onOpenInspector(step.id) },
                        onOpenVision = { onOpenVision(step.id) },
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
            RpaAction.LAUNCH_APP -> ParamField(
                value = step.params.packageName.orEmpty(),
                label = stringResource(R.string.float_rpa_param_package),
                enabled = enabled,
                onChange = { value ->
                    onStepChange(step.copy(params = step.params.copy(packageName = value)))
                },
            )
            RpaAction.OPEN_CHAT -> {
                ParamField(
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

@Composable
private fun TargetCards(
    script: RpaScript,
    step: RpaStep,
    enabled: Boolean,
    onStepChange: (RpaStep) -> Unit,
    onOpenInspector: () -> Unit,
    onOpenVision: () -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            RpaSectionTitle(
                text = stringResource(R.string.float_rpa_a11y_section),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onOpenInspector,
                enabled = enabled && a11yEnabled,
            ) {
                Text(stringResource(R.string.float_rpa_a11y_open_inspector))
            }
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
