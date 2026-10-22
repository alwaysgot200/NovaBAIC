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

/**
 * 把授权结果从 [FloatCaptureActivity] 回传给服务的中转桥。
 *
 * 两通道共用一个中转 Activity：
 * - 上游通道：consentIntent + onConsentResult（原始 resultCode/data）。
 * - MPManager 常驻投影通道（方案 2026-10-22 模块 A）：mpConsent 回传
 *   [com.verlintas.baic2.floatball.auto.ScreenCapture.ensureProjection] 的
 *   最终布尔结果，授权弹窗由 Activity 内 MPManager 启动器发起。
 */
object FloatCaptureBridge {
    var consentIntent: Intent? = null
    var onConsentResult: ((resultCode: Int, data: Intent?) -> Unit)? = null
    var mpConsent: ((granted: Boolean) -> Unit)? = null

    fun clear() {
        consentIntent = null
        onConsentResult = null
        mpConsent = null
    }
}
