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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.auto.CvMatch
import com.verlintas.baic2.floatball.auto.ScreenCapture
import com.verlintas.baic2.floatball.rpa.RpaAndroidDevice
import com.verlintas.rpa.model.RpaBounds
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaPick
import com.verlintas.rpa.model.RpaPickRequest
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 命中结果连同本次搜索参数一起保存，改滑杆不会改写已复制的 JSON。 */
private data class QuickFindResult(
    val match: CvMatch,
    val frameWidth: Int,
    val frameHeight: Int,
    val threshold: Double,
    val grayscale: Boolean,
    val capturedAt: Long,
) {
    val ratioX: Float get() = RpaQuickFindGeometry.ratio(match.centerX, frameWidth)
    val ratioY: Float get() = RpaQuickFindGeometry.ratio(match.centerY, frameHeight)
}

/**
 * 以图找图 · 透明全屏结果浮层（方案 §6）。
 *
 * - PICK：选图 + 阈值 + 灰度；图片平时只落在 cacheDir，只有「应用到当前步骤」
 *   才写入脚本模板目录（方案 §7.8 拾取桥）；
 * - SEARCHING：由 Activity 把自身 decor 隐藏后抓帧（见 [RpaQuickFindActivity]），
 *   这里只负责发起与收结果；
 * - RESULT：绿色命中框 + 十字中心（[Canvas]）+ 底部结果卡（像素/比例/得分）；
 * - ERROR：无帧源给 Shizuku / 录屏授权入口；黑帧、模板过大、低于阈值各有文案。
 *
 * 目标 App 始终在下方可见；本页只消费触摸，不把截图铺成背景。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RpaQuickFindScreen(
    device: RpaAndroidDevice,
    captureFrame: suspend () -> Result<Bitmap>,
    grantProjection: suspend () -> Boolean,
    onOpenShizuku: () -> Unit,
    onExternalFlow: () -> Unit,
    onClose: () -> Unit,
    pickRequest: RpaPickRequest? = null,
    onApplyPick: ((spec: RpaVisionSpec, template: Bitmap, templateId: String) -> Unit)? = null,
    onCancelPick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var template by remember { mutableStateOf<Bitmap?>(null) }
    var templateFile by remember { mutableStateOf<File?>(null) }
    var threshold by remember { mutableStateOf(0.80f) }
    var grayscale by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<QuickFindResult?>(null) }
    var bestScore by remember { mutableStateOf<Double?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourceMissing by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    fun copyToClipboard(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            Toast.makeText(context, R.string.float_rpa_quickfind_copy_failed, Toast.LENGTH_SHORT)
                .show()
            return
        }
        runCatching { manager.setPrimaryClip(ClipData.newPlainText("rpa", text)) }
            .onSuccess {
                Toast.makeText(context, R.string.float_rpa_quickfind_copied, Toast.LENGTH_SHORT)
                    .show()
            }
            .onFailure {
                Toast.makeText(
                    context,
                    R.string.float_rpa_quickfind_copy_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = decodeTemplate(context, uri)
            val bitmap = decoded.getOrNull()
            if (bitmap == null) {
                error = context.getString(R.string.float_rpa_quickfind_error_decode)
                return@launch
            }
            val file = writeTemplateFile(context, bitmap)
            if (file == null) {
                error = context.getString(
                    R.string.float_rpa_quickfind_error_generic,
                    "template_write_failed",
                )
                return@launch
            }
            template = bitmap
            templateFile = file
            result = null
            bestScore = null
            sourceMissing = false
            error = null
        }
    }

    fun startSearch() {
        val file = templateFile ?: return
        if (searching) return
        searching = true
        result = null
        bestScore = null
        error = null
        sourceMissing = false
        val searchThreshold = threshold.toDouble()
        val searchGrayscale = grayscale
        scope.launch {
            if (!device.capabilities.screenshotReady) {
                sourceMissing = true
                error = context.getString(R.string.float_rpa_quickfind_error_frame)
                searching = false
                return@launch
            }
            val captured = captureFrame()
            val frame = captured.getOrNull()
            if (frame == null) {
                val reason = captured.exceptionOrNull()?.message.orEmpty()
                sourceMissing = reason.contains(ScreenCapture.REASON_CAPTURE_UNAVAILABLE)
                error = when {
                    reason.contains(ScreenCapture.REASON_BLANK_FRAME) ->
                        context.getString(R.string.float_rpa_quickfind_error_black)

                    sourceMissing ->
                        context.getString(R.string.float_rpa_quickfind_error_frame)

                    else -> context.getString(
                        R.string.float_rpa_quickfind_error_generic,
                        reason.ifBlank { "frame_null" },
                    )
                }
                searching = false
                return@launch
            }
            val best = device.bestTemplateMatch(
                frame = frame,
                templateFile = file,
                region = null,
                grayscale = searchGrayscale,
            )
            val match = best.getOrNull()
            if (match == null) {
                val reason = best.exceptionOrNull()?.message.orEmpty()
                error = when {
                    reason.contains("template_larger_than_region") ->
                        context.getString(R.string.float_rpa_quickfind_error_too_large)

                    reason.contains("template_missing") ||
                        reason.contains("template_decode_failed") ->
                        context.getString(R.string.float_rpa_quickfind_error_decode)

                    else -> context.getString(
                        R.string.float_rpa_quickfind_error_generic,
                        reason.ifBlank { "match_failed" },
                    )
                }
            } else if (match.score < searchThreshold) {
                bestScore = match.score
            } else {
                result = QuickFindResult(
                    match = match,
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    threshold = searchThreshold,
                    grayscale = searchGrayscale,
                    capturedAt = System.currentTimeMillis(),
                )
            }
            searching = false
        }
    }

    fun grantProjectionNow() {
        if (searching) return
        scope.launch {
            val granted = runCatching { grantProjection() }.getOrDefault(false)
            if (granted) {
                sourceMissing = false
                error = null
            } else {
                sourceMissing = true
                error = context.getString(R.string.float_rpa_quickfind_error_frame)
            }
        }
    }

    val current = result
    val box = if (current == null) {
        null
    } else {
        RpaQuickFindGeometry.viewBox(
            frameWidth = current.frameWidth,
            frameHeight = current.frameHeight,
            viewWidth = viewSize.width,
            viewHeight = viewSize.height,
            match = RpaBounds(
                left = current.match.left,
                top = current.match.top,
                right = current.match.right,
                bottom = current.match.bottom,
            ),
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val target = box ?: return@Canvas
            val green = Color(0xFF22C55E)
            drawRect(
                color = Color(0x3322C55E),
                topLeft = Offset(target.left, target.top),
                size = Size(target.width, target.height),
            )
            drawRect(
                color = green,
                topLeft = Offset(target.left, target.top),
                size = Size(target.width, target.height),
                style = Stroke(width = 3.dp.toPx()),
            )
            val arm = 18.dp.toPx()
            drawLine(
                color = Color.White,
                start = Offset(target.centerX - arm, target.centerY),
                end = Offset(target.centerX + arm, target.centerY),
                strokeWidth = 2.dp.toPx(),
            )
            drawLine(
                color = Color.White,
                start = Offset(target.centerX, target.centerY - arm),
                end = Offset(target.centerX, target.centerY + arm),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(
                color = green,
                radius = 5.dp.toPx(),
                center = Offset(target.centerX, target.centerY),
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 10.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.62f),
        ) {
            Text(
                text = stringResource(R.string.float_rpa_quickfind_top_hint),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        val maxCardHeight = maxHeight * 0.66f
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxCardHeight),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.float_rpa_quickfind_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.float_rpa_quickfind_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.float_rpa_quickfind_close))
                    }
                }

                if (pickRequest != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.float_rpa_pick_banner,
                                    pickRequest.scriptName,
                                    pickRequest.displayStep(null),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (onCancelPick != null) {
                                TextButton(onClick = onCancelPick) {
                                    Text(stringResource(R.string.float_rpa_pick_cancel))
                                }
                            }
                        }
                    }
                }

                current?.let { found ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.float_rpa_quickfind_result_score,
                                    RpaQuickFindJson.formatScore(found.match.score),
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(
                                    R.string.float_rpa_quickfind_result_center,
                                    found.match.centerX,
                                    found.match.centerY,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = stringResource(
                                    R.string.float_rpa_quickfind_result_ratio,
                                    RpaQuickFindJson.ratioPair(found.ratioX, found.ratioY),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = stringResource(
                                    R.string.float_rpa_quickfind_result_time,
                                    remember(found.capturedAt) {
                                        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                            .format(Date(found.capturedAt))
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                    .copy(alpha = 0.72f),
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(
                                    RpaQuickFindJson.ratioPair(found.ratioX, found.ratioY),
                                )
                            },
                        ) {
                            Text(stringResource(R.string.float_rpa_quickfind_copy_ratio))
                        }
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(
                                    RpaQuickFindJson.visionSpec(
                                        ratioX = found.ratioX,
                                        ratioY = found.ratioY,
                                        threshold = found.threshold,
                                        grayscale = found.grayscale,
                                        centerX = found.match.centerX,
                                        centerY = found.match.centerY,
                                        frameWidth = found.frameWidth,
                                        frameHeight = found.frameHeight,
                                        score = found.match.score,
                                    ),
                                )
                            },
                        ) {
                            Text(stringResource(R.string.float_rpa_quickfind_copy_json))
                        }
                        if (pickRequest != null && onApplyPick != null) {
                            val targetTemplate = template
                            Button(
                                onClick = {
                                    if (targetTemplate == null) {
                                        Toast.makeText(
                                            context,
                                            R.string.float_rpa_pick_need_result,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        val templateId = RpaIds.newId("tpl")
                                        onApplyPick(
                                            RpaVisionSpec(
                                                primary = RpaVisionLocator(
                                                    kind = RpaVisionKind.TEMPLATE,
                                                    templateId = templateId,
                                                    threshold = found.threshold,
                                                    grayscale = found.grayscale,
                                                    pick = RpaPick.BOTTOM_MOST,
                                                ),
                                                fallback = RpaVisionLocator(
                                                    kind = RpaVisionKind.RATIO,
                                                    ratioX = found.ratioX,
                                                    ratioY = found.ratioY,
                                                ),
                                                note = "quickfind center=(${found.match.centerX}," +
                                                    "${found.match.centerY}) " +
                                                    "frame=${found.frameWidth}x${found.frameHeight} " +
                                                    "score=${RpaQuickFindJson.formatScore(found.match.score)}",
                                            ),
                                            targetTemplate,
                                            templateId,
                                        )
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.float_rpa_pick_apply))
                            }
                        }
                    }
                }

                if (searching) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.float_rpa_quickfind_searching),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (current == null) {
                    bestScore?.let { score ->
                        Text(
                            text = stringResource(
                                R.string.float_rpa_quickfind_below_threshold,
                                String.format(Locale.US, "%.2f", threshold),
                                RpaQuickFindJson.formatScore(score),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (sourceMissing) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = onOpenShizuku) {
                                Text(stringResource(R.string.float_rpa_quickfind_open_shizuku))
                            }
                            OutlinedButton(onClick = { grantProjectionNow() }) {
                                Text(stringResource(R.string.float_rpa_quickfind_grant_projection))
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val preview = template
                    if (preview != null) {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (preview == null) {
                                    R.string.float_rpa_quickfind_no_image
                                } else {
                                    R.string.float_rpa_quickfind_image_ready
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.float_rpa_quickfind_pick_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            // 选图会临时退出本页可见态：先告诉 Activity 别在 onStop 清退。
                            onExternalFlow()
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        enabled = !searching,
                    ) {
                        Text(stringResource(R.string.float_rpa_quickfind_pick_image))
                    }
                }

                Text(
                    text = stringResource(
                        R.string.float_rpa_quickfind_threshold,
                        String.format(Locale.US, "%.2f", threshold),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    valueRange = 0.50f..0.95f,
                    enabled = !searching,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.float_rpa_quickfind_grayscale),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = grayscale,
                        onCheckedChange = { grayscale = it },
                        enabled = !searching,
                    )
                }

                Button(
                    onClick = { startSearch() },
                    enabled = templateFile != null && !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (current == null) {
                                R.string.float_rpa_quickfind_search
                            } else {
                                R.string.float_rpa_quickfind_research
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** 选图 → 位图：限制最大边，避免大照片直接把匹配内存打爆。 */
private suspend fun decodeTemplate(context: Context, uri: Uri): Result<Bitmap> =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                error("decode_failed")
            }
            var sample = 1
            while (bounds.outWidth / sample > MAX_TEMPLATE_SIDE ||
                bounds.outHeight / sample > MAX_TEMPLATE_SIDE
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: error("decode_failed")
            if (bitmap.width < MIN_TEMPLATE_SIDE || bitmap.height < MIN_TEMPLATE_SIDE) {
                bitmap.recycle()
                error("decode_failed")
            }
            bitmap
        }
    }

/** 模板落 cacheDir（匹配器按文件读），退出时由 Activity 清目录。 */
private suspend fun writeTemplateFile(context: Context, bitmap: Bitmap): File? =
    withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "rpa/quickfind").apply { mkdirs() }
        val file = File(dir, "selected.png")
        val ok = runCatching {
            file.outputStream().use { sink ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, sink)
            }
        }.getOrDefault(false)
        if (ok) file else null
    }

private const val MAX_TEMPLATE_SIDE = 2_000
private const val MIN_TEMPLATE_SIDE = 8
