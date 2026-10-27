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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.verlintas.baic2.floatball.AdvisorPage
import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaNodeAction
import com.verlintas.rpa.model.RpaNodeSnapshot
import com.verlintas.rpa.model.RpaPick
import com.verlintas.rpa.model.RpaSelector
import com.ven.assists.AssistsCore
import kotlinx.coroutines.launch

private enum class InspectorFilter { ALL, CLICKABLE, EDITABLE, TEXT }

/**
 * Android 界面布局分析器（参考 AutoJs6 / AutoJs 的布局工具）：
 *
 * - 截图 + 节点框叠层，点击画面命中「面积最小」的节点（最具体元素）；
 * - 节点树列表按层级缩进，可筛选可点击 / 可编辑 / 有文本；
 * - 选中节点后勾选选择器条件（文本 / 描述 / viewId / className / 可点击 /
 *   可编辑）与多命中策略，一键生成 [RpaA11ySpec] 回填编辑器。
 *
 * 只服务 a11y 线路配置；无 a11y 脚本请使用「以图找图配置」。
 */
@Composable
internal fun RpaInspectorScreen(
    device: RpaAndroidDevice,
    onBack: () -> Unit,
    onApply: (RpaA11ySpec) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var root by remember { mutableStateOf<AssistsCore.NodeTree?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedIndex by remember { mutableStateOf(-1) }
    var filter by remember { mutableStateOf(InspectorFilter.ALL) }

    val flat = remember(root) { root?.let { flattenNodeTree(it) } ?: emptyList() }
    val filtered = remember(flat, filter) {
        flat.mapIndexedNotNull { index, item ->
            val ok = when (filter) {
                InspectorFilter.ALL -> true
                InspectorFilter.CLICKABLE -> item.node.isClickable
                InspectorFilter.EDITABLE -> isNodeEditable(item.node)
                InspectorFilter.TEXT -> item.node.text.isNotBlank() || item.node.des.isNotBlank()
            }
            if (ok) index to item else null
        }
    }
    val selected = flat.getOrNull(selectedIndex)?.node
    val boxes = remember(flat, selectedIndex) {
        flat.mapIndexedNotNull { index, item ->
            val bounds = nodeBounds(item.node)
            if (bounds.isEmpty) {
                null
            } else {
                FrameBox(
                    bounds = bounds,
                    kind = when {
                        index == selectedIndex -> FrameBoxKind.SELECTED
                        isNodeEditable(item.node) -> FrameBoxKind.EDITABLE
                        item.node.isClickable -> FrameBoxKind.CLICKABLE
                        else -> FrameBoxKind.NODE
                    },
                )
            }
        }.take(300)
    }

    suspend fun reload() {
        loading = true
        loadError = null
        val shot = runCatching { device.inspectorFrame() }.getOrNull()
        val tree = runCatching { device.inspectorNodeTree() }.getOrNull()
        frame = shot
        root = tree
        selectedIndex = -1
        if (tree == null) {
            loadError = "a11y_node_tree_unavailable"
        }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(flat) {
        if (selectedIndex !in flat.indices) selectedIndex = -1
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AdvisorPage(
            title = stringResource(R.string.float_rpa_inspector_title),
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
                                text = stringResource(R.string.float_rpa_inspector_capture),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = { scope.launch { reload() } },
                                enabled = !loading,
                            ) {
                                Text(stringResource(R.string.float_rpa_inspector_refresh))
                            }
                        }
                        if (loading) {
                            Text(
                                text = stringResource(R.string.float_rpa_inspector_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        loadError?.let { reason ->
                            Text(
                                text = if (reason == "a11y_node_tree_unavailable") {
                                    stringResource(R.string.float_rpa_inspector_need_a11y)
                                } else {
                                    stringResource(R.string.float_rpa_inspector_error, reason)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        val bitmap = frame
                        if (bitmap == null) {
                            Text(
                                text = stringResource(R.string.float_rpa_inspector_no_frame),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.float_rpa_inspector_frame_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                            )
                            RpaFrameCanvas(
                                bitmap = bitmap,
                                previewHeight = 320.dp,
                                boxes = boxes,
                                onTapPixel = { x, y ->
                                    val hit = hitTestNode(flat, x, y)
                                    if (hit != null) {
                                        val index = flat.indexOfFirst { it.node === hit }
                                        if (index >= 0) selectedIndex = index
                                    }
                                },
                            )
                        }
                    }
                }
                selected?.let { node ->
                    item {
                        NodeDetailCard(
                            node = node,
                            onApply = onApply,
                        )
                    }
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    ) {
                        RpaSectionTitle(
                            text = stringResource(R.string.float_rpa_inspector_nodes, flat.size),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.float_rpa_inspector_filters),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = filter == InspectorFilter.ALL,
                            onClick = { filter = InspectorFilter.ALL },
                            label = { Text(stringResource(R.string.float_rpa_inspector_filter_all)) },
                        )
                        FilterChip(
                            selected = filter == InspectorFilter.CLICKABLE,
                            onClick = { filter = InspectorFilter.CLICKABLE },
                            label = {
                                Text(stringResource(R.string.float_rpa_inspector_filter_clickable))
                            },
                        )
                        FilterChip(
                            selected = filter == InspectorFilter.EDITABLE,
                            onClick = { filter = InspectorFilter.EDITABLE },
                            label = {
                                Text(stringResource(R.string.float_rpa_inspector_filter_editable))
                            },
                        )
                        FilterChip(
                            selected = filter == InspectorFilter.TEXT,
                            onClick = { filter = InspectorFilter.TEXT },
                            label = { Text(stringResource(R.string.float_rpa_inspector_filter_text)) },
                        )
                    }
                }
                items(filtered, key = { it.first }) { (index, item) ->
                    NodeRow(
                        index = index,
                        item = item,
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    index: Int,
    item: FlatRpaNode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val node = item.node
    val label = node.text.takeIf { it.isNotBlank() }
        ?: node.des.takeIf { it.isNotBlank() }
        ?: simpleClassName(node.className)
    val detail = buildString {
        append(simpleClassName(node.className))
        if (node.viewId.isNotBlank()) {
            append(" · ")
            append(node.viewId.substringAfterLast('/'))
        }
        if (node.boundsInScreen.width > 0 && node.boundsInScreen.height > 0) {
            append(" · ")
            append(node.boundsInScreen.left)
            append(',')
            append(node.boundsInScreen.top)
            append(' ')
            append(node.boundsInScreen.width)
            append('x')
            append(node.boundsInScreen.height)
        }
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = (item.depth.coerceAtMost(8) * 10).dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (node.isClickable) {
                NodeTag(stringResource(R.string.float_rpa_inspector_tag_clickable))
            }
            if (isNodeEditable(node)) {
                NodeTag(stringResource(R.string.float_rpa_inspector_tag_editable))
            }
        }
    }
}

@Composable
private fun NodeTag(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun NodeDetailCard(
    node: AssistsCore.NodeTree,
    onApply: (RpaA11ySpec) -> Unit,
) {
    val editable = isNodeEditable(node)
    var includeText by remember(node) { mutableStateOf(node.text.isNotBlank()) }
    var includeDesc by remember(node) {
        mutableStateOf(node.text.isBlank() && node.des.isNotBlank())
    }
    var includeId by remember(node) {
        mutableStateOf(node.text.isBlank() && node.des.isBlank() && node.viewId.isNotBlank())
    }
    var includeClass by remember(node) {
        mutableStateOf(
            node.text.isBlank() && node.des.isBlank() && node.viewId.isBlank(),
        )
    }
    var includeClickable by remember(node) { mutableStateOf(false) }
    var includeEditable by remember(node) { mutableStateOf(false) }
    var matchMode by remember(node) { mutableStateOf(RpaMatchMode.EXACT) }
    var pick by remember(node) { mutableStateOf(RpaPick.FIRST) }

    val selector = RpaSelector(
        text = node.text.takeIf { includeText && it.isNotBlank() },
        textMatch = matchMode,
        viewId = node.viewId.takeIf { includeId && it.isNotBlank() },
        description = node.des.takeIf { includeDesc && it.isNotBlank() },
        className = node.className.takeIf { includeClass && it.isNotBlank() },
        clickableOnly = includeClickable && node.isClickable,
        editableOnly = includeEditable && editable,
        enabledOnly = true,
    )

    RpaCard(modifier = Modifier.padding(top = 12.dp)) {
        RpaSectionTitle(text = stringResource(R.string.float_rpa_inspector_selected))
        Text(
            text = stringResource(
                R.string.float_rpa_inspector_attrs,
                node.text.ifBlank { "∅" },
                node.des.ifBlank { "∅" },
                node.viewId.ifBlank { "∅" },
                simpleClassName(node.className),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(
                R.string.float_rpa_inspector_flags,
                node.isClickable.toString(),
                editable.toString(),
                node.isScrollable.toString(),
                node.isVisibleToUser.toString(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.float_rpa_inspector_bounds,
                "${node.boundsInScreen.left},${node.boundsInScreen.top}-" +
                    "${node.boundsInScreen.right},${node.boundsInScreen.bottom}",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = stringResource(R.string.float_rpa_inspector_selector),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        SelectorToggle(
            label = stringResource(R.string.float_rpa_inspector_include_text),
            checked = includeText,
            enabled = node.text.isNotBlank(),
            onChange = { includeText = it },
        )
        SelectorToggle(
            label = stringResource(R.string.float_rpa_inspector_include_desc),
            checked = includeDesc,
            enabled = node.des.isNotBlank(),
            onChange = { includeDesc = it },
        )
        SelectorToggle(
            label = stringResource(R.string.float_rpa_inspector_include_id),
            checked = includeId,
            enabled = node.viewId.isNotBlank(),
            onChange = { includeId = it },
        )
        SelectorToggle(
            label = stringResource(R.string.float_rpa_inspector_include_class),
            checked = includeClass,
            enabled = node.className.isNotBlank(),
            onChange = { includeClass = it },
        )
        SelectorToggle(
            label = stringResource(R.string.float_rpa_inspector_include_clickable),
            checked = includeClickable,
            enabled = node.isClickable,
            onChange = { includeClickable = it },
        )
        SelectorToggle(
            label = stringResource(R.string.float_rpa_inspector_include_editable),
            checked = includeEditable,
            enabled = editable,
            onChange = { includeEditable = it },
        )
        if (includeText || includeDesc) {
            Text(
                text = stringResource(R.string.float_rpa_a11y_match),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RpaMatchMode.entries.forEach { mode ->
                    FilterChip(
                        selected = matchMode == mode,
                        onClick = { matchMode = mode },
                        label = { Text(rpaMatchLabel(mode)) },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.float_rpa_a11y_pick),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RpaPick.entries.forEach { candidate ->
                FilterChip(
                    selected = pick == candidate,
                    onClick = { pick = candidate },
                    label = { Text(rpaPickLabel(candidate)) },
                )
            }
        }
        Text(
            text = rpaSelectorSummary(selector),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(
            onClick = {
                onApply(
                    RpaA11ySpec(
                        selectors = listOf(selector),
                        pick = pick,
                        action = RpaNodeAction.CLICK,
                        snapshot = node.toSnapshot(),
                        note = "layout inspector: ${simpleClassName(node.className)}",
                    ),
                )
            },
            enabled = !selector.isEmpty(),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.float_rpa_inspector_generate))
        }
    }
}

@Composable
private fun SelectorToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) },
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun AssistsCore.NodeTree.toSnapshot(): RpaNodeSnapshot = RpaNodeSnapshot(
    packageName = packageName,
    className = className,
    text = text,
    description = des,
    viewId = viewId,
    hintText = hintText,
    bounds = nodeBounds(this),
    clickable = isClickable,
    editable = isNodeEditable(this),
    scrollable = isScrollable,
    focused = isFocused,
)

private fun simpleClassName(className: String): String =
    className.substringAfterLast('.').ifBlank { className }
