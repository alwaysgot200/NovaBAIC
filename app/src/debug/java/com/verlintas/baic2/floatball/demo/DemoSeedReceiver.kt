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
import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.advisor.ResolveOutcome
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.ProactivePolicyEntity
import com.verlintas.baic2.floatball.advisor.data.SelfPersonaEntity
import com.verlintas.baic2.floatball.auto.AutoSelectionStore
import com.verlintas.baic2.floatball.auto.ImChatNavigator
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
    lateinit var prefs: AdvisorPrefs

    @Inject
    lateinit var autoSelection: AutoSelectionStore

    @Inject
    lateinit var imChatNavigator: ImChatNavigator

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
        seedSelfPersona(demo.optJSONObject("selfPersona"))
        seedProactive(demo.optJSONObject("proactive"))
        return 0
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
            autoSelection.save(AutoSelectionStore.Selection(targets = autoTargets))
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
        autoSelection.clear()
    }

    private companion object {
        const val DEMO_ALIAS = "demo:v1"
        const val DEMO_SELF_TAG = "demo-me"
    }
}
