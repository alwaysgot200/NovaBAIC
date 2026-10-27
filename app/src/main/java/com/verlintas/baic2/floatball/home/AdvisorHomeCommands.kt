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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.verlintas.baic2.R
import com.verlintas.baic2.designsystem.Baic2Spacing
import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.auto.AutoSelectionStore
import com.verlintas.baic2.floatball.auto.AutoSessionScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 主页「单次 / 连续 / 自动」三键操作条 + 运行状态签（方案 §4）。
 *
 * 只做展示与入口转发：真正的状态机在 [com.verlintas.baic2.floatball.FloatBallService]，
 * 运行态读 [AdvisorRunState]。操作条与悬浮球扇形菜单同源同状态，不重建业务逻辑。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AdvisorHomeEntryPoint {
    fun runState(): AdvisorRunState

    fun selectionStore(): AutoSelectionStore

    fun advisorPrefs(): AdvisorPrefs

    fun contactRepository(): ContactRepository

    fun autoScheduler(): AutoSessionScheduler
}

@Composable
internal fun rememberAdvisorHomeEntry(): AdvisorHomeEntryPoint {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AdvisorHomeEntryPoint::class.java,
        )
    }
}

@Composable
fun AdvisorHomeBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val entry = rememberAdvisorHomeEntry()
    val runState = remember(entry) { entry.runState() }
    val state by runState.state.collectAsState()
    // 组合内读取偏好必须走 collectAsState：直接读 StateFlow.value 不会被 Compose 观察，
    // 也会触发 lint 的 StateFlowValueCalledInComposition 错误。
    val prefsState by entry.advisorPrefs().state.collectAsState()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var riskSelection by remember { mutableStateOf<AutoSelectionStore.Selection?>(null) }

    fun commit(selection: AutoSelectionStore.Selection, confirmed: Boolean) {
        if (!confirmed && !entry.advisorPrefs().state.value.autoModeEnabled) {
            riskSelection = selection
            return
        }
        if (selection.allContacts || selection.targets.size > 1) {
            val listenerEnabled = NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName)
            if (!listenerEnabled) {
                Toast.makeText(context, R.string.float_home_auto_need_notify, Toast.LENGTH_LONG).show()
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                return
            }
        }
        entry.selectionStore().save(selection)
        if (!entry.advisorPrefs().state.value.autoModeEnabled) {
            entry.advisorPrefs().setAutoModeEnabled(true)
        }
        sheetOpen = false
        AdvisorHomeLauncher.startAuto(activity)
    }

    Column(
        modifier = modifier
            .navigationBarsPadding()
            .padding(end = Baic2Spacing.xl, bottom = FAB_CLEARANCE)
            .width(BAR_WIDTH),
        horizontalAlignment = Alignment.End,
    ) {
        AnimatedVisibility(
            visible = state.mode != AdvisorRunState.Mode.IDLE,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 },
        ) {
            StatusChip(
                state = state,
                onClick = if (state.mode == AdvisorRunState.Mode.AUTO) {
                    { panelOpen = true }
                } else {
                    null
                },
            )
        }
        AnimatedVisibility(visible = state.mode != AdvisorRunState.Mode.IDLE) {
            Spacer(Modifier.height(Baic2Spacing.sm))
        }
        CommandBar(
            state = state,
            onOneshot = { AdvisorHomeLauncher.startOneshot(activity) },
            onContinuous = { AdvisorHomeLauncher.startContinuous(activity) },
            onAuto = {
                if (state.mode == AdvisorRunState.Mode.AUTO) {
                    AdvisorHomeLauncher.stopAuto(context)
                } else {
                    sheetOpen = true
                }
            },
        )
    }

    if (sheetOpen) {
        AdvisorAutoScopeSheet(
            onDismiss = { sheetOpen = false },
            onStart = { selection -> commit(selection, confirmed = false) },
        )
    }

    if (panelOpen) {
        AdvisorAutoPanel(onDismiss = { panelOpen = false })
    }

    riskSelection?.let { selection ->
        AlertDialog(
            onDismissRequest = { riskSelection = null },
            title = { Text(stringResource(R.string.float_companion_risk_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.float_companion_risk_text,
                        prefsState.companionMaxSends,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        entry.advisorPrefs().setAutoModeEnabled(true)
                        riskSelection = null
                        commit(selection, confirmed = true)
                    },
                ) {
                    Text(stringResource(R.string.float_companion_risk_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { riskSelection = null }) {
                    Text(stringResource(R.string.float_companion_risk_cancel))
                }
            },
        )
    }
}

// ------------------------------------------------------------- 三键操作条

@Composable
private fun CommandBar(
    state: AdvisorRunState.Snapshot,
    onOneshot: () -> Unit,
    onContinuous: () -> Unit,
    onAuto: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val idle = state.mode == AdvisorRunState.Mode.IDLE
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .height(46.dp)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .semantics { contentDescription = "参谋操作：单次、连续、自动" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment(
            iconRes = R.drawable.fb_ic_cmd_oneshot,
            label = stringResource(
                if (state.mode == AdvisorRunState.Mode.ONESHOT) {
                    R.string.float_home_oneshot_running
                } else {
                    R.string.float_fan_oneshot
                },
            ),
            active = state.mode == AdvisorRunState.Mode.ONESHOT,
            enabled = idle,
            activeColor = MaterialTheme.colorScheme.primary,
            description = stringResource(
                if (state.mode == AdvisorRunState.Mode.ONESHOT) R.string.float_home_oneshot_running
                else R.string.float_home_disabled_busy,
            ),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onOneshot()
            },
        )
        Divider()
        Segment(
            iconRes = R.drawable.fb_ic_cmd_continuous,
            label = stringResource(
                if (state.mode == AdvisorRunState.Mode.CONTINUOUS) {
                    R.string.float_home_continuous_running
                } else {
                    R.string.float_fan_continuous
                },
            ),
            active = state.mode == AdvisorRunState.Mode.CONTINUOUS,
            enabled = idle,
            activeColor = MaterialTheme.colorScheme.error,
            description = stringResource(
                if (state.mode == AdvisorRunState.Mode.CONTINUOUS) R.string.float_home_continuous_running
                else R.string.float_home_disabled_busy,
            ),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onContinuous()
            },
        )
        Divider()
        Segment(
            iconRes = R.drawable.fb_ic_cmd_auto,
            label = if (state.mode == AdvisorRunState.Mode.AUTO) {
                stringResource(R.string.float_home_auto_running, state.autoTargetCount)
            } else {
                stringResource(R.string.float_split_auto)
            },
            active = state.mode == AdvisorRunState.Mode.AUTO,
            enabled = idle || state.mode == AdvisorRunState.Mode.AUTO,
            activeColor = MaterialTheme.colorScheme.primary,
            description = stringResource(
                if (state.mode == AdvisorRunState.Mode.AUTO) R.string.float_home_auto_running
                else R.string.float_home_disabled_auto,
            ),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onAuto()
            },
        )
    }
}

@Composable
private fun RowScope.Segment(
    iconRes: Int,
    label: String,
    active: Boolean,
    enabled: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    description: String,
    onClick: () -> Unit,
) {
    val alpha = if (enabled || active) 1f else 0.38f
    val contentColor = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .weight(1f)
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (active) activeColor.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(enabled = enabled) { onClick() }
            .alpha(alpha)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            active && !enabled -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
            active -> PulsingDot(color = contentColor)
            else -> Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(14.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

@Composable
private fun PulsingDot(color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "auto-dot")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "auto-dot-scale",
    )
    val dotAlpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "auto-dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .scale(scale)
            .alpha(dotAlpha)
            .clip(CircleShape)
            .background(color),
    )
}

// --------------------------------------------------------------- 状态签

/**
 * 运行状态签（方案 §4.4 + 2026-10-27 §14.1）：自动档按 [AdvisorRunState.AutoPhase]
 * 显示启动/导航/等待阶段与当前注释，避免「点了自动十几秒没反应」。
 */
@Composable
private fun StatusChip(state: AdvisorRunState.Snapshot, onClick: (() -> Unit)? = null) {
    val text = when (state.mode) {
        AdvisorRunState.Mode.CONTINUOUS -> stringResource(R.string.float_home_status_continuous)
        AdvisorRunState.Mode.AUTO -> autoChipText(state)
        else -> ""
    }
    if (text.isEmpty()) return
    Surface(
        modifier = if (onClick != null) {
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
        } else {
            Modifier
        },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 自动档状态签文案：启动阶段优先，注释（note）非空时优先展示为副说明。 */
@Composable
private fun autoChipText(state: AdvisorRunState.Snapshot): String {
    val note = state.autoNote.orEmpty()
    return when (state.autoPhase) {
        AdvisorRunState.AutoPhase.STARTING -> stringResource(
            R.string.float_home_status_auto_starting,
            state.autoActiveContact.orEmpty(),
            state.autoActiveApp.orEmpty(),
        )
        AdvisorRunState.AutoPhase.NAVIGATING -> note.ifEmpty {
            stringResource(
                R.string.float_home_status_auto_navigating,
                state.autoActiveApp.orEmpty(),
            )
        }
        AdvisorRunState.AutoPhase.WAITING -> stringResource(
            R.string.float_home_status_auto_waiting,
            state.autoActiveContact.orEmpty(),
            state.autoActiveApp.orEmpty(),
        )
        AdvisorRunState.AutoPhase.FAILED -> stringResource(
            R.string.float_home_status_auto_failed,
            state.autoNote.orEmpty(),
        )
        AdvisorRunState.AutoPhase.IDLE,
        AdvisorRunState.AutoPhase.READY,
        -> {
            val base = if (state.autoTargetCount <= 1) {
                stringResource(
                    R.string.float_home_status_auto_one,
                    state.autoActiveContact.orEmpty(),
                    state.autoActiveApp.orEmpty(),
                )
            } else {
                stringResource(
                    R.string.float_home_status_auto_many,
                    state.autoTargetCount,
                    state.autoActiveContact.orEmpty(),
                    state.autoActiveApp.orEmpty(),
                )
            }
            if (note.isEmpty()) base else "$base · $note"
        }
    }
}

// --------------------------------------------------------------- utils

private val BAR_WIDTH = 232.dp

/** FAB（56dp）+ 外边距 24dp + 间距 12dp：操作条底边压在「新对话」上方。 */
private val FAB_CLEARANCE = 92.dp

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
