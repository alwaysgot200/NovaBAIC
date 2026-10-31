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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.auto.CvMatch
import com.verlintas.baic2.floatball.auto.ScreenCapture
import com.verlintas.baic2.floatball.rpa.RpaAndroidDevice
import com.verlintas.rpa.model.RpaBounds
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaPick
import com.verlintas.rpa.model.RpaPickPurpose
import com.verlintas.rpa.model.RpaPickRequest
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 拾取选区形状（方案 §3.2）。 */
private enum class PickShape { RECT, CIRCLE }

/** 圆形选区（圆心 + 半径，窗口坐标）。 */
private data class PickCircle(val centerX: Float, val centerY: Float, val radius: Float)

/** 矩形调整触点：四边 + 四角（角同调相邻两边）。 */
private enum class RectHandle {
    LEFT, TOP, RIGHT, BOTTOM,
    LEFT_TOP, RIGHT_TOP, LEFT_BOTTOM, RIGHT_BOTTOM,
}

/** 拖拽瞬时态：新建选区、整体移动、调整边/角/半径。 */
private sealed interface PickDrag {
    val fromX: Float
    val fromY: Float

    data class DrawRect(override val fromX: Float, override val fromY: Float) : PickDrag

    data class MoveRect(
        val base: RpaQuickFindBox,
        override val fromX: Float,
        override val fromY: Float,
    ) : PickDrag

    data class ResizeRect(
        val handle: RectHandle,
        val base: RpaQuickFindBox,
        override val fromX: Float,
        override val fromY: Float,
    ) : PickDrag

    data class DrawCircle(override val fromX: Float, override val fromY: Float) : PickDrag

    data class MoveCircle(
        val base: PickCircle,
        override val fromX: Float,
        override val fromY: Float,
    ) : PickDrag

    data class ResizeCircle(
        val base: PickCircle,
        override val fromX: Float,
        override val fromY: Float,
    ) : PickDrag
}

private fun rectFromDrag(fromX: Float, fromY: Float, toX: Float, toY: Float): RpaQuickFindBox =
    RpaQuickFindBox(
        left = min(fromX, toX),
        top = min(fromY, toY),
        right = max(fromX, toX),
        bottom = max(fromY, toY),
    )

/** 按触点调整矩形（角同调相邻两边）；不允许越过对边（各保 1px）。 */
private fun rectWithHandle(
    base: RpaQuickFindBox,
    handle: RectHandle,
    x: Float,
    y: Float,
): RpaQuickFindBox = when (handle) {
    RectHandle.LEFT -> base.copy(left = min(x, base.right - 1f))
    RectHandle.RIGHT -> base.copy(right = max(x, base.left + 1f))
    RectHandle.TOP -> base.copy(top = min(y, base.bottom - 1f))
    RectHandle.BOTTOM -> base.copy(bottom = max(y, base.top + 1f))
    RectHandle.LEFT_TOP -> base.copy(
        left = min(x, base.right - 1f),
        top = min(y, base.bottom - 1f),
    )

    RectHandle.RIGHT_TOP -> base.copy(
        right = max(x, base.left + 1f),
        top = min(y, base.bottom - 1f),
    )

    RectHandle.LEFT_BOTTOM -> base.copy(
        left = min(x, base.right - 1f),
        bottom = max(y, base.top + 1f),
    )

    RectHandle.RIGHT_BOTTOM -> base.copy(
        right = max(x, base.left + 1f),
        bottom = max(y, base.top + 1f),
    )
}

/**
 * 命中矩形调整触点：先四角（容差内取最近角），再四边（容差内取最近边）；
 * 都不中返回 null，由调用方按「区域内=整体移动 / 区域外=新建」分流。
 */
private fun hitRectHandle(
    x: Float,
    y: Float,
    box: RpaQuickFindBox,
    tolerance: Float,
): RectHandle? {
    val corners = listOf(
        RectHandle.LEFT_TOP to hypot(x - box.left, y - box.top),
        RectHandle.RIGHT_TOP to hypot(x - box.right, y - box.top),
        RectHandle.LEFT_BOTTOM to hypot(x - box.left, y - box.bottom),
        RectHandle.RIGHT_BOTTOM to hypot(x - box.right, y - box.bottom),
    )
    corners.minByOrNull { it.second }?.let { if (it.second <= tolerance) return it.first }
    val edges = buildList {
        if (x >= box.left - tolerance && x <= box.right + tolerance) {
            add(RectHandle.TOP to abs(y - box.top))
            add(RectHandle.BOTTOM to abs(y - box.bottom))
        }
        if (y >= box.top - tolerance && y <= box.bottom + tolerance) {
            add(RectHandle.LEFT to abs(x - box.left))
            add(RectHandle.RIGHT to abs(x - box.right))
        }
    }
    return edges.filter { it.second <= tolerance }.minByOrNull { it.second }?.first
}

/** 整体移动矩形；偏移钳位让选区尽量留在画布内，防止拖丢。 */
private fun moveRect(
    base: RpaQuickFindBox,
    dx: Float,
    dy: Float,
    canvasW: Float,
    canvasH: Float,
): RpaQuickFindBox {
    val clampedDx = dx.coerceIn(-base.left, max(0f, canvasW - base.width) - base.left)
    val clampedDy = dy.coerceIn(-base.top, max(0f, canvasH - base.height) - base.top)
    return RpaQuickFindBox(
        left = base.left + clampedDx,
        top = base.top + clampedDy,
        right = base.right + clampedDx,
        bottom = base.bottom + clampedDy,
    )
}

/** 整体移动圆形；圆心钳位让圆尽量留在画布内（圆大于画布时锁中心）。 */
private fun moveCircle(
    base: PickCircle,
    dx: Float,
    dy: Float,
    canvasW: Float,
    canvasH: Float,
): PickCircle = base.copy(
    centerX = (base.centerX + dx).coerceIn(
        min(base.radius, canvasW / 2f),
        max(canvasW - base.radius, canvasW / 2f),
    ),
    centerY = (base.centerY + dy).coerceIn(
        min(base.radius, canvasH / 2f),
        max(canvasH - base.radius, canvasH / 2f),
    ),
)

/** 命中圆周（半径柄）视为调半径。 */
private fun hitCircleRim(
    x: Float,
    y: Float,
    circle: PickCircle,
    tolerance: Float,
): Boolean = abs(hypot(x - circle.centerX, y - circle.centerY) - circle.radius) <= tolerance

/** 命中圆内（含容差）视为整体移动；圆外按下点为新圆心。 */
private fun hitCircleInterior(
    x: Float,
    y: Float,
    circle: PickCircle,
    tolerance: Float,
): Boolean = hypot(x - circle.centerX, y - circle.centerY) <= circle.radius + tolerance

/** 选区中心 → 帧比例坐标（屏幕取点 POINT 回填用）；映射失败返回 null。 */
private fun selectionCenterRatio(
    shape: PickShape,
    rect: RpaQuickFindBox?,
    circle: PickCircle?,
    frame: Bitmap,
    viewSize: IntSize,
): Pair<Float, Float>? {
    val selection = when (shape) {
        PickShape.RECT -> rect ?: return null
        PickShape.CIRCLE -> circle?.let {
            RpaQuickFindGeometry.circleBoundingSquare(it.centerX, it.centerY, it.radius)
        } ?: return null
    }
    val bounds = RpaQuickFindGeometry.frameRect(
        selection = selection,
        frameWidth = frame.width,
        frameHeight = frame.height,
        viewWidth = viewSize.width,
        viewHeight = viewSize.height,
    ) ?: return null
    val cx = (bounds.left + bounds.right) / 2
    val cy = (bounds.top + bounds.bottom) / 2
    return RpaQuickFindGeometry.ratio(cx, frame.width) to
        RpaQuickFindGeometry.ratio(cy, frame.height)
}

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
 * - ERROR：无帧源给 Shizuku 修复入口；黑帧、模板过大、低于阈值各有文案。
 *
 * 目标 App 始终在下方可见；本页只消费触摸，不把截图铺成背景。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RpaQuickFindScreen(
    device: RpaAndroidDevice,
    captureFrame: suspend () -> Result<Bitmap>,
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
    var pickFrame by remember { mutableStateOf<Bitmap?>(null) }
    var pickCapturing by remember { mutableStateOf(false) }
    var pickCropping by remember { mutableStateOf(false) }
    var pickShape by remember { mutableStateOf(PickShape.RECT) }
    var pickRect by remember { mutableStateOf<RpaQuickFindBox?>(null) }
    var pickCircle by remember { mutableStateOf<PickCircle?>(null) }

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

    /** 抓帧失败 → 用户文案（方案 §5 报因）；`sourceMissing` 由调用方按 reason 设置。 */
    fun frameErrorText(reason: String): String = when {
        reason.contains(ScreenCapture.REASON_BLANK_FRAME) ->
            context.getString(R.string.float_rpa_quickfind_error_black)

        reason.contains(ScreenCapture.REASON_CAPTURE_UNAVAILABLE) ->
            context.getString(R.string.float_rpa_quickfind_error_frame)

        else -> context.getString(
            R.string.float_rpa_quickfind_error_generic,
            reason.ifBlank { "frame_null" },
        )
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
                error = frameErrorText(reason)
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

    /**
     * 屏幕拾取（方案 §3.1）：自隐抓帧成功后进入冻结画布选区；失败沿用
     * 既有三类报因 + Shizuku 修复入口，绝不静默。
     */
    fun startRegionPick() {
        if (searching || pickCapturing || pickCropping) return
        pickCapturing = true
        result = null
        bestScore = null
        error = null
        sourceMissing = false
        scope.launch {
            if (!device.capabilities.screenshotReady) {
                sourceMissing = true
                error = context.getString(R.string.float_rpa_quickfind_error_frame)
                pickCapturing = false
                return@launch
            }
            val captured = captureFrame()
            val frame = captured.getOrNull()
            if (frame == null) {
                val reason = captured.exceptionOrNull()?.message.orEmpty()
                sourceMissing = reason.contains(ScreenCapture.REASON_CAPTURE_UNAVAILABLE)
                error = frameErrorText(reason)
            } else {
                pickFrame = frame
                pickRect = null
                pickCircle = null
            }
            pickCapturing = false
        }
    }

    fun cancelPickRegion() {
        pickFrame = null
        pickRect = null
        pickCircle = null
        error = null
    }

    /**
     * 确认选区（决策 C：回主卡手动查找）：IO 线程裁剪，圆形做圆外透明掩膜，
     * 产出走与相册选图完全相同的链路（`selected.png` → `templateFile` → 查找）。
     */
    fun confirmPickRegion() {
        val frame = pickFrame ?: return
        if (pickCropping) return
        val shapeNow = pickShape
        val rectSel = pickRect
        val circleSel = pickCircle
        pickCropping = true
        error = null
        // POINT 目的（屏幕取点，§7.9 排矛盾）：取选区中心比例回填 RATIO 坐标，
        // 不裁模板、不搜索，保持「点击(坐标)」概念不被翻转成「点击图片」。
        if (pickRequest?.purpose == RpaPickPurpose.POINT) {
            val center = selectionCenterRatio(shapeNow, rectSel, circleSel, frame, viewSize)
            pickCropping = false
            if (center == null) {
                error = context.getString(R.string.float_rpa_quickfind_pick_failed, "pick_point_failed")
            } else {
                onApplyPick?.invoke(
                    RpaVisionSpec(
                        primary = RpaVisionLocator(
                            kind = RpaVisionKind.RATIO,
                            ratioX = center.first,
                            ratioY = center.second,
                        ),
                    ),
                    // POINT 不写模板：bitmap/templateId 占位，applyPick 按 purpose 跳过模板落盘。
                    frame,
                    "",
                )
            }
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                when (shapeNow) {
                    PickShape.RECT -> {
                        val sel = rectSel ?: return@withContext null
                        val rect = RpaQuickFindGeometry.frameRect(
                            selection = sel,
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            viewWidth = viewSize.width,
                            viewHeight = viewSize.height,
                        ) ?: return@withContext null
                        RpaQuickFindPicker.crop(frame, rect)
                    }

                    PickShape.CIRCLE -> {
                        val sel = circleSel ?: return@withContext null
                        val rect = RpaQuickFindGeometry.frameRect(
                            selection = RpaQuickFindGeometry.circleBoundingSquare(
                                centerX = sel.centerX,
                                centerY = sel.centerY,
                                radius = sel.radius,
                            ),
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            viewWidth = viewSize.width,
                            viewHeight = viewSize.height,
                        ) ?: return@withContext null
                        val ellipse = RpaQuickFindGeometry.frameEllipse(
                            centerX = sel.centerX,
                            centerY = sel.centerY,
                            radius = sel.radius,
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            viewWidth = viewSize.width,
                            viewHeight = viewSize.height,
                        ) ?: return@withContext null
                        RpaQuickFindPicker.cropCircle(frame, rect, ellipse)
                    }
                }
            }
            when {
                bitmap == null -> error = context.getString(
                    R.string.float_rpa_quickfind_pick_failed,
                    "crop_failed",
                )

                bitmap.width < MIN_TEMPLATE_SIDE || bitmap.height < MIN_TEMPLATE_SIDE -> {
                    if (bitmap !== frame) bitmap.recycle()
                    error = context.getString(
                        R.string.float_rpa_quickfind_pick_too_small,
                        MIN_TEMPLATE_SIDE,
                    )
                }

                else -> {
                    val file = writeTemplateFile(context, bitmap)
                    if (file == null) {
                        if (bitmap !== frame) bitmap.recycle()
                        error = context.getString(
                            R.string.float_rpa_quickfind_pick_failed,
                            "template_write_failed",
                        )
                    } else {
                        template = bitmap
                        templateFile = file
                        result = null
                        bestScore = null
                        sourceMissing = false
                        error = null
                        pickFrame = null
                        pickRect = null
                        pickCircle = null
                    }
                }
            }
            pickCropping = false
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

    val pickDimens: Pair<Int, Int>? = when (pickShape) {
        PickShape.RECT -> pickRect?.let { it.width.roundToInt() to it.height.roundToInt() }
        PickShape.CIRCLE -> pickCircle?.let { sel ->
            val diameter = (sel.radius * 2f).roundToInt()
            diameter to diameter
        }
    }
    val pickValid = pickDimens != null &&
        pickDimens.first >= MIN_TEMPLATE_SIDE &&
        pickDimens.second >= MIN_TEMPLATE_SIDE

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it },
    ) {
        val pickBitmap = pickFrame
        if (pickBitmap != null) {
            PickSelectLayer(
                frame = pickBitmap,
                shape = pickShape,
                rect = pickRect,
                circle = pickCircle,
                onRectChange = { pickRect = it },
                onCircleChange = { pickCircle = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
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
        }

        // 结果坐标 chip（方案 §3.4）：十字旁显示中心坐标，框右上角显示命中尺寸。
        if (current != null && box != null) {
            val coordText = stringResource(
                R.string.float_rpa_quickfind_result_coord,
                current.match.centerX,
                current.match.centerY,
            )
            val boxText = stringResource(
                R.string.float_rpa_quickfind_result_box,
                current.match.width,
                current.match.height,
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier.offset {
                    val chipWidth = 96.dp.toPx()
                    val chipHeight = 26.dp.toPx()
                    var x = box.centerX + 10.dp.toPx()
                    if (x + chipWidth > viewSize.width) {
                        x = box.centerX - 10.dp.toPx() - chipWidth
                    }
                    var y = box.centerY + 10.dp.toPx()
                    if (y + chipHeight > viewSize.height) {
                        y = box.centerY - 10.dp.toPx() - chipHeight
                    }
                    IntOffset(
                        x.roundToInt().coerceIn(0, viewSize.width),
                        y.roundToInt().coerceIn(0, viewSize.height),
                    )
                },
            ) {
                Text(
                    text = coordText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier.offset {
                    val chipWidth = 118.dp.toPx()
                    val chipHeight = 26.dp.toPx()
                    val x = (box.right - chipWidth)
                        .coerceIn(0f, (viewSize.width - chipWidth).coerceAtLeast(0f))
                    var y = box.top - chipHeight - 4.dp.toPx()
                    if (y < 0f) y = box.top + 4.dp.toPx()
                    IntOffset(x.roundToInt(), y.roundToInt().coerceIn(0, viewSize.height))
                },
            ) {
                Text(
                    text = boxText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
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

                if (pickBitmap != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = pickShape == PickShape.RECT,
                            onClick = {
                                pickShape = PickShape.RECT
                                pickRect = null
                                pickCircle = null
                            },
                            label = {
                                Text(
                                    stringResource(R.string.float_rpa_quickfind_pick_shape_rect),
                                )
                            },
                        )
                        FilterChip(
                            selected = pickShape == PickShape.CIRCLE,
                            onClick = {
                                pickShape = PickShape.CIRCLE
                                pickRect = null
                                pickCircle = null
                            },
                            label = {
                                Text(
                                    stringResource(R.string.float_rpa_quickfind_pick_shape_circle),
                                )
                            },
                        )
                    }
                    if (pickDimens == null) {
                        Text(
                            text = stringResource(R.string.float_rpa_quickfind_pick_hint_drag),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(
                                R.string.float_rpa_quickfind_pick_size,
                                pickDimens.first,
                                pickDimens.second,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!pickValid) {
                            Text(
                                text = stringResource(
                                    R.string.float_rpa_quickfind_pick_too_small,
                                    MIN_TEMPLATE_SIDE,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    error?.let { message ->
                        QuickFindErrorBlock(
                            message = message,
                            sourceMissing = sourceMissing,
                            onOpenShizuku = onOpenShizuku,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { confirmPickRegion() },
                            enabled = pickValid && !pickCropping,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.float_rpa_quickfind_pick_use))
                        }
                        OutlinedButton(
                            onClick = { cancelPickRegion() },
                            enabled = !pickCropping,
                        ) {
                            Text(stringResource(R.string.float_rpa_quickfind_pick_cancel))
                        }
                    }
                } else {

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

                if (searching || pickCapturing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(
                                if (pickCapturing) {
                                    R.string.float_rpa_quickfind_pick_capturing
                                } else {
                                    R.string.float_rpa_quickfind_searching
                                },
                            ),
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
                    QuickFindErrorBlock(
                        message = message,
                        sourceMissing = sourceMissing,
                        onOpenShizuku = onOpenShizuku,
                    )
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            enabled = !searching && !pickCapturing,
                        ) {
                            Text(stringResource(R.string.float_rpa_quickfind_pick_image))
                        }
                        OutlinedButton(
                            onClick = { startRegionPick() },
                            enabled = !searching && !pickCapturing,
                        ) {
                            Text(stringResource(R.string.float_rpa_quickfind_pick_region))
                        }
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
                    enabled = templateFile != null && !searching && !pickCapturing,
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

/** 报因块：错误文案 + 帧源缺失时的 Shizuku 修复入口（录屏抽帧走通后自动完成）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickFindErrorBlock(
    message: String,
    sourceMissing: Boolean,
    onOpenShizuku: () -> Unit,
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    if (sourceMissing) {
        OutlinedButton(onClick = onOpenShizuku) {
            Text(stringResource(R.string.float_rpa_quickfind_open_shizuku))
        }
    }
}

/**
 * 冻结帧拾取画布（方案 §3.1 SELECTING）：抓帧铺满全屏（FillBounds，
 * x/y 独立缩放，与 [RpaQuickFindGeometry] 同口径）+ 选区手势与绘制。
 *
 * 方形 = 对角拖出矩形，四边可再拖调整；圆形 = 按下点为圆心拖出半径，
 * 圆周半径柄可再调（决策 A）。手势只在本层消费，选区经
 * [RpaQuickFindGeometry.frameRect] / [RpaQuickFindGeometry.frameEllipse]
 * 换算为帧像素后由 [RpaQuickFindPicker] 裁剪。
 */
@Composable
private fun PickSelectLayer(
    frame: Bitmap,
    shape: PickShape,
    rect: RpaQuickFindBox?,
    circle: PickCircle?,
    onRectChange: (RpaQuickFindBox) -> Unit,
    onCircleChange: (PickCircle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tolerance = with(LocalDensity.current) { 24.dp.toPx() }
    var drag by remember { mutableStateOf<PickDrag?>(null) }
    // pointerInput 只以 shape 为 key（拖拽中不随选区变化重启），闭包里必须
    // 走 rememberUpdatedState 读最新选区/回调，否则命中判断用的是旧值，
    // 「移动/调整」会退化成新建选区。
    val curRect by rememberUpdatedState(rect)
    val curCircle by rememberUpdatedState(circle)
    val curOnRectChange by rememberUpdatedState(onRectChange)
    val curOnCircleChange by rememberUpdatedState(onCircleChange)

    Box(modifier = modifier) {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(shape) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            drag = when (shape) {
                                PickShape.RECT -> {
                                    val base = curRect
                                    if (base == null) {
                                        PickDrag.DrawRect(offset.x, offset.y)
                                    } else {
                                        val handle =
                                            hitRectHandle(offset.x, offset.y, base, tolerance)
                                        when {
                                            handle != null ->
                                                PickDrag.ResizeRect(
                                                    handle, base, offset.x, offset.y,
                                                )

                                            offset.x in base.left..base.right &&
                                                offset.y in base.top..base.bottom ->
                                                PickDrag.MoveRect(base, offset.x, offset.y)

                                            else -> PickDrag.DrawRect(offset.x, offset.y)
                                        }
                                    }
                                }

                                PickShape.CIRCLE -> {
                                    val base = curCircle
                                    when {
                                        base != null &&
                                            hitCircleRim(offset.x, offset.y, base, tolerance) ->
                                            PickDrag.ResizeCircle(base, offset.x, offset.y)

                                        base != null &&
                                            hitCircleInterior(offset.x, offset.y, base, tolerance) ->
                                            PickDrag.MoveCircle(base, offset.x, offset.y)

                                        else -> PickDrag.DrawCircle(offset.x, offset.y)
                                    }
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            val active = drag ?: return@detectDragGestures
                            val px = change.position.x
                            val py = change.position.y
                            when (active) {
                                is PickDrag.DrawRect ->
                                    curOnRectChange(
                                        rectFromDrag(active.fromX, active.fromY, px, py),
                                    )

                                is PickDrag.MoveRect ->
                                    curOnRectChange(
                                        moveRect(
                                            active.base,
                                            px - active.fromX,
                                            py - active.fromY,
                                            size.width.toFloat(),
                                            size.height.toFloat(),
                                        ),
                                    )

                                is PickDrag.ResizeRect ->
                                    curOnRectChange(
                                        rectWithHandle(active.base, active.handle, px, py),
                                    )

                                is PickDrag.DrawCircle ->
                                    curOnCircleChange(
                                        PickCircle(
                                            centerX = active.fromX,
                                            centerY = active.fromY,
                                            radius = hypot(px - active.fromX, py - active.fromY),
                                        ),
                                    )

                                is PickDrag.MoveCircle ->
                                    curOnCircleChange(
                                        moveCircle(
                                            active.base,
                                            px - active.fromX,
                                            py - active.fromY,
                                            size.width.toFloat(),
                                            size.height.toFloat(),
                                        ),
                                    )

                                is PickDrag.ResizeCircle ->
                                    curOnCircleChange(
                                        active.base.copy(
                                            radius = hypot(
                                                px - active.base.centerX,
                                                py - active.base.centerY,
                                            ),
                                        ),
                                    )
                            }
                        },
                        onDragEnd = { drag = null },
                        onDragCancel = { drag = null },
                    )
                },
        ) {
            val green = Color(0xFF22C55E)
            when (shape) {
                PickShape.RECT -> rect?.let { sel ->
                    drawRect(
                        color = green.copy(alpha = 0.20f),
                        topLeft = Offset(sel.left, sel.top),
                        size = Size(sel.width, sel.height),
                    )
                    drawRect(
                        color = green,
                        topLeft = Offset(sel.left, sel.top),
                        size = Size(sel.width, sel.height),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    // 四角触点：提示可拖角同调两边
                    for (corner in listOf(
                        Offset(sel.left, sel.top),
                        Offset(sel.right, sel.top),
                        Offset(sel.left, sel.bottom),
                        Offset(sel.right, sel.bottom),
                    )) {
                        drawCircle(
                            color = Color.White,
                            radius = 5.dp.toPx(),
                            center = corner,
                        )
                        drawCircle(
                            color = green,
                            radius = 5.dp.toPx(),
                            center = corner,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }

                PickShape.CIRCLE -> circle?.let { sel ->
                    val center = Offset(sel.centerX, sel.centerY)
                    if (sel.radius > 0f) {
                        drawCircle(
                            color = green.copy(alpha = 0.20f),
                            radius = sel.radius,
                            center = center,
                        )
                        drawCircle(
                            color = green,
                            radius = sel.radius,
                            center = center,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 6.dp.toPx(),
                            center = Offset(sel.centerX + sel.radius, sel.centerY),
                        )
                    }
                    drawCircle(
                        color = green,
                        radius = 4.dp.toPx(),
                        center = center,
                    )
                }
            }
        }
    }
}

private const val MAX_TEMPLATE_SIDE = 2_000
private const val MIN_TEMPLATE_SIDE = 8
