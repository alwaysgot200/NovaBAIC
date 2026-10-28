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

package com.verlintas.baic2.floatball.rpa

import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.store.RpaScriptStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RpaScriptStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): RpaScriptStore = RpaScriptStore(temp.root)

    private fun script(name: String, id: String = RpaIds.newId("script")): RpaScript =
        RpaScript(
            id = id,
            name = name,
            mode = RpaMode.A11Y,
            steps = listOf(
                RpaStep(id = RpaIds.newId("step"), action = RpaAction.COMMENT, note = "first"),
                RpaStep(id = RpaIds.newId("step"), action = RpaAction.COMMENT, note = "second"),
            ),
        )

    @Test
    fun `save then load round trips and stamps timestamps`() {
        val store = store()
        val saved = store.save(script("demo")).getOrThrow()

        assertTrue(saved.createdAt > 0)
        assertTrue(saved.updatedAt > 0)
        assertEquals(saved.id, store.load(saved.id)?.id)
        assertEquals("demo", store.load(saved.id)?.name)
        assertEquals(2, store.load(saved.id)?.steps?.size)
    }

    @Test
    fun `list report keeps readable scripts and names broken files`() {
        val store = store()
        val first = store.save(script("first")).getOrThrow()
        store.save(script("second"))
        File(store.scriptsDir, "broken.json").writeText("{ not json")

        val report = store.listReport()

        assertEquals(2, report.scripts.size)
        assertTrue(report.scripts.any { it.id == first.id })
        assertEquals(listOf("broken.json"), report.failed)
        assertEquals(2, store.list().size)
    }

    @Test
    fun `ids are whitelisted so path traversal stays inside scripts dir`() {
        val store = store()
        val saved = store.save(script("escape", id = "../../evil")).getOrThrow()
        val scriptsRoot = store.scriptsDir.canonicalFile

        val file = File(scriptsRoot, "${saved.id}.json")
        assertFalse(file.exists())
        assertTrue(store.scriptsDir.listFiles().orEmpty().none { it.name == "evil.json" })
        assertFalse(File(temp.root, "evil.json").exists())
        assertFalse(File(temp.root.parentFile, "evil.json").exists())
        // 同一个白名单函数读回，说明写入点在 scripts 目录内。
        assertEquals("escape", store.load("../../evil")?.name)
    }

    @Test
    fun `duplicate copies templates and regenerates step ids`() {
        val store = store()
        val original = store.save(script("original")).getOrThrow()
        val templateId = "tpl_test"
        store.saveTemplate(original.id, templateId, byteArrayOf(1, 2, 3)).getOrThrow()

        val copy = store.duplicate(original).getOrThrow()

        assertNotEquals(original.id, copy.id)
        assertEquals("original copy", copy.name)
        assertTrue(store.load(copy.id) != null)
        assertTrue(store.templateExists(copy.id, templateId))
        assertEquals(
            original.steps.map { it.id }.toSet().intersect(copy.steps.map { it.id }.toSet()),
            emptySet<String>(),
        )
        // 原脚本模板不受影响。
        assertTrue(store.templateExists(original.id, templateId))
    }

    @Test
    fun `delete removes script document and its template directory`() {
        val store = store()
        val saved = store.save(script("to-delete")).getOrThrow()
        store.saveTemplate(saved.id, "tpl_x", byteArrayOf(9)).getOrThrow()

        assertTrue(store.delete(saved.id))
        assertFalse(store.templateExists(saved.id, "tpl_x"))
        assertTrue(store.load(saved.id) == null)
    }

    @Test
    fun `save overwrites atomically and leaves no temp file`() {
        val store = store()
        val saved = store.save(script("v1")).getOrThrow()
        store.save(saved.copy(name = "v2")).getOrThrow()

        assertEquals("v2", store.load(saved.id)?.name)
        val leftovers = store.scriptsDir.listFiles().orEmpty().filter { it.extension == "tmp" }
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun `template save rejects empty payload`() {
        val store = store()
        val saved = store.save(script("templates")).getOrThrow()

        val result = store.saveTemplate(saved.id, "tpl_empty", ByteArray(0))

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertFalse(store.templateExists(saved.id, "tpl_empty"))
    }
}
