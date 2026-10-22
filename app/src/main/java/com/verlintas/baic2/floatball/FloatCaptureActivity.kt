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

package com.verlintas.baic2.floatball

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.verlintas.baic2.floatball.auto.ScreenCapture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Transparent relay: MediaProjection consent must be started from an activity,
 * but the floating-ball flow lives in a service. Two channels share this relay:
 *
 * - 默认（上游通道）：服务在 [FloatCaptureBridge] 停好 consentIntent 与
 *   回调，本 Activity 启动系统授权框并原样回传结果后立即结束。
 * - [EXTRA_MP]（MPManager 常驻投影通道，方案 2026-10-22 模块 A）：本
 *   Activity 内执行 [ScreenCapture.ensureProjection] 编排——a11y 开自动点
 *   弹窗、Shizuku 活走 appops 静默直过、皆无则手动点——把最终布尔结果经
 *   桥回传。授权编排最长 60s（手动路径），期间保持本 Activity 存活。
 */
@AndroidEntryPoint
class FloatCaptureActivity : ComponentActivity() {

    @Inject
    lateinit var screenCapture: ScreenCapture

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            deliver(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mp = intent?.getBooleanExtra(EXTRA_MP, false) ?: false
        if (mp) {
            if (FloatCaptureBridge.mpConsent == null) finish()
            return
        }
        if (FloatCaptureBridge.onConsentResult == null || FloatCaptureBridge.consentIntent == null) {
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        if (intent?.getBooleanExtra(EXTRA_MP, false) == true) {
            runMpConsent()
            return
        }
        val intent: Intent = FloatCaptureBridge.consentIntent ?: run {
            finish()
            return
        }
        if (!isFinishing) launcher.launch(intent)
    }

    /** MPManager 通道授权编排；结果回传一次（防配置变更重放）。 */
    private fun runMpConsent() {
        if (isFinishing) return
        lifecycleScope.launch {
            val granted = runCatching { screenCapture.ensureProjection() }.getOrDefault(false)
            FloatCaptureBridge.mpConsent?.invoke(granted)
            FloatCaptureBridge.clear()
            finish()
        }
    }

    private fun deliver(resultCode: Int, data: Intent?) {
        FloatCaptureBridge.onConsentResult?.invoke(resultCode, data)
        FloatCaptureBridge.clear()
        finish()
    }

    companion object {
        /** 走 MPManager 常驻投影授权编排（默认走上游通道中转）。 */
        const val EXTRA_MP = "extra_use_mp"
    }
}
