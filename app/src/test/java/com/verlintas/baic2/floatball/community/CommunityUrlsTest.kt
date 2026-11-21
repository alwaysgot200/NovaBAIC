/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.community

import org.junit.Assert.assertEquals
import org.junit.Test

/** 社区 URL 拼装（多产品方案 §4.8）：产品锁定、`/admin` 前缀、尾斜杠归一。 */
class CommunityUrlsTest {

    @Test
    fun marketUrlLocksHaocanmouProduct() {
        assertEquals(
            "http://192.168.1.27/admin/market/shop?product=haocanmou&source=client",
            CommunityUrls.market("http://192.168.1.27"),
        )
    }

    @Test
    fun forumUrlLocksHaocanmouProduct() {
        assertEquals(
            "https://autojs6.80fafa.com/admin/market/forum?product=haocanmou&source=client",
            CommunityUrls.forum("https://autojs6.80fafa.com"),
        )
    }

    @Test
    fun trailingSlashIsNormalized() {
        assertEquals(
            "http://192.168.1.27/admin/market/shop?product=haocanmou&source=client",
            CommunityUrls.market("http://192.168.1.27/"),
        )
    }

    @Test
    fun ssoTargetMatchesPage() {
        assertEquals(
            "/admin/market/forum?product=haocanmou&source=client",
            CommunityUrls.target(isForum = true),
        )
        assertEquals(
            "/admin/market/shop?product=haocanmou&source=client",
            CommunityUrls.target(isForum = false),
        )
    }

    @Test
    fun ssoUrlKeepsTicketInFragment() {
        assertEquals(
            "http://192.168.1.27/admin/sso#ticket=0123456789abcdef0123456789abcdef",
            CommunityUrls.sso("http://192.168.1.27", "0123456789abcdef0123456789abcdef"),
        )
    }
}
