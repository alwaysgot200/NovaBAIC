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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.baic2.device.api.OcrLine
import com.verlintas.baic2.floatball.AdvisorPage
import com.verlintas.baic2.floatball.auto.CvMatch
import com.verlintas.rpa.model.RpaBounds
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaLimits
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaPick
import com.verlintas.rpa.model.RpaRect
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import com.verlintas.rpa.store.RpaScriptStore
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class VisionTest(
    val ok: Boolean,
    val match: CvMatch? = null,
    val detail: String = "",
)

/**
 * 「以图找图」交互配置（无 a11y 线路）：
 *
 * - 帧源与执行同源（Shizuku screencap / MediaProjection），不借用 a11y 截图；
 * - 拖拽框选 → 裁剪预览 → 保存为模板 → 当前帧即时匹配（OpenCV matchTemplate）；
 * - OCR 文本行可一键生成 OCR 定位；比例坐标可直接点画面取点；
 * - 主定位 + 可选兜底定位，写回 [RpaVisionSpec]。
 */
@Composable
internal fun RpaVisionScreen(
    device: RpaAndroidDevice,
    store: RpaScriptStore,
    scriptId: String,
    initial: RpaVisionSpec? = null,
    onBack: () -> Unit,
    onApply: (RpaVisionSpec) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<RpaRect?>(null) }
    var templates by remember { mutableStateOf<List<String>>(emptyList()) }
    var templateId by remember { mutableStateOf(initial?.primary?.templateId) }
    var primary by remember { mutableStateOf(initial?.primary ?: RpaVisionLocator()) }
    var fallback by remember { mutableStateOf(initial?.fallback) }
    var ratioPickTarget by remember { mutableStateOf<Boolean?>(null) }
    var ocrLines by remember { mutableStateOf<List<OcrLine>>(emptyList()) }
    var ocrLoading by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<VisionTest?>(null) }
    var templateSavedId by remember { mutableStateOf<String?>(null) }
    var templateError by remember { mutableStateOf<String?>(null) }

    // 裁剪/压缩是位图重活：离开主线程，选区确认后才算一次。
    val cropPreview by produceState<Bitmap?>(initialValue = null, frame, selection) {
        val bitmap = frame
        val rect = selection
        value = if (bitmap == null || rect == null) {
            null
        } else {
            withContext(Dispatchers.Default) { cropBitmap(bitmap, rect) }
        }
    }
    val testBox = test?.match?.let { match ->
        FrameBox(
            bounds = RpaBounds(match.left, match.top, match.right, match.bottom),
            kind = FrameBoxKind.SELECTED,
        )
    }

    suspend fun reload() {
        loading = true
        loadError = null
        val captured = withContext(Dispatchers.IO) {
            runCatching { device.visionConfigFrame() }.getOrNull()
        }
        val ids = withContext(Dispatchers.IO) {
            runCatching { store.listTemplateIds(scriptId) }.getOrDefault(templates)
        }
        frame = captured
        templates = ids
        if (captured == null) {
            loadError = "frame_unavailable"
        }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    fun saveTemplateFromSelection() {
        val bitmap = frame ?: return
        val rect = selection ?: return
        scope.launch {
            val bytes = withContext(Dispatchers.Default) {
                cropBitmap(bitmap, rect)?.let { crop ->
                    try {
                        encodePng(crop)
                    } finally {
                        crop.recycle()
                    }
                }
            }
            if (bytes == null) {
                templateSavedId = null
                templateError = "template_encode_failed"
                return@launch
            }
            val id = RpaIds.newId("tpl")
            val saved = withContext(Dispatchers.IO) {
                store.saveTemplate(scriptId, id, bytes)
            }.getOrNull()
            if (saved == null) {
                templateSavedId = null
                templateError = "template_save_failed"
                return@launch
            }
            device.invalidateTemplate(saved)
            templateId = id
            primary = primary.copy(kind = RpaVisionKind.TEMPLATE, templateId = id)
            templates = withContext(Dispatchers.IO) {
                runCatching { store.listTemplateIds(scriptId) }.getOrDefault(templates)
            }
            templateSavedId = id
            templateError = null
            test = null
        }
    }

    fun runTemplateTest() {
        val bitmap = frame ?: return
        val id = templateId ?: return
        test = null
        scope.launch {
            val result = device.testTemplate(
                frame = bitmap,
                templateFile = store.templateFile(scriptId, id),
                threshold = primary.threshold,
                region = primary.region,
                grayscale = primary.grayscale,
            )
            test = result.fold(
                onSuccess = { match -> VisionTest(true, match = match) },
                onFailure = { error ->
                    VisionTest(false, detail = error.message ?: "match_failed")
                },
            )
        }
    }

    fun runOcr() {
        val bitmap = frame ?: return
        ocrLoading = true
        scope.launch {
            ocrLines = device.ocrLines(bitmap)
            ocrLoading = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AdvisorPage(
            title = stringResource(R.string.float_rpa_vision_title),
            onBack = onBack,
        ) { inner ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = inner.calculateTopPadding() + 8.dp,
                    bottom = inner.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item {
                    RpaCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RpaSectionTitle(
                                text = stringResource(R.string.float_rpa_vision_capture),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = { scope.launch { reload() } },
                                enabled = !loading,
                            ) {
                                Text(stringResource(R.string.float_rpa_vision_refresh))
                            }
                        }
                        Text(
                            text = stringResource(R.string.float_rpa_vision_frame_source),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (loading) {
                            Text(
                                text = stringResource(R.string.float_rpa_vision_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        loadError?.let {
                            Text(
                                text = stringResource(R.string.float_rpa_vision_no_frame),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        val bitmap = frame
                        if (bitmap != null) {
                            Text(
                                text = stringResource(
                                    if (ratioPickTarget == null) {
                                        R.string.float_rpa_vision_frame_hint
                                    } else {
                                        R.string.float_rpa_vision_ratio_pick_hint
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                            )
                            RpaFrameCanvas(
                                bitmap = bitmap,
                                previewHeight = 320.dp,
                                selection = selection,
                                boxes = listOfNotNull(testBox),
                                draggable = ratioPickTarget == null,
                                onSelection = { selection = it },
                                onTapPixel = { x, y ->
                                    val rx = x.toFloat() / bitmap.width.coerceAtLeast(1)
                                    val ry = y.toFloat() / bitmap.height.coerceAtLeast(1)
                                    when (ratioPickTarget) {
                                        false -> primary = primary.copy(
                                            kind = RpaVisionKind.RATIO,
                                            ratioX = rx,
                                            ratioY = ry,
                                        )
                                        true -> fallback = fallback?.copy(
                                            kind = RpaVisionKind.RATIO,
                                            ratioX = rx,
                                            ratioY = ry,
                                        )
                                        null -> Unit
                                    }
                                    ratioPickTarget = null
                                },
                            )
                            Text(
                                text = stringResource(
                                    R.string.float_rpa_vision_selection,
                                    selection?.let { rectSummary(it) }
                                        ?: stringResource(R.string.float_rpa_vision_selection_none),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                item {
                    RpaCard(modifier = Modifier.padding(top = 8.dp)) {
                        RpaSectionTitle(text = stringResource(R.string.float_rpa_vision_crop))
                        val crop = cropPreview
                        if (crop == null) {
                            Text(
                                text = stringResource(R.string.float_rpa_vision_selection_none),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                            RpaFrameCanvas(
                                bitmap = crop,
                                previewHeight = 140.dp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Button(
                                onClick = { saveTemplateFromSelection() },
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                Text(stringResource(R.string.float_rpa_vision_save_template))
                            }
                        }
                        templateSavedId?.let { id ->
                            Text(
                                text = stringResource(
                                    R.string.float_rpa_vision_template_saved,
                                    id,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        templateError?.let { error ->
                            Text(
                                text = stringResource(
                                    R.string.float_rpa_vision_template_save_failed,
                                    error,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text(
                            text = stringResource(R.string.float_rpa_vision_template_list),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (templates.isEmpty()) {
                            Text(
                                text = stringResource(R.string.float_rpa_vision_template_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            templates.forEach { id ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp),
                                ) {
                                    FilterChip(
                                        selected = templateId == id,
                                        onClick = {
                                            templateId = id
                                            primary = primary.copy(
                                                kind = RpaVisionKind.TEMPLATE,
                                                templateId = id,
                                            )
                                            test = null
                                        },
                                        label = {
                                            Text(
                                                text = id,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                    )
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    val file = store.templateFile(scriptId, id)
                                                    store.deleteTemplate(scriptId, id)
                                                    device.invalidateTemplate(file)
                                                }
                                                templates = withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        store.listTemplateIds(scriptId)
                                                    }.getOrDefault(emptyList())
                                                }
                                                if (templateId == id) templateId = null
                                                if (primary.templateId == id) {
                                                    primary = primary.copy(templateId = null)
                                                }
                                                if (fallback?.templateId == id) {
                                                    fallback = fallback?.copy(templateId = null)
                                                }
                                                test = null
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.float_rpa_vision_template_delete))
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    LocatorEditorCard(
                        modifier = Modifier.padding(top = 8.dp),
                        title = stringResource(R.string.float_rpa_vision_primary),
                        locator = primary,
                        templates = templates,
                        selection = selection,
                        frame = frame,
                        ratioPicking = ratioPickTarget == false,
                        onChange = { updated ->
                            primary = updated
                            // 在定位卡里换模板时同步测试目标，避免「测试」按钮
                            // 仍指向模板列表里上一次选中的那张图。
                            updated.templateId
                                ?.takeIf { updated.kind == RpaVisionKind.TEMPLATE }
                                ?.let { templateId = it }
                            test = null
                        },
                        onRatioPick = {
                            ratioPickTarget = if (ratioPickTarget == false) null else false
                        },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        OutlinedButton(
                            onClick = { runTemplateTest() },
                            enabled = !loading && templateId != null,
                        ) {
                            Text(stringResource(R.string.float_rpa_vision_test))
                        }
                        OutlinedButton(
                            onClick = { runOcr() },
                            enabled = !loading && !ocrLoading,
                        ) {
                            Text(
                                stringResource(
                                    if (ocrLoading) {
                                        R.string.float_rpa_vision_ocr_loading
                                    } else {
                                        R.string.float_rpa_vision_ocr_run
                                    },
                                ),
                            )
                        }
                    }
                    test?.let { result ->
                        val match = result.match
                        Text(
                            text = if (result.ok && match != null) {
                                stringResource(
                                    R.string.float_rpa_vision_test_ok,
                                    match.centerX,
                                    match.centerY,
                                    "%.3f".format(match.score),
                                )
                            } else {
                                stringResource(
                                    R.string.float_rpa_vision_test_fail,
                                    "%.2f".format(primary.threshold),
                                    result.detail,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.ok) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (templateId == null) {
                        Text(
                            text = stringResource(R.string.float_rpa_vision_test_no_template),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (ocrLines.isNotEmpty()) {
                    item {
                        RpaCard(modifier = Modifier.padding(top = 8.dp)) {
                            RpaSectionTitle(text = stringResource(R.string.float_rpa_vision_ocr))
                            ocrLines.take(40).forEach { line ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            primary = primary.copy(
                                                kind = RpaVisionKind.OCR_TEXT,
                                                ocrText = line.text,
                                            )
                                            test = null
                                        }
                                        .padding(vertical = 2.dp),
                                ) {
                                    Text(
                                        text = line.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "${line.centerX},${line.centerY}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(
                                        onClick = {
                                            primary = primary.copy(
                                                kind = RpaVisionKind.OCR_TEXT,
                                                ocrText = line.text,
                                            )
                                            test = null
                                        },
                                    ) {
                                        Text(stringResource(R.string.float_rpa_vision_ocr_use))
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    val currentFallback = fallback
                    if (currentFallback == null) {
                        OutlinedButton(
                            onClick = {
                                fallback = RpaVisionLocator(
                                    kind = RpaVisionKind.RATIO,
                                    ratioX = 0.92f,
                                    ratioY = 0.88f,
                                    pick = RpaPick.FIRST,
                                )
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.float_rpa_vision_add_fallback))
                        }
                    } else {
                        LocatorEditorCard(
                            modifier = Modifier.padding(top = 8.dp),
                            title = stringResource(R.string.float_rpa_vision_fallback),
                            locator = currentFallback,
                            templates = templates,
                            selection = selection,
                            frame = frame,
                            ratioPicking = ratioPickTarget == true,
                            onChange = { fallback = it },
                            onRatioPick = {
                                ratioPickTarget = if (ratioPickTarget == true) null else true
                            },
                        )
                        OutlinedButton(
                            onClick = {
                                fallback = null
                                if (ratioPickTarget == true) ratioPickTarget = null
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(stringResource(R.string.float_rpa_vision_remove_fallback))
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            onApply(
                                RpaVisionSpec(
                                    primary = primary,
                                    fallback = fallback?.takeIf { it.isConfigured() },
                                    note = initial?.note ?: "vision config",
                                ),
                            )
                        },
                        enabled = primary.isConfigured(),
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text(stringResource(R.string.float_rpa_vision_apply))
                    }
                    if (!primary.isConfigured()) {
                        Text(
                            text = stringResource(R.string.float_rpa_vision_need_kind),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocatorEditorCard(
    modifier: Modifier = Modifier,
    title: String,
    locator: RpaVisionLocator,
    templates: List<String>,
    selection: RpaRect?,
    frame: Bitmap?,
    ratioPicking: Boolean,
    onChange: (RpaVisionLocator) -> Unit,
    onRatioPick: () -> Unit,
) {
    RpaCard(modifier = modifier) {
        RpaSectionTitle(text = title)
        Text(
            text = stringResource(R.string.float_rpa_vision_kind),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RpaVisionKind.entries.forEach { kind ->
                FilterChip(
                    selected = locator.kind == kind,
                    onClick = {
                        onChange(
                            locator.copy(
                                kind = kind,
                                templateId = locator.templateId
                                    ?: templates.firstOrNull(),
                                // 比例坐标始终按整帧归一化，region 仅服务模板 / OCR；
                                // 切到 RATIO 时清掉旧 ROI，避免留下无效配置。
                                region = if (kind == RpaVisionKind.RATIO) null else locator.region,
                            ),
                        )
                    },
                    label = { Text(rpaVisionKindLabel(kind)) },
                )
            }
        }
        when (locator.kind) {
            RpaVisionKind.TEMPLATE -> {
                if (templates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.float_rpa_vision_template_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        templates.forEach { id ->
                            FilterChip(
                                selected = locator.templateId == id,
                                onClick = {
                                    onChange(locator.copy(templateId = id))
                                },
                                label = {
                                    Text(
                                        text = id,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
                // 图11 相似度：整数百分比 50–99%，显示当前值；阈值内部仍是 0..1 双精度。
                Text(
                    text = stringResource(
                        R.string.float_rpa_vision_threshold,
                        "${(locator.threshold * 100).roundToInt()}%",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = ((locator.threshold * 100).roundToInt().coerceIn(50, 99)).toFloat(),
                    onValueChange = { percent ->
                        onChange(
                            locator.copy(
                                threshold = percent.roundToInt().coerceIn(50, 99) / 100.0,
                            ),
                        )
                    },
                    valueRange = 50f..99f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.float_rpa_vision_grayscale),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = locator.grayscale,
                        onCheckedChange = { onChange(locator.copy(grayscale = it)) },
                    )
                }
                // 图11 匹配第几（选填，1 起）：多命中时取第 N 个；留空 = 不指定。
                var matchIndexText by remember(locator.matchIndex) {
                    mutableStateOf(locator.matchIndex?.toString().orEmpty())
                }
                OutlinedTextField(
                    value = matchIndexText,
                    onValueChange = { raw ->
                        matchIndexText = raw
                        val parsed = raw.trim().toIntOrNull()
                        onChange(
                            locator.copy(
                                matchIndex = when {
                                    raw.isBlank() -> null
                                    parsed != null -> parsed.coerceAtLeast(1)
                                    else -> locator.matchIndex
                                },
                            ),
                        )
                    },
                    label = { Text(stringResource(R.string.float_rpa_vision_match_index)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
                // 图11 ☐快速搜图
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = locator.quickSearch,
                        onCheckedChange = { onChange(locator.copy(quickSearch = it)) },
                    )
                    Text(
                        text = stringResource(R.string.float_rpa_vision_quick_search),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // 图11「埋图模式」截图截断、语义待确认（P2 待确认）：只落模型默认值字段占位，
                // 禁用 + 「即将开放」，不做假入口。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = locator.embedMode,
                        onValueChange = {},
                        label = { Text(stringResource(R.string.float_rpa_vision_embed_mode)) },
                        enabled = false,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.float_rpa_property_coming_soon),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            RpaVisionKind.OCR_TEXT -> {
                OutlinedTextField(
                    value = locator.ocrText.orEmpty(),
                    onValueChange = { onChange(locator.copy(ocrText = it)) },
                    label = { Text(stringResource(R.string.float_rpa_vision_ocr)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.float_rpa_prop_match),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RpaMatchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = locator.ocrMatch == mode,
                            onClick = { onChange(locator.copy(ocrMatch = mode)) },
                            label = { Text(rpaMatchLabel(mode)) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.float_rpa_vision_ocr_pick),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RpaPick.entries.forEach { pick ->
                        FilterChip(
                            selected = locator.pick == pick,
                            onClick = { onChange(locator.copy(pick = pick)) },
                            label = { Text(rpaPickLabel(pick)) },
                        )
                    }
                }
            }
            RpaVisionKind.RATIO -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = "%.2f".format(locator.ratioX),
                        onValueChange = { raw ->
                            raw.toFloatOrNull()?.let { value ->
                                onChange(locator.copy(ratioX = value.coerceIn(0f, 1f)))
                            }
                        },
                        label = { Text(stringResource(R.string.float_rpa_vision_ratio_x)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = "%.2f".format(locator.ratioY),
                        onValueChange = { raw ->
                            raw.toFloatOrNull()?.let { value ->
                                onChange(locator.copy(ratioY = value.coerceIn(0f, 1f)))
                            }
                        },
                        label = { Text(stringResource(R.string.float_rpa_vision_ratio_y)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedButton(
                    onClick = onRatioPick,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        stringResource(
                            if (ratioPicking) {
                                R.string.float_rpa_vision_ratio_pick_cancel
                            } else {
                                R.string.float_rpa_vision_ratio_pick
                            },
                        ),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RpaPick.entries.forEach { pick ->
                        FilterChip(
                            selected = locator.pick == pick,
                            onClick = { onChange(locator.copy(pick = pick)) },
                            label = { Text(rpaPickLabel(pick)) },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = locator.offsetX.toString(),
                onValueChange = { raw ->
                    onChange(locator.copy(offsetX = raw.toIntOrNull() ?: 0))
                },
                label = { Text(stringResource(R.string.float_rpa_vision_offset_x)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = locator.offsetY.toString(),
                onValueChange = { raw ->
                    onChange(locator.copy(offsetY = raw.toIntOrNull() ?: 0))
                },
                label = { Text(stringResource(R.string.float_rpa_vision_offset_y)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        if (locator.kind != RpaVisionKind.RATIO) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.float_rpa_vision_region),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = locator.region != null,
                    onCheckedChange = { checked ->
                        onChange(
                            locator.copy(
                                // 无选区时给整屏默认框，方便直接拖框调整（图11）
                                region = if (checked) {
                                    selection ?: RpaRect(0f, 0f, 1f, 1f)
                                } else {
                                    null
                                },
                            ),
                        )
                    },
                )
            }
            Text(
                text = locator.region?.let {
                    stringResource(R.string.float_rpa_vision_region_value, rectSummary(it))
                } ?: stringResource(R.string.float_rpa_vision_region_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val roi = locator.region
            if (roi != null) {
                Text(
                    text = stringResource(R.string.float_rpa_vision_roi_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                RoiDragBox(
                    region = roi,
                    frame = frame,
                    onChange = { onChange(locator.copy(region = it)) },
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoiPercentField(
                        label = stringResource(R.string.float_rpa_vision_roi_left),
                        value = roi.left,
                        range = RpaLimits.RATIO_MIN..(roi.right - ROI_MIN_SIZE),
                        onChange = { left ->
                            onChange(locator.copy(region = roi.copy(left = left)))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    RoiPercentField(
                        label = stringResource(R.string.float_rpa_vision_roi_top),
                        value = roi.top,
                        range = RpaLimits.RATIO_MIN..(roi.bottom - ROI_MIN_SIZE),
                        onChange = { top ->
                            onChange(locator.copy(region = roi.copy(top = top)))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoiPercentField(
                        label = stringResource(R.string.float_rpa_vision_roi_right),
                        value = roi.right,
                        range = (roi.left + ROI_MIN_SIZE)..RpaLimits.RATIO_MAX,
                        onChange = { right ->
                            onChange(locator.copy(region = roi.copy(right = right)))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    RoiPercentField(
                        label = stringResource(R.string.float_rpa_vision_roi_bottom),
                        value = roi.bottom,
                        range = (roi.top + ROI_MIN_SIZE)..RpaLimits.RATIO_MAX,
                        onChange = { bottom ->
                            onChange(locator.copy(region = roi.copy(bottom = bottom)))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (selection != null) {
                TextButton(
                    onClick = { onChange(locator.copy(region = selection)) },
                ) {
                    Text(stringResource(R.string.float_rpa_vision_region_use_selection))
                }
            }
            if (locator.region != null) {
                TextButton(onClick = { onChange(locator.copy(region = null)) }) {
                    Text(stringResource(R.string.float_rpa_vision_region_clear))
                }
            }
        }
    }
}

private fun encodePng(bitmap: Bitmap): ByteArray? =
    ByteArrayOutputStream().use { sink ->
        if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, sink)) {
            sink.toByteArray()
        } else {
            null
        }
    }

private fun cropBitmap(bitmap: Bitmap, rect: RpaRect): Bitmap? {
    val normalized = rect.normalized()
    if (!normalized.isValid()) return null
    val left = (normalized.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
    val top = (normalized.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
    val right = (normalized.right * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
    val bottom = (normalized.bottom * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)
    return runCatching {
        Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }.getOrNull()
}

private fun rectSummary(rect: RpaRect): String {
    // 取原值不钳 0..1：限制区域允许越界（图11），摘要须与四值输入一致。
    return buildString {
        append((rect.left * 100).roundToInt())
        append("%,")
        append((rect.top * 100).roundToInt())
        append("% → ")
        append((rect.right * 100).roundToInt())
        append("%,")
        append((rect.bottom * 100).roundToInt())
        append('%')
    }
}

// ---------------------------- 图11 限制区域（ROI）编辑 ----------------------------

/** ROI 最小边长（比例），保证拖框 / 改值后仍是有效矩形。 */
private const val ROI_MIN_SIZE = 0.01f

/** 图11 限制区域单值输入：% 整数、可越界；文本与数值双向同步（拖框改数值即回显）。 */
@Composable
private fun RoiPercentField(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf((value * 100).roundToInt().toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            raw.trim().toIntOrNull()?.let { percent ->
                onChange(
                    (percent / 100f)
                        .coerceAtLeast(range.start)
                        .coerceAtMost(range.endInclusive),
                )
            }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

/** 拖框手势：框内拖动平移，四角拖动改大小。 */
private enum class RoiDrag {
    MOVE,
    LEFT_TOP,
    RIGHT_TOP,
    LEFT_BOTTOM,
    RIGHT_BOTTOM,
}

/** 拖框画布的映射几何：单位方形（0..1）→ 画布像素矩形。 */
private data class RoiFit(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private fun roiFit(canvas: IntSize, frame: Bitmap?): RoiFit {
    if (canvas.width <= 0 || canvas.height <= 0) return RoiFit(0f, 0f, 0f, 0f)
    if (frame == null) {
        // 无帧时整块画布当单位方形，拖框仍可用（纯比例坐标）。
        return RoiFit(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
    }
    val scale = min(
        canvas.width.toFloat() / frame.width.coerceAtLeast(1),
        canvas.height.toFloat() / frame.height.coerceAtLeast(1),
    )
    val width = frame.width * scale
    val height = frame.height * scale
    return RoiFit(
        left = (canvas.width - width) / 2f,
        top = (canvas.height - height) / 2f,
        width = width,
        height = height,
    )
}

private fun roiDragAt(offset: Offset, region: RpaRect, fit: RoiFit, handlePx: Float): RoiDrag? {
    if (fit.width <= 0f || fit.height <= 0f) return null
    val left = fit.left + region.left * fit.width
    val top = fit.top + region.top * fit.height
    val right = fit.left + region.right * fit.width
    val bottom = fit.top + region.bottom * fit.height
    fun near(x: Float, y: Float): Boolean =
        abs(offset.x - x) <= handlePx && abs(offset.y - y) <= handlePx
    return when {
        near(left, top) -> RoiDrag.LEFT_TOP
        near(right, top) -> RoiDrag.RIGHT_TOP
        near(left, bottom) -> RoiDrag.LEFT_BOTTOM
        near(right, bottom) -> RoiDrag.RIGHT_BOTTOM
        offset.x in min(left, right)..max(left, right) &&
            offset.y in min(top, bottom)..max(top, bottom) -> RoiDrag.MOVE
        else -> null
    }
}

/** 拖框增量 → 新 ROI（比例值可越界，钳在 [RpaLimits] 允许区间并保持最小框）。 */
private fun RpaRect.shiftRoi(drag: RoiDrag, du: Float, dv: Float): RpaRect {
    val lo = RpaLimits.RATIO_MIN
    val hi = RpaLimits.RATIO_MAX
    return when (drag) {
        RoiDrag.MOVE -> {
            val width = right - left
            val height = bottom - top
            val newLeft = (left + du)
                .coerceAtLeast(lo)
                .coerceAtMost((hi - width).coerceAtLeast(lo))
            val newTop = (top + dv)
                .coerceAtLeast(lo)
                .coerceAtMost((hi - height).coerceAtLeast(lo))
            RpaRect(newLeft, newTop, newLeft + width, newTop + height)
        }
        RoiDrag.LEFT_TOP -> copy(
            left = (left + du).coerceAtLeast(lo).coerceAtMost(right - ROI_MIN_SIZE),
            top = (top + dv).coerceAtLeast(lo).coerceAtMost(bottom - ROI_MIN_SIZE),
        )
        RoiDrag.RIGHT_TOP -> copy(
            right = (right + du).coerceAtLeast(left + ROI_MIN_SIZE).coerceAtMost(hi),
            top = (top + dv).coerceAtLeast(lo).coerceAtMost(bottom - ROI_MIN_SIZE),
        )
        RoiDrag.LEFT_BOTTOM -> copy(
            left = (left + du).coerceAtLeast(lo).coerceAtMost(right - ROI_MIN_SIZE),
            bottom = (bottom + dv).coerceAtLeast(top + ROI_MIN_SIZE).coerceAtMost(hi),
        )
        RoiDrag.RIGHT_BOTTOM -> copy(
            right = (right + du).coerceAtLeast(left + ROI_MIN_SIZE).coerceAtMost(hi),
            bottom = (bottom + dv).coerceAtLeast(top + ROI_MIN_SIZE).coerceAtMost(hi),
        )
    }
}

/**
 * 图11 限制区域拖框：框内拖动平移、四角拖动改大小，与四值输入双向同步。
 * 底图取当前帧（无帧时用占位底色）；越界值仅作示意绘制，最终以数值为准。
 */
@Composable
private fun RoiDragBox(
    region: RpaRect,
    frame: Bitmap?,
    onChange: (RpaRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(frame) { frame?.asImageBitmap() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(canvasSize, frame) { roiFit(canvasSize, frame) }
    // 手势回调跨多次拖动持有引用：读最新状态，pointerInput 键稳定不重启手势。
    val currentRegion by rememberUpdatedState(region)
    val currentFit by rememberUpdatedState(fit)
    val currentChange by rememberUpdatedState(onChange)
    var dragMode by remember { mutableStateOf<RoiDrag?>(null) }
    val density = LocalDensity.current
    val handlePx = with(density) { 12.dp.toPx() }
    val strokeWidth = with(density) { 2.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(Color(0x22000000)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(frame) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragMode = roiDragAt(offset, currentRegion, currentFit, handlePx)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val mode = dragMode ?: return@detectDragGestures
                            val geometry = currentFit
                            if (geometry.width <= 0f || geometry.height <= 0f) {
                                return@detectDragGestures
                            }
                            currentChange(
                                currentRegion.shiftRoi(
                                    mode,
                                    amount.x / geometry.width,
                                    amount.y / geometry.height,
                                ),
                            )
                        },
                        onDragEnd = { dragMode = null },
                        onDragCancel = { dragMode = null },
                    )
                },
        ) {
            if (fit.width > 0f && fit.height > 0f) {
                val frameImage = image
                if (frameImage != null && frame != null) {
                    drawImage(
                        image = frameImage,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(frame.width, frame.height),
                        dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                        dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
                    )
                }
                val left = fit.left + region.left * fit.width
                val top = fit.top + region.top * fit.height
                val width = max((region.right - region.left) * fit.width, 1f)
                val height = max((region.bottom - region.top) * fit.height, 1f)
                val color = Color(0xFFFF3D71)
                drawRect(
                    color = Color(0x33FF3D71),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                )
                drawRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(width = strokeWidth),
                )
                // 四角手柄：拖拽改大小的抓取点
                listOf(
                    Offset(left, top),
                    Offset(left + width, top),
                    Offset(left, top + height),
                    Offset(left + width, top + height),
                ).forEach { corner ->
                    drawRect(
                        color = color,
                        topLeft = Offset(corner.x - handlePx / 2, corner.y - handlePx / 2),
                        size = Size(handlePx, handlePx),
                    )
                }
            }
        }
    }
}
