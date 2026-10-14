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

package com.verlintas.baic2.floatball.advisor

import androidx.room.withTransaction
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.PlaybookEntity
import com.verlintas.baic2.floatball.advisor.data.PlaybookFtsEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FTS4 写入/检索的中文切分工具：CJK 字符逐字插空格、拉丁词小写保留，
 * 使 FTS 默认分词器对中文按字建索引；查询侧用二元字邻接短语召回。
 */
object PlaybookIndex {

    private val cjkRange = Character.UnicodeScript.HAN

    fun tokenize(text: String): String = buildString(text.length * 2) {
        text.forEach { ch ->
            when {
                Character.UnicodeScript.of(ch.code) == cjkRange -> append(' ').append(ch).append(' ')
                ch.isLetterOrDigit() -> append(ch.lowercaseChar())
                else -> append(' ')
            }
        }
    }.trim().replace(Regex("\\s+"), " ")

    /**
     * 构造 FTS MATCH 表达式：每个汉字连续段产出二元邻接短语（短于 2 字按
     * 单字），拉丁/数字词加前缀通配；各子句 OR。例：嫌贵VIP → "嫌 贵" OR vip*
     */
    fun matchExpression(query: String): String {
        val clauses = ArrayList<String>()
        val latin = Regex("[A-Za-z0-9]{2,}")
        latin.findAll(query).forEach { clauses += "${it.value.lowercase()}*" }
        query.split(Regex("[^\\p{IsHan}A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
            .forEach { segment ->
                val han = segment.filter { Character.UnicodeScript.of(it.code) == cjkRange }
                if (han.isNotEmpty()) {
                    if (han.length == 1) {
                        clauses += han
                    } else {
                        for (i in 0 until han.length - 1) {
                            clauses += "\"${han[i]} ${han[i + 1]}\""
                        }
                    }
                }
            }
        return clauses.joinToString(" OR ")
    }
}

/**
 * 话术检索：FTS4 召回为主、LIKE 关键字打分为辅，category 过滤 + priority
 * 排序（方案 §6.2）。结构化小知识库，本地毫秒级，无需 embedding。
 */
@Singleton
class PlaybookRetriever @Inject constructor(
    private val db: AdvisorDatabase,
    private val seeder: AdvisorAssetSeeder,
) {
    /**
     * @param query 本轮对方最新发言（+目标关键词）。
     * @param categories 限定分类（null = 全分类）。
     * @param limit 返回条数（提示词默认 Top-3）。
     */
    suspend fun retrieve(
        query: String,
        categories: List<String>? = null,
        limit: Int = 3,
    ): List<PlaybookEntity> {
        seeder.ensureSeed()
        val terms = queryTerms(query)
        if (terms.isEmpty()) return emptyList()

        val pool = if (categories != null) {
            categories.flatMap { db.playbookDao().listByCategory(it) }
        } else {
            db.playbookDao().listAll()
        }
        if (pool.isEmpty()) return emptyList()

        val hits = LinkedHashSet<Long>()
        // 1) FTS 召回，命中行带基础分。
        val match = PlaybookIndex.matchExpression(query)
        if (match.isNotBlank()) {
            runCatching { db.playbookDao().ftsRowIds(match, limit * 4) }.getOrDefault(emptyList())
                .forEach { rowId -> hits += rowId }
        }
        // 2) LIKE 兜底 + 关键字位置打分（场景名权重最高，标签次之，正文保底）。
        val scores = HashMap<Long, Int>()
        pool.forEach { p ->
            val keywordScore = scorePlaybook(p, terms)
            if (keywordScore > 0) hits += p.id
            scores[p.id] = keywordScore + if (p.id in hits && keywordScore == 0) FTS_HIT_BASE else 0
        }
        return pool.asSequence()
            .filter { it.id in hits }
            .filter { categories == null || it.category in categories }
            .sortedWith(
                compareByDescending<PlaybookEntity> { scores[it.id] ?: 0 }
                    .thenByDescending { it.priority }
                    .thenBy { it.id },
            )
            .take(limit)
            .toList()
    }

    // -------------------------------------------------- 管理 UI（P5）

    /** 全部话术（内置 + 用户自定义），priority 倒序；首次调用确保内置已播种。 */
    suspend fun all(): List<PlaybookEntity> {
        seeder.ensureSeed()
        return db.playbookDao().listAll()
    }

    /** 现有分类（内置 4 类 + 用户新填的分类），供新增话术下拉/分组用。 */
    suspend fun categories(): List<String> = all().map { it.category }.distinct()

    /** 新增用户自定义话术：实体与 FTS 影子行同事务写入，立即可检索。 */
    suspend fun addUserPlaybook(
        category: String,
        scenario: String,
        tags: String,
        content: String,
    ): Long = db.withTransaction {
        val id = db.playbookDao().insert(
            PlaybookEntity(
                category = category.trim(),
                scenario = scenario.trim(),
                tags = tags.trim(),
                content = content.trim(),
                priority = USER_PRIORITY,
                source = SOURCE_USER,
            ),
        )
        db.playbookDao().insertFts(
            PlaybookFtsEntity(
                rowid = id,
                scenario = PlaybookIndex.tokenize(scenario),
                tags = PlaybookIndex.tokenize(tags),
                content = PlaybookIndex.tokenize(content),
            ),
        )
        id
    }

    /** 删除用户自定义话术（内置行受 DAO 条件保护，删不掉）；FTS 行同事务清理。 */
    suspend fun deleteUserPlaybook(id: Long) = db.withTransaction {
        db.playbookDao().deleteFts(id)
        db.playbookDao().deleteUser(id)
    }

    private fun scorePlaybook(p: PlaybookEntity, terms: List<String>): Int {
        var score = 0
        val tags = p.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        terms.forEach { term ->
            if (p.scenario.contains(term)) score += 100
            if (tags.any { it == term }) score += 60
            if (tags.any { it.contains(term) || term.contains(it) && it.length >= 2 }) score += 20
            if (p.content.contains(term)) score += 10
        }
        return score
    }

    /** 抽取检索词：2 字以上的汉字段与拉丁词，去重，最多 8 个。 */
    private fun queryTerms(query: String): List<String> {
        val terms = LinkedHashSet<String>()
        Regex("[A-Za-z0-9]{2,}").findAll(query).forEach { terms += it.value.lowercase() }
        query.split(Regex("[^\\p{IsHan}A-Za-z0-9]+"))
            .asSequence()
            .map { seg -> seg.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
            .filter { it.length >= 2 }
            .forEach { seg ->
                terms += seg
                if (seg.length > 2) {
                    for (i in 0 until seg.length - 1) terms += seg.substring(i, i + 2)
                }
            }
        return terms.take(8)
    }

    private companion object {
        const val FTS_HIT_BASE = 5
        const val USER_PRIORITY = 5
        const val SOURCE_USER = "user"
    }
}
