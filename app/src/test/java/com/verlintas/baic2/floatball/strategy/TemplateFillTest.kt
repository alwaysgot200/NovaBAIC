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

package com.verlintas.baic2.floatball.strategy

import com.verlintas.baic2.floatball.advisor.strategy.TemplateFill
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateFillTest {

    @Test
    fun nameAndAspiration_filled() {
        val out = TemplateFill.render(
            "和{name}保持长期关系，同时我成为{aspiration}的人",
            name = "李总",
            aspiration = "沉稳、可靠",
        )
        assertEquals("和李总保持长期关系，同时我成为沉稳、可靠的人", out)
    }

    @Test
    fun emptyAspiration_fallsBackToIdealSelf() {
        val out = TemplateFill.render("成为{aspiration}的专业者", name = "李总", aspiration = "")
        assertEquals("成为理想中的自己的专业者", out)
    }

    @Test
    fun unknownPlaceholders_becomeBlanksAndLiteralsKept() {
        val out = TemplateFill.render(
            "在{deadline}前处理{objection}，对{name}保持耐心",
            name = "",
            aspiration = "稳",
        )
        assertEquals(
            "在${TemplateFill.BLANK}前处理${TemplateFill.BLANK}，对${TemplateFill.BLANK}保持耐心",
            out,
        )
    }
}
