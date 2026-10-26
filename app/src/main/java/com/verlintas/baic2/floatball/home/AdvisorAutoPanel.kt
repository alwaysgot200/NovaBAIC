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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verlintas.baic2.R
import com.verlintas.baic2.designsystem.Baic2Spacing
import com.verlintas.baic2.floatball.auto.AutoSessionScheduler

/**
 * 自动托管面板（方案 §4.4 / §5.3.3）：状态签点击进入，列出全部托管目标的
 * 实时状态，支持单目标暂停 / 恢复与「全部停止」。
 *
 * 只读调度器快照 + 下发暂停/停止指令，不持有任何会话业务对象；停止仍走
 * [AdvisorHomeLauncher.stopAuto] 让服务统一收口（清队列、清红点、复位运行态）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvisorAutoPanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entry = rememberAdvisorHomeEntry()
    val scheduler = remember(entry) { entry.autoScheduler() }
    val snapshot by scheduler.snapshot.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val labels = snapshot.targets
        .mapNotNull { target -> target.imPackage }
        .distinct()
        .associateWith { pkg -> appLabel(context, pkg) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Baic2Spacing.xl)
                .padding(bottom = Baic2Spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.float_home_panel_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Baic2Spacing.md))
            if (snapshot.targets.isEmpty()) {
                Text(
                    text = stringResource(R.string.float_home_panel_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
                ) {
                    snapshot.targets.forEach { target ->
                        TargetRow(
                            target = target,
                            appName = target.imPackage?.let { labels[it] }.orEmpty(),
                            onTogglePause = {
                                scheduler.setPaused(target.key, target.status != AutoSessionScheduler.Status.PAUSED)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(Baic2Spacing.lg))
            Button(
                onClick = {
                    AdvisorHomeLauncher.stopAuto(context)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.float_home_panel_stop_all))
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.float_home_scope_back))
            }
        }
    }
}

@Composable
private fun TargetRow(
    target: AutoSessionScheduler.TargetState,
    appName: String,
    onTogglePause: () -> Unit,
) {
    val paused = target.status == AutoSessionScheduler.Status.PAUSED
    val failed = target.status == AutoSessionScheduler.Status.FAILED
    val active = target.status == AutoSessionScheduler.Status.ANALYZING ||
        target.status == AutoSessionScheduler.Status.NAVIGATING ||
        target.status == AutoSessionScheduler.Status.SENDING
    val dotColor = when {
        paused -> MaterialTheme.colorScheme.outline
        failed -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
            .padding(horizontal = Baic2Spacing.md, vertical = Baic2Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(Baic2Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = target.displayName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = buildString {
                if (appName.isNotEmpty()) append(appName)
                val status = statusLabel(target)
                if (status.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(status)
                }
                target.error?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
            }
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = stringResource(
                if (paused) R.string.float_home_panel_resume else R.string.float_home_panel_pause,
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onTogglePause() }
                .padding(horizontal = Baic2Spacing.sm, vertical = Baic2Spacing.xs),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun statusLabel(target: AutoSessionScheduler.TargetState): String = when (target.status) {
    AutoSessionScheduler.Status.QUEUED -> stringResource(R.string.float_home_panel_status_queued)
    AutoSessionScheduler.Status.ANALYZING -> stringResource(R.string.float_home_panel_status_analyzing)
    AutoSessionScheduler.Status.NAVIGATING -> stringResource(R.string.float_home_panel_status_navigating)
    AutoSessionScheduler.Status.SENDING -> stringResource(R.string.float_home_panel_status_sending)
    AutoSessionScheduler.Status.COOLDOWN -> stringResource(R.string.float_home_panel_status_cooldown)
    AutoSessionScheduler.Status.FAILED -> stringResource(R.string.float_home_panel_status_failed)
    AutoSessionScheduler.Status.DONE -> stringResource(R.string.float_home_panel_status_done)
    AutoSessionScheduler.Status.PAUSED -> stringResource(R.string.float_home_panel_status_paused)
}

private fun appLabel(context: android.content.Context, imPackage: String): String = runCatching {
    context.packageManager.getApplicationLabel(
        context.packageManager.getApplicationInfo(imPackage, 0),
    ).toString()
}.getOrDefault(imPackage)
