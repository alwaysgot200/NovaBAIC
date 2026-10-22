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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.advisor.EngagementRules
import com.verlintas.baic2.floatball.advisor.FacetEditor
import com.verlintas.baic2.floatball.advisor.SelfFacets
import com.verlintas.baic2.floatball.advisor.StyleVocab
import com.verlintas.baic2.floatball.advisor.data.ContactProfileEntity
import com.verlintas.baic2.floatball.advisor.data.ENGAGEMENT_MUTE
import com.verlintas.baic2.floatball.advisor.data.ENGAGEMENT_PROACTIVE
import com.verlintas.baic2.floatball.advisor.data.ENGAGEMENT_REACTIVE
import com.verlintas.baic2.floatball.advisor.data.PRIVACY_POLICY_ASSESS
import com.verlintas.baic2.floatball.advisor.data.PRIVACY_POLICY_BYPASS
import com.verlintas.baic2.floatball.advisor.data.PRIVACY_POLICY_HANDOFF
import com.verlintas.baic2.floatball.advisor.data.SelfPersonaEntity
import com.verlintas.baic2.floatball.advisor.data.normalizePrivacyPolicy
import com.verlintas.baic2.floatball.experts.strategist.TALKER_ASSESS
import com.verlintas.baic2.floatball.experts.strategist.TALKER_DIM_EXEMPLIFYING
import com.verlintas.baic2.floatball.experts.strategist.TALKER_DIM_LYING
import com.verlintas.baic2.floatball.experts.strategist.TALKER_DIM_PROMISING
import com.verlintas.baic2.floatball.experts.strategist.TALKER_FREE
import com.verlintas.baic2.floatball.experts.strategist.TALKER_NEVER
import com.verlintas.baic2.floatball.experts.strategist.TalkerStrategyCodec
import kotlinx.coroutines.launch

/**
 * 画像页（GoalTab）的「双方人设与维持度」区块（VA-2，UltraAdvisor v2.2 方案
 * §2.4/§2.5/§2.6/§4.1）：对方风格三标签（identity/chatStyle/closeness 手动芯片，
 * 落库存展示词表原词，与 V-C 萃取共用同一词汇口径）+ 我方关系侧面 facet
 * （增删/从全局库选/自输，pending 差异标签核阅转正或丢弃）+ 维持度三档 +
 * 隐私策略三档（F1 handoff/bypass + T1 assess 自评）+ 聊天手段策略三维三档
 * （v12 T1-a：撒谎/许愿/举例，走 experts/strategist/TalkerStrategy）+ 阶段意图
 * 手填（D5）+ isGroup 开关 + 手动合并入口（选候选 → 选主档案，跨包名同人才合并）。
 *
 * 独立成文件而非内嵌 GoalTab：既有大文件只做一行挂载，保持追加式 diff；
 * 编辑去重逻辑在 [FacetEditor]（纯函数，有单测）；写路径全部经
 * [ContactRepository]，UI 不直连 DAO。profile 由 GoalTab 的 observeProfile
 * Flow 传入，写库后自动刷新，无需本地缓存。
 */
@Composable
internal fun AdvisorPersonaCard(
    contactId: Long,
    profile: ContactProfileEntity?,
    contacts: ContactRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var facetDialogOpen by remember { mutableStateOf(false) }
    var mergePicking by remember { mutableStateOf(false) }
    var mergeCandidate by remember { mutableStateOf<ContactProfileEntity?>(null) }

    // F8 单一事实来源：芯片选项数组直接引用 StyleVocab 词表常量（值=展示词=
    // 判定词，写库值必在词表内），杜绝「UI 存 strings.xml 中文值、判定侧比对
    // StyleVocab 常量」的双份手抄漂移。
    val identityOptions = StyleVocab.IDENTITIES
    val styleOptions = StyleVocab.CHAT_STYLES
    val closenessOptions = StyleVocab.CLOSENESS_LEVELS.mapIndexed { level, word -> level to word }
    // 我方侧面 facet 与合并差异 pending（§2.4/§2.5）：组合顶层解码，Surface 内
    // 区块与弹窗共用同一来源（profile Flow 写库后自动刷新）。V-C 起待确认列扩为
    // 通用通道（含 AI 萃取带入的 style 三标签待定值），decodePending 向后兼容旧数组。
    val facet = SelfFacets.decode(profile?.selfFacetJson)
    val stylePending = SelfFacets.decodePending(profile?.selfFacetPendingJson)

    /** 合并落库 + 结果提示（失败原因映射用户文案，不吞异常）。 */
    fun runMerge(mainId: Long, otherId: Long, mainName: String, otherName: String) {
        scope.launch {
            val result = runCatching { contacts.mergeInto(mainId, otherId) }
            val message = when {
                result.isSuccess ->
                    context.getString(R.string.float_persona_merge_done, mainName, otherName)
                result.exceptionOrNull()?.message == "type_mismatch" ->
                    context.getString(R.string.float_persona_merge_fail_type)
                else -> context.getString(R.string.float_persona_merge_fail_generic)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.float_persona_card_title),
                style = MaterialTheme.typography.titleMedium,
            )

            // ── 对方标签区（§4.1 手动通道）：点选落库，再点同一枚清除 ──
            SectionLabel(stringResource(R.string.float_persona_theirs))
            StyleChipRow(
                label = stringResource(R.string.float_persona_identity),
                options = identityOptions,
                selected = profile?.identity,
            ) { value ->
                scope.launch {
                    contacts.setStyleTags(contactId, value, profile?.chatStyle, profile?.closeness)
                }
            }
            StyleChipRow(
                label = stringResource(R.string.float_persona_chat_style),
                options = styleOptions,
                selected = profile?.chatStyle,
            ) { value ->
                scope.launch {
                    contacts.setStyleTags(contactId, profile?.identity, value, profile?.closeness)
                }
            }
            ValueChipRow(
                label = stringResource(R.string.float_persona_closeness),
                options = closenessOptions,
                selected = profile?.closeness,
            ) { value ->
                scope.launch {
                    contacts.setStyleTags(contactId, profile?.identity, profile?.chatStyle, value)
                }
            }

            // ── 我方侧面区（§2.4）：facet 展示 + 增删 + pending 差异核阅（§2.5） ──
            SectionLabel(stringResource(R.string.float_persona_mine))
            FacetChips(
                facet = facet,
                onAdd = { facetDialogOpen = true },
            ) { updated ->
                scope.launch { contacts.setSelfFacet(contactId, updated) }
            }
            if (stylePending != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.float_persona_facet_pending_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        if (stylePending.facet.isNotEmpty()) {
                            Text(
                                text = stylePending.facet.joinToString("、"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        // V-C：AI 萃取带入的 style 待定值（转正时只在对应列为空时生效）。
                        stylePending.identity?.let {
                            Text(
                                text = stringResource(R.string.float_persona_style_pending_identity, it),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        stylePending.chatStyle?.let {
                            Text(
                                text = stringResource(R.string.float_persona_style_pending_chat_style, it),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        stylePending.closeness?.let { level ->
                            Text(
                                text = stringResource(
                                    R.string.float_persona_style_pending_closeness,
                                    closenessOptions.firstOrNull { it.first == level }?.second
                                        ?: StyleVocab.closenessLabel(level),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = stringResource(R.string.float_persona_facet_pending_confirm),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clickable {
                                        scope.launch { contacts.confirmFacetPending(contactId) }
                                    }
                                    .padding(vertical = 4.dp),
                            )
                            Text(
                                text = stringResource(R.string.float_persona_facet_pending_discard),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clickable {
                                        scope.launch { contacts.discardFacetPending(contactId) }
                                    }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // ── 维持度三档（§2.6）：默认 reactive，第一手自动参与开关 ──
            SectionLabel(stringResource(R.string.float_persona_engagement))
            val engagement = EngagementRules.normalize(profile?.engagement)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = engagement == ENGAGEMENT_PROACTIVE,
                    onClick = {
                        scope.launch { contacts.setEngagement(contactId, ENGAGEMENT_PROACTIVE) }
                    },
                    label = { Text(stringResource(R.string.float_persona_engagement_proactive)) },
                )
                FilterChip(
                    selected = engagement == ENGAGEMENT_REACTIVE,
                    onClick = {
                        scope.launch { contacts.setEngagement(contactId, ENGAGEMENT_REACTIVE) }
                    },
                    label = { Text(stringResource(R.string.float_persona_engagement_reactive)) },
                )
                FilterChip(
                    selected = engagement == ENGAGEMENT_MUTE,
                    onClick = {
                        scope.launch { contacts.setEngagement(contactId, ENGAGEMENT_MUTE) }
                    },
                    label = { Text(stringResource(R.string.float_persona_engagement_mute)) },
                )
            }

            // ── 隐私策略（F1 两档 + T1 第三档）：问到我方隐私时交主人/绕行/自评 ──
            SectionLabel(stringResource(R.string.float_persona_privacy_policy))
            val privacyPolicy = normalizePrivacyPolicy(profile?.privacyPolicy)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = privacyPolicy == PRIVACY_POLICY_HANDOFF,
                    onClick = {
                        scope.launch { contacts.setPrivacyPolicy(contactId, PRIVACY_POLICY_HANDOFF) }
                    },
                    label = { Text(stringResource(R.string.float_persona_privacy_policy_handoff)) },
                )
                FilterChip(
                    selected = privacyPolicy == PRIVACY_POLICY_BYPASS,
                    onClick = {
                        scope.launch { contacts.setPrivacyPolicy(contactId, PRIVACY_POLICY_BYPASS) }
                    },
                    label = { Text(stringResource(R.string.float_persona_privacy_policy_bypass)) },
                )
                FilterChip(
                    selected = privacyPolicy == PRIVACY_POLICY_ASSESS,
                    onClick = {
                        scope.launch { contacts.setPrivacyPolicy(contactId, PRIVACY_POLICY_ASSESS) }
                    },
                    label = { Text(stringResource(R.string.float_persona_privacy_policy_assess)) },
                )
            }

            // ── 聊天手段策略（v12，T1-a 总方案 §三）：撒谎/许愿/举例三维×三档； ──
            //    第四维隐私披露即上方 privacyPolicy（单点真源）；正向手段不受此限。
            SectionLabel(stringResource(R.string.float_persona_talker_strategy))
            val talker = remember(profile?.talkerStrategyJson) {
                TalkerStrategyCodec.decode(profile?.talkerStrategyJson)
            }
            val talkerDims = listOf(
                TALKER_DIM_LYING to R.string.float_persona_talker_lying,
                TALKER_DIM_PROMISING to R.string.float_persona_talker_promising,
                TALKER_DIM_EXEMPLIFYING to R.string.float_persona_talker_exemplifying,
            )
            talkerDims.forEach { (dim, labelRes) ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        // 与右侧 FilterChip 交叉轴居中（2026-10-22：标签与档位
                        // 按钮高度不同，不声明 align 会顶对齐观感差）。
                        modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                    )
                    listOf(TALKER_NEVER, TALKER_ASSESS, TALKER_FREE).forEach { level ->
                        FilterChip(
                            selected = talker.levelOf(dim) == level,
                            onClick = {
                                scope.launch { contacts.setTalkerStrategy(contactId, talker.withLevel(dim, level)) }
                            },
                            label = {
                                Text(
                                    stringResource(
                                        when (level) {
                                            TALKER_NEVER -> R.string.float_persona_talker_never
                                            TALKER_ASSESS -> R.string.float_persona_talker_assess
                                            else -> R.string.float_persona_talker_free
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            // ── 阶段意图（v12，D5 主人手填）：军师「本阶段你要推进：…」方针来源 ──
            var stageIntentDraft by remember(profile?.stageIntent) {
                mutableStateOf(profile?.stageIntent.orEmpty())
            }
            OutlinedTextField(
                value = stageIntentDraft,
                onValueChange = { stageIntentDraft = it },
                label = { Text(stringResource(R.string.float_persona_stage_intent)) },
                placeholder = { Text(stringResource(R.string.float_persona_stage_intent_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            if (stageIntentDraft != profile?.stageIntent.orEmpty()) {
                TextButton(
                    onClick = {
                        scope.launch { contacts.setStageIntent(contactId, stageIntentDraft) }
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.float_persona_stage_intent_save))
                }
            }

            // ── 手动合并入口（§2.5）：选候选档案 → 选主档案，绝不自动 ──
            OutlinedButton(
                onClick = { mergePicking = true },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.float_persona_merge))
            }
        }
    }

    if (facetDialogOpen) {
        FacetEditDialog(
            current = facet,
            contacts = contacts,
            onDismiss = { facetDialogOpen = false },
            onSave = { merged ->
                facetDialogOpen = false
                scope.launch { contacts.setSelfFacet(contactId, merged) }
            },
        )
    }
    if (mergePicking && mergeCandidate == null) {
        MergeCandidateDialog(
            contactId = contactId,
            contactType = profile?.contactType,
            contacts = contacts,
            onDismiss = { mergePicking = false },
            onPicked = {
                mergePicking = false
                mergeCandidate = it
            },
        )
    }
    mergeCandidate?.let { candidate ->
        MergeMainDialog(
            currentId = contactId,
            currentName = profile?.displayName.orEmpty(),
            candidate = candidate,
            onDismiss = { mergeCandidate = null },
            onMerge = { mainId, otherId, mainName, otherName ->
                mergeCandidate = null
                runMerge(mainId, otherId, mainName, otherName)
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/** 单行标签芯片组（字符串选项）：点选落库，再点同一枚清除（value=null）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleChipRow(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(if (option == selected) null else option) },
                label = { Text(option) },
            )
        }
    }
}

/** 单行标签芯片组（带值选项，closeness 0~3 用）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValueChipRow(
    label: String,
    options: List<Pair<Int, String>>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, text) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(if (value == selected) null else value) },
                label = { Text(text) },
            )
        }
    }
}

/** 我方侧面标签集：已有标签点 ✕ 移除，「＋添加标签」开编辑弹窗。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetChips(
    facet: List<String>,
    onAdd: () -> Unit,
    onChange: (List<String>) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        if (facet.isEmpty()) {
            Text(
                text = stringResource(R.string.float_persona_facet_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        facet.forEach { tag ->
            AssistChip(
                onClick = { onChange(FacetEditor.removeTag(facet, tag)) },
                label = { Text(tag) },
                trailingIcon = { Text("✕") },
            )
        }
        AssistChip(
            onClick = onAdd,
            label = { Text("＋ ${stringResource(R.string.float_persona_facet_add)}") },
        )
    }
}

/**
 * facet 编辑弹窗（§2.4 每关系独立增删）：上半区从全局人设库芯片切换，
 * 下半区自由输入（顿号/逗号分隔），暂存列表实时预览、保存一次性落库。
 * 去重保序交给 [FacetEditor]（纯函数），弹窗只持暂存态。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetEditDialog(
    current: List<String>,
    contacts: ContactRepository,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var library by remember { mutableStateOf<List<SelfPersonaEntity>>(emptyList()) }
    var staged by remember(current) { mutableStateOf(current) }
    var input by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { library = contacts.selfPersonas() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_persona_facet_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.float_persona_facet_from_library),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    if (library.isEmpty()) {
                        Text(
                            text = stringResource(R.string.float_selfpersona_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    library.forEach { persona ->
                        FilterChip(
                            selected = persona.tag in staged,
                            onClick = { staged = FacetEditor.toggleTag(staged, persona.tag) },
                            label = { Text(persona.tag) },
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.float_persona_facet_custom)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = staged.joinToString("、").ifEmpty {
                        stringResource(R.string.float_persona_facet_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(FacetEditor.addTags(staged, input)) }) {
                Text(stringResource(R.string.float_profile_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_profile_cancel))
            }
        },
    )
}

/** 合并候选选择（§2.5 第 1 步）：只列同类型活跃档案（类型不同 Repository 会拒绝，前置过滤少走弯路）。 */
@Composable
private fun MergeCandidateDialog(
    contactId: Long,
    contactType: String?,
    contacts: ContactRepository,
    onDismiss: () -> Unit,
    onPicked: (ContactProfileEntity) -> Unit,
) {
    var others by remember { mutableStateOf<List<ContactProfileEntity>>(emptyList()) }
    LaunchedEffect(contactId, contactType) {
        others = contacts.listAlive().filter {
            it.id != contactId && (contactType == null || it.contactType == contactType)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_persona_merge_pick)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (others.isEmpty()) {
                    Text(stringResource(R.string.float_persona_merge_empty))
                }
                others.forEach { candidate ->
                    Text(
                        text = candidate.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPicked(candidate) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_profile_cancel))
            }
        },
    )
}

/** 主档案选择（§2.5 合并方向）：我方侧面以主档案为准，被并差异标签转待确认。 */
@Composable
private fun MergeMainDialog(
    currentId: Long,
    currentName: String,
    candidate: ContactProfileEntity,
    onDismiss: () -> Unit,
    onMerge: (mainId: Long, otherId: Long, mainName: String, otherName: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_persona_merge_choose_main)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.float_persona_merge_main, currentName),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onMerge(currentId, candidate.id, currentName, candidate.displayName)
                        }
                        .padding(vertical = 10.dp),
                )
                Text(
                    text = stringResource(R.string.float_persona_merge_main, candidate.displayName),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onMerge(candidate.id, currentId, candidate.displayName, currentName)
                        }
                        .padding(vertical = 10.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_profile_cancel))
            }
        },
    )
}
