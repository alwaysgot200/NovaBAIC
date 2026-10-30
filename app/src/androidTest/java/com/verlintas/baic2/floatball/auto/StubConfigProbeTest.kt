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

package com.verlintas.baic2.floatball.auto

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.util.Xml
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 诊断探针（临时）：定位「白名单桩 a11y 配置在 dumpsys 里解析为空」的断点。
 * 逐层打印：metaData 键值类型 → xmlResId → XML token/属性 ID → 模拟 AMS 读法。
 * 对照组为好参谋服务（同机解析正常）。
 */
@RunWith(AndroidJUnit4::class)
class StubConfigProbeTest {

    @Test
    fun probeMetaAndXml() {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        probe(pm, "STUB", "org.autojs.autojs",
            "com.stardust.autojs.core.accessibility.AccessibilityService")
        probe(pm, "GOOD", "com.verlintas.baic2",
            "com.verlintas.baic2.device.impl.a11y.Baic2AccessibilityService")
    }

    /** 「微信视角」：FEEDBACK_SPOKEN 过滤后的启用服务列表逐字段打印。 */
    @Test
    fun probeEnabledList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val am = context.getSystemService(AccessibilityManager::class.java)
        println("[LIST] touchExploration=${am?.isTouchExplorationEnabled}")
        val list = am?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_SPOKEN).orEmpty()
        println("[LIST] size=${list.size}")
        for (info in list) {
            println("[LIST] id=${info.id}")
            println("[LIST]   feedbackType=0x${Integer.toHexString(info.feedbackType)}" +
                " capabilities=${info.capabilities}" +
                " isAccessibilityTool=${info.isAccessibilityTool}" +
                " settingsActivity=${info.settingsActivityName}" +
                " component=${info.resolveInfo?.serviceInfo?.packageName}/${info.resolveInfo?.serviceInfo?.name}")
        }
    }

    private fun probe(pm: PackageManager, tag: String, pkg: String, cls: String) {
        val si = try {
            pm.getServiceInfo(ComponentName(pkg, cls), PackageManager.GET_META_DATA)
        } catch (t: Throwable) {
            println("[$tag] getServiceInfo ERROR $t")
            return
        }
        val md = si.metaData
        if (md == null) {
            println("[$tag] metaData=null")
            return
        }
        for (k in md.keySet()) {
            val v = md.get(k)
            println("[$tag] meta key=$k class=${v?.javaClass?.name} val=$v")
        }
        val xmlId = md.getInt("android.accessibility_service", 0)
        println("[$tag] xmlResId=0x${Integer.toHexString(xmlId)}")
        if (xmlId == 0) return
        try {
            val res = pm.getResourcesForApplication(si.applicationInfo)
            val parser: XmlResourceParser = res.getXml(xmlId)
            val attrs = Xml.asAttributeSet(parser)
            var type = parser.next()
            while (type != XmlResourceParser.END_DOCUMENT) {
                if (type == XmlResourceParser.START_TAG) {
                    println("[$tag] START_TAG ${parser.name}")
                    for (i in 0 until attrs.attributeCount) {
                        println("[$tag]   attr nameRes=0x${Integer.toHexString(attrs.getAttributeNameResource(i))}" +
                            " name=${attrs.getAttributeName(i)} val=${attrs.getAttributeValue(i)}")
                    }
                    // 模拟 AMS/AccessibilityServiceInfo 读法（framework styleable 字面 ID：
                    // eventTypes=0x01010380 feedbackType=0x01010382 notificationTimeout=0x01010383）
                    val ta = res.obtainAttributes(attrs,
                        intArrayOf(0x01010380, 0x01010382, 0x01010383))
                    println("[$tag]   typed eventTypes=0x${Integer.toHexString(ta.getInt(0, 0))}" +
                        " feedbackType=0x${Integer.toHexString(ta.getInt(1, 0))}" +
                        " timeout=${ta.getInt(2, 0)}")
                    ta.recycle()
                }
                type = parser.next()
            }
            parser.close()
        } catch (t: Throwable) {
            println("[$tag] XML parse ERROR $t")
        }
    }
}
