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

/**
 * Transparent relay: MediaProjection consent must be started from an activity,
 * but the floating-ball flow lives in a service. The service parks a callback
 * in [FloatCaptureBridge], this activity launches the consent dialog and
 * forwards the raw result back, then finishes immediately.
 */
class FloatCaptureActivity : ComponentActivity() {

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            deliver(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (FloatCaptureBridge.onConsentResult == null || FloatCaptureBridge.consentIntent == null) {
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        val intent: Intent = FloatCaptureBridge.consentIntent ?: run {
            finish()
            return
        }
        if (!isFinishing) launcher.launch(intent)
    }

    private fun deliver(resultCode: Int, data: Intent?) {
        FloatCaptureBridge.onConsentResult?.invoke(resultCode, data)
        FloatCaptureBridge.clear()
        finish()
    }
}
