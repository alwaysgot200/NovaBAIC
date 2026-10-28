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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.baic2.device.api.OcrLine
import com.verlintas.baic2.floatball.AdvisorPage
import com.verlintas.baic2.floatball.auto.CvMatch
import com.verlintas.rpa.model.RpaBounds
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaPick
import com.verlintas.rpa.model.RpaRect
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import com.verlintas.rpa.store.RpaScriptStore
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    var templates by remember { mutableStateOf(runCatching { store.listTemplateIds(scriptId) }.getOrDefault(emptyList())) }
    var templateId by remember { mutableStateOf(initial?.primary?.templateId) }
    var primary by remember { mutableStateOf(initial?.primary ?: RpaVisionLocator()) }
    var fallback by remember { mutableStateOf(initial?.fallback) }
    var ratioPickTarget by remember { mutableStateOf<Boolean?>(null) }
    var ocrLines by remember { mutableStateOf<List<OcrLine>>(emptyList()) }
    var ocrLoading by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<VisionTest?>(null) }
    var templateSavedId by remember { mutableStateOf<String?>(null) }
    var templateError by remember { mutableStateOf<String?>(null) }

    val cropPreview = remember(frame, selection) {
        val bitmap = frame
        val rect = selection
        if (bitmap == null || rect == null) null else cropBitmap(bitmap, rect)
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
        frame = runCatching { device.visionConfigFrame() }.getOrNull()
        templates = runCatching { store.listTemplateIds(scriptId) }.getOrDefault(templates)
        if (frame == null) {
            loadError = "frame_unavailable"
        }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    fun saveTemplateFromSelection() {
        val bitmap = frame ?: return
        val rect = selection ?: return
        val crop = cropBitmap(bitmap, rect) ?: return
        val bytes = ByteArrayOutputStream().use { sink ->
            if (crop.compress(Bitmap.CompressFormat.PNG, 100, sink)) {
                sink.toByteArray()
            } else {
                null
            }
        } ?: return
        val id = RpaIds.newId("tpl")
        store.saveTemplate(scriptId, id, bytes)
            .onSuccess {
                templateId = id
                primary = primary.copy(kind = RpaVisionKind.TEMPLATE, templateId = id)
                templates = runCatching { store.listTemplateIds(scriptId) }
                    .getOrDefault(templates)
                templateSavedId = id
                templateError = null
                test = null
            }
            .onFailure {
                templateSavedId = null
                templateError = it.message ?: "template_save_failed"
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
                                            store.deleteTemplate(scriptId, id)
                                            templates = runCatching {
                                                store.listTemplateIds(scriptId)
                                            }.getOrDefault(emptyList())
                                            if (templateId == id) templateId = null
                                            if (primary.templateId == id) {
                                                primary = primary.copy(templateId = null)
                                            }
                                            if (fallback?.templateId == id) {
                                                fallback = fallback?.copy(templateId = null)
                                            }
                                            test = null
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
                Text(
                    text = stringResource(
                        R.string.float_rpa_vision_threshold,
                        "%.2f".format(locator.threshold),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = locator.threshold.toFloat(),
                    onValueChange = {
                        onChange(locator.copy(threshold = it.toDouble()))
                    },
                    valueRange = 0.5f..0.99f,
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
                                region = if (checked) selection else null,
                            ),
                        )
                    },
                    enabled = selection != null || locator.region != null,
                )
            }
            Text(
                text = locator.region?.let {
                    stringResource(R.string.float_rpa_vision_region_value, rectSummary(it))
                } ?: stringResource(R.string.float_rpa_vision_region_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    val normalized = rect.normalized()
    return buildString {
        append((normalized.left * 100).roundToInt())
        append("%,")
        append((normalized.top * 100).roundToInt())
        append("% → ")
        append((normalized.right * 100).roundToInt())
        append("%,")
        append((normalized.bottom * 100).roundToInt())
        append('%')
    }
}
