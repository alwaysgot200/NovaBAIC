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

package com.verlintas.baic2.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AttachmentKind {
    IMAGE,
    TEXT,
}

/**
 * A user attachment.
 *
 * Images live as files ([localPath]); [base64] is filled ephemerally when a
 * request is built and never persisted. Text files keep their content inline
 * ([text]), bounded at import time.
 */
@Serializable
data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val mimeType: String,
    val fileName: String? = null,
    val localPath: String? = null,
    val text: String? = null,
    val base64: String? = null,
    val sizeBytes: Long = 0L,
)
