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

package com.verlintas.baic2.floatball.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.FloatBallService
import com.verlintas.baic2.floatball.ScreenAdvisor
import com.verlintas.baic2.floatball.home.AdvisorSelfWindowGate
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.advisor.PersonaFieldType
import com.verlintas.baic2.floatball.advisor.PersonaSchemas
import com.verlintas.baic2.floatball.advisor.PersonaValueCodes
import com.verlintas.baic2.floatball.advisor.PersonaValues
import com.verlintas.baic2.floatball.advisor.ResolveOutcome
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.MY_PERSONA_CONTACT_ID
import com.verlintas.baic2.floatball.advisor.data.PersonalMaterialEntity
import com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity
import com.verlintas.baic2.floatball.advisor.data.ProactivePolicyEntity
import com.verlintas.baic2.floatball.advisor.data.SelfPersonaEntity
import com.verlintas.baic2.floatball.auto.AutoSelectionStore
import com.verlintas.baic2.floatball.auto.ImChatNavigator
import com.verlintas.baic2.floatball.auto.InjectorSelector
import com.verlintas.baic2.floatball.contact.ContactRef
import com.verlintas.baic2.floatball.director.TopicEngine
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Debug-only 演示数据导入/重置入口（release 包不含本类）。
 *
 * `adb shell am broadcast -a com.verlintas.baic2.DEBUG_DEMO_SEED \
 *   -n com.verlintas.baic2/.floatball.demo.DemoSeedReceiver \
 *   --es path /sdcard/Android/data/com.verlintas.baic2/files/demo.json [--ez reset true]`
 *
 * 结果码：0=成功；2=演示数据已存在（未 reset）；1=失败（data 为原因）。
 * 只写 advisor.db 与好参谋自有偏好，绝不删除非 demo 行。
 * 另供 RPA 冒烟（`--es navPkg/--es navName`）：走 ImChatNavigator 直达聊天页，
 * 供自动化验证模糊匹配/最靠前会话选择，不写任何数据。
 */
@AndroidEntryPoint
class DemoSeedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var db: AdvisorDatabase

    @Inject
    lateinit var contacts: ContactRepository

    @Inject
    lateinit var schemas: PersonaSchemas

    @Inject
    lateinit var prefs: AdvisorPrefs

    @Inject
    lateinit var autoSelection: AutoSelectionStore

    @Inject
    lateinit var imChatNavigator: ImChatNavigator

    @Inject
    lateinit var advisor: ScreenAdvisor

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = runCatching { handle(context, intent) }
            val code = result.getOrNull() ?: 1
            val message = result.exceptionOrNull()?.message ?: result.getOrNull()?.toString().orEmpty()
            pending.setResultCode(code)
            pending.setResultData(message)
            pending.finish()
        }
    }

    private suspend fun handle(context: Context, intent: Intent): Int {
        // 联调参数钩子（2026-10-27 自动档复测）：`--es pace "1-3"`（派发前随机延迟
        // 区间，秒）、`--es executor a11y|shizuku|auto`、`--es work 60 --es rest 60`
        // （工作/停歇段，秒；rest=0 表示不停歇）。只写好参谋自有偏好，不碰 IM。
        val tuned = applyTuningPrefs(intent)
        // 自动档脚本化入口（2026-10-27 反复复测）：`--ez auto true` 走与主页
        // 「自动」键完全相同的 ACTION_AUTO_FROM_MAIN（自身窗口隐藏握手 +
        // AutoSelectionStore 选择集 + 单会话陪伴窗口），`--ez stopAuto true`
        // 收尾。可与上面的参数钩子同一条广播拼用（先调参再启动），用于无 UI
        // 交互的批量复测——耗时只用 `auto_timing:` 台账比对，不看人眼。
        val auto = intent.getBooleanExtra("auto", false)
        val stopAuto = intent.getBooleanExtra("stopAuto", false)
        if (auto || stopAuto) {
            val action = if (auto) {
                FloatBallService.ACTION_AUTO_FROM_MAIN
            } else {
                FloatBallService.ACTION_STOP_AUTO
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatBallService::class.java).setAction(action),
            )
            if (auto) {
                // 自身窗口隐藏握手（[AdvisorSelfWindowGate]）：主页入口靠
                // MainActivity.onStop 触发，脚本入口没有 Activity 生命周期，
                // 服务 1.5s 拿不到信号会直接放弃（真机实测：toast
                // `float_home_self_hide_timeout`，此后什么都不发生）。
                // 这里在等待窗内补两次「已隐藏」信号（幂等）：第一次大概率
                // 早于服务武装，第二次补刀；两者都在 SELF_HIDE_TIMEOUT_MS 内。
                val handler = Handler(Looper.getMainLooper())
                listOf(500L, 1_200L).forEach { at ->
                    handler.postDelayed({ AdvisorSelfWindowGate.markHidden() }, at)
                }
            }
            Log.i(TAG, "auto hook: $action")
            return 0
        }
        // 调试模拟来信（2026-10-27 自动档闭环联调）：
        // `--es say "对方说了什么" [--es sayContact 吴瑞曦topwayricky]`
        // 走 ScreenAdvisor.submitNotificationTurn 的正式后台回合管线（写 USER
        // 消息 → LLM 分析 → ASSISTANT 成稿 → turnCompletions），自动窗口控制器
        // 收到回合完成流后走 Gatekeeper/拟人节奏/派发。回合可能跑十几秒，超出
        // broadcast goAsync 时限，故 fire-and-forget；结果码 0 仅表示「已受理」，
        // 处理结果看 advisor.db 台账与截图。
        val say = intent.getStringExtra("say")
        if (!say.isNullOrBlank()) {
            val hint = intent.getStringExtra("sayContact").orEmpty()
            val alive = db.contactProfileDao().listAlive("com.tencent.mm")
            val profile = alive.firstOrNull {
                hint.isNotBlank() && it.displayName.contains(hint, ignoreCase = true)
            } ?: alive.firstOrNull {
                it.displayName.contains("topwayricky", ignoreCase = true)
            } ?: throw IllegalStateException("say_contact_not_found")
            val ref = ContactRef(
                contactId = profile.id,
                displayName = profile.displayName,
                aliases = emptyList(),
                goal = profile.goal,
                style = profile.style,
            )
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { advisor.submitNotificationTurn(say, ref) }
            }
            return 0
        }
        // RPA 冒烟入口（自动化验证模糊匹配）：`--es navPkg com.tencent.mm --es navName 吴瑞曦topwayricky`。
        // 走与画像页「关联会话」完全相同的 ImChatNavigator.openChat 链路，结果码
        // 0=已进入聊天页；失败抛异常以 data 回传 REASON_*（脚本可断言）。
        val navPkg = intent.getStringExtra("navPkg")
        if (!navPkg.isNullOrBlank()) {
            val navName = intent.getStringExtra("navName").orEmpty()
            val result = imChatNavigator.openChat(navPkg, navName)
            if (result is ImChatNavigator.NavResult.Fail) {
                throw IllegalStateException("nav_fail:${result.reason}")
            }
            return 0
        }
        val reset = intent.getBooleanExtra("reset", false)
        if (tuned.isNotEmpty() && !reset && intent.getStringExtra("path").isNullOrBlank()) {
            // 只带联调参数时到此收工，不再走种子导入分支（否则会误报 missing path）。
            return 0
        }
        if (reset) {
            resetDemo()
            return 0
        }
        // 只看「仍存活」的 demo 档案：reset 只软删档案、不删别名行，重灌必须能继续。
        val alive = db.contactAliasDao().listByAlias(DEMO_ALIAS)
            .map { it.contactId }
            .distinct()
            .any { contactId -> db.contactProfileDao().findById(contactId)?.deletedAt == null }
        if (alive) {
            return 2
        }
        val path = intent.getStringExtra("path")
        if (path.isNullOrBlank()) {
            throw IllegalArgumentException("missing --es path")
        }
        val file = if (path.startsWith("/")) File(path) else File(context.filesDir, path)
        val root = JSONObject(file.readText())
        // manifest.json 把客户端数据收在 client 节点；兼容直接放根节点的简化输入。
        val demo = root.optJSONObject("client") ?: root
        seedContacts(demo.optJSONArray("contacts"))
        seedPersonaFields(MY_PERSONA_CONTACT_ID, demo.optJSONObject("selfFields"))
        seedSelfPersona(demo.optJSONObject("selfPersona"))
        seedMaterials(demo.optJSONArray("materials"))
        seedProactive(demo.optJSONObject("proactive"))
        return 0
    }

    /**
     * 联调参数钩子实现：解析 `pace/executor/work/rest` 并写好参谋偏好，
     * 返回已应用项摘要（空 = 未带任何联调参数）。非法值静默忽略，不抛异常。
     */
    private fun applyTuningPrefs(intent: Intent): String {
        val applied = mutableListOf<String>()
        intent.getStringExtra("pace")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val parts = raw.split("-", "~", ",")
            val minSec = parts.getOrNull(0)?.trim()?.toLongOrNull()
            val maxSec = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: minSec
            if (minSec != null && maxSec != null && minSec >= 0 && maxSec >= 0) {
                prefs.setAutoPace(minSec * 1000L, maxSec * 1000L)
                applied += "pace=${minSec}-${maxSec}s"
            }
        }
        intent.getStringExtra("executor")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val channel = when (raw) {
                "a11y" -> InjectorSelector.CONFIG_A11Y
                "shizuku" -> InjectorSelector.CONFIG_SHIZUKU
                "auto" -> InjectorSelector.CONFIG_AUTO
                else -> null
            }
            if (channel != null) {
                prefs.setAutoExecutorChannel(channel)
                applied += "executor=$channel"
            }
        }
        val workSec = intent.getIntExtra("work", -1)
        if (workSec > 0) {
            val ms = workSec * 1000L
            // 档位外取值不落库：setWorkMs 对未知名目会回退 10 分钟，静默改参数更难排障。
            if (ms in AdvisorPrefs.WORK_REST_OPTIONS) {
                prefs.setWorkMs(ms)
                applied += "work=${workSec}s"
            } else {
                Log.w(TAG, "work=${workSec}s not in options, ignored")
            }
        }
        val restSec = intent.getIntExtra("rest", -1)
        if (restSec >= 0) {
            val ms = restSec * 1000L
            if (ms in AdvisorPrefs.WORK_REST_OPTIONS) {
                prefs.setRestMs(ms)
                applied += "rest=${restSec}s"
            } else {
                Log.w(TAG, "rest=${restSec}s not in options, ignored")
            }
        }
        if (applied.isNotEmpty()) {
            Log.i(TAG, "tuning applied: ${applied.joinToString(",")}")
        }
        return applied.joinToString(",")
    }

    private suspend fun seedContacts(array: org.json.JSONArray?) {
        if (array == null) return
        val autoTargets = mutableListOf<AutoSelectionStore.Target>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val imPackage = item.optString("imPackage", "com.tencent.mm")
            val title = item.optString("title").ifBlank { continue }
            val resolved = contacts.resolve(
                imPackage = imPackage,
                rawTitle = title,
                source = "user",
                createStub = true,
                bypassTombstone = true,
            )
            val profile = (resolved as? ResolveOutcome.Matched)?.profile ?: continue
            contacts.recordChannel(profile.id, imPackage, title)
            contacts.recordAliasHit(profile.id, DEMO_ALIAS, title, "user")
            item.optString("goal").takeIf { it.isNotBlank() }?.let { contacts.updateGoal(profile.id, it) }
            item.optString("stageIntent").takeIf { it.isNotBlank() }?.let {
                contacts.setStageIntent(profile.id, it)
            }
            item.optString("engagement").takeIf { it.isNotBlank() }?.let {
                contacts.setEngagement(profile.id, it)
            }
            item.optString("privacy").takeIf { it.isNotBlank() }?.let {
                contacts.setPrivacyPolicy(profile.id, it)
            }
            val identity = item.optString("identity").takeIf { it.isNotBlank() }
            val chatStyle = item.optString("chatStyle").takeIf { it.isNotBlank() }
            if (identity != null || chatStyle != null) {
                contacts.setStyleTags(profile.id, identity, chatStyle, null)
            }
            if (item.optBoolean("group", false)) {
                contacts.setGroup(profile.id, true)
            } else {
                contacts.setContactType(profile.id, "person")
            }
            // 结构化画像：与手动填写同口径（source=demo、confirmed=true），
            // LLM 萃取草稿不会覆盖；reset 按 source 精确清理。
            seedPersonaFields(profile.id, item.optJSONObject("fields"))
            if (item.optBoolean("autoTarget", false)) {
                prefs.addAutoWhitelistContact(profile.id)
                autoTargets += AutoSelectionStore.Target(
                    contactId = profile.id,
                    imPackage = imPackage,
                    channelName = title,
                    displayName = title,
                )
            }
        }
        if (autoTargets.isNotEmpty()) {
            // 种子环境把自动档总闸一并打开：真机复验从「子菜单/分屏/主页」进入时
            // 只验证入口与运行态，不被旧设备遗留的 auto_mode_enabled=false 干扰。
            prefs.setAutoModeEnabled(true)
            autoSelection.save(AutoSelectionStore.Selection(targets = autoTargets))
        }
    }

    /**
     * 结构化画像字段导入（`selfFields` / 联系人 `fields`）：
     * 文本按原样、tags 按 schema 编成 JSON 数组（码表值归一为 code），
     * 统一 source=demo、confirmed=true，reset 时只清 demo 行。
     */
    private suspend fun seedPersonaFields(contactId: Long, node: JSONObject?) {
        if (node == null || node.length() == 0) return
        val now = System.currentTimeMillis()
        val rows = mutableListOf<PersonaFieldEntity>()
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val raw = node.optString(key).trim()
            if (raw.isEmpty()) continue
            val def = schemas.get().def(key)
            val value = if (def?.type == PersonaFieldType.TAGS) {
                val tags = raw.split(",", "，", "、")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { PersonaValueCodes.normalizeExtracted(def, it) }
                    .distinct()
                if (tags.isEmpty()) continue
                PersonaValues.encodeTags(tags)
            } else {
                val normalized = def?.let { PersonaValueCodes.normalizeExtracted(it, raw) }
                normalized?.takeIf { it.isNotBlank() } ?: raw
            }
            rows += PersonaFieldEntity(
                contactId = contactId,
                field = key,
                value = value,
                source = DEMO_SOURCE,
                confirmed = true,
                evidence = DEMO_EVIDENCE,
                updatedAt = now,
            )
        }
        if (rows.isNotEmpty()) db.personaFieldDao().upsertAll(rows)
    }

    /**
     * 个人素材库导入（`materials`）：统一挂 [DEMO_TAG]，重灌先清同标签旧行，
     * 避免重复导入造成同一素材多份指纹。fingerprint 与 TopicEngine 同源。
     */
    private suspend fun seedMaterials(array: org.json.JSONArray?) {
        if (array == null) return
        val dao = db.personalMaterialDao()
        dao.deleteByTag(DEMO_TAG)
        val now = System.currentTimeMillis()
        for (index in 0 until array.length()) {
            val content = array.optJSONObject(index)
                ?.optString("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            dao.upsert(
                PersonalMaterialEntity(
                    content = content,
                    tag = DEMO_TAG,
                    fingerprint = TopicEngine.fingerprintOf(content),
                    createdAt = now,
                ),
            )
        }
    }

    private suspend fun seedSelfPersona(node: JSONObject?) {
        if (node == null) return
        val tag = node.optString("tag").ifBlank { return }
        val note = node.optString("note").takeIf { it.isNotBlank() }
        db.selfPersonaDao().upsert(
            SelfPersonaEntity(tag = tag, note = note, createdAt = System.currentTimeMillis()),
        )
    }

    private suspend fun seedProactive(node: JSONObject?) {
        if (node == null) return
        // 只在「存活档案」里找领导：旧 demo 数据软删后别名仍在，按 alias 查会把
        // 主动政策挂到已删除的旧档上（2026-10-26 真机复核命中）。
        val alive = db.contactProfileDao().listAlive("com.tencent.mm")
        val leader = alive.firstOrNull { it.displayName.contains("topwayricky", ignoreCase = true) }
            ?: alive.firstOrNull { it.displayName.contains("吴瑞曦") }
            ?: return
        db.proactivePolicyDao().upsert(
            ProactivePolicyEntity(
                contactId = leader.id,
                enabled = node.optBoolean("enabled", true),
                dailyCap = node.optInt("dailyCap", 3),
                minGapHours = node.optInt("minGapHours", 0),
                quietStart = node.optString("quietStart", "23:00"),
                quietEnd = node.optString("quietEnd", "07:00"),
                preferSmalltalk = node.optBoolean("preferSmalltalk", true),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun resetDemo() {
        val now = System.currentTimeMillis()
        val aliasRows = db.contactAliasDao().listByAlias(DEMO_ALIAS)
        aliasRows.map { it.contactId }.distinct().forEach { contactId ->
            db.contactProfileDao().setDeleted(contactId, now)
            // 清掉 demo 联系人名下的会话登记行：软删档案留下的旧 channel 会让
            // 下次导入出现「同一会话两条记录」的假象（显示名不同时尤其明显）。
            db.chatChannelDao().listForContact(contactId).forEach {
                db.chatChannelDao().delete(it.id)
            }
            // 主动策略行以 contactId 为主键、不随档案软删消失；不清会留下「墓碑档
            // 仍有主动政策」的脏行（2026-10-26 真机复核：旧 id=1 政策行残留）。
            db.proactivePolicyDao().delete(contactId)
            prefs.removeAutoWhitelistContact(contactId)
        }
        db.selfPersonaDao().listAll()
            .filter { it.tag == DEMO_SELF_TAG }
            .forEach { db.selfPersonaDao().deleteById(it.id) }
        // 双方人设/素材只清 demo 来源与 demo 标签，用户真实数据原样保留。
        db.personaFieldDao().deleteBySource(DEMO_SOURCE)
        db.personalMaterialDao().deleteByTag(DEMO_TAG)
        autoSelection.clear()
    }

    private companion object {
        const val TAG = "DemoSeed"
        const val DEMO_ALIAS = "demo:v1"
        const val DEMO_SELF_TAG = "demo-me"
        const val DEMO_SOURCE = "demo"
        const val DEMO_TAG = "demo:v1"
        const val DEMO_EVIDENCE = "demo seed"
    }
}
