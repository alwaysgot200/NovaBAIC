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

/**
 * 好参谋社区入口 URL（多产品方案 §4.8）。
 *
 * SPA 页面在站点 `/admin` 下的市场/论坛路由，客户端固定带
 * `product=haocanmou&source=client`：手机端锁定本产品且隐藏 Web 产品切换器。
 * 站点根由 `AccountApi.SERVER_ORIGIN` 构建期注入，本对象只做纯字符串拼装，
 * 便于单测。
 */
internal object CommunityUrls {

    /** 客户端产品上下文（产品锁定 + 来源标记）。 */
    const val PRODUCT_QUERY = "product=haocanmou&source=client"

    fun market(origin: String): String = build(origin, "shop")

    fun forum(origin: String): String = build(origin, "forum")

    /** SSO ticket 的站内跳转目标（不含站点根）。 */
    fun target(isForum: Boolean): String =
        "/admin/market/${if (isForum) "forum" else "shop"}?$PRODUCT_QUERY"

    /** ticket 放 URL fragment（不进 nginx 访问日志），Web 兑换后落到 target。 */
    fun sso(origin: String, ticket: String): String =
        "${origin.trimEnd('/')}/admin/sso#ticket=${encode(ticket)}"

    private fun build(origin: String, page: String): String =
        "${origin.trimEnd('/')}/admin/market/$page?$PRODUCT_QUERY"

    /** fragment 值百分号编码；ticket 正常为十六进制，异常字符也不破坏 URL。 */
    private fun encode(raw: String): String =
        java.net.URLEncoder.encode(raw, "UTF-8").replace("+", "%20")
}
