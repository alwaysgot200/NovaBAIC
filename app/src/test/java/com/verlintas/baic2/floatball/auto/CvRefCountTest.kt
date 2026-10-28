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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板 native 资源引用计数：缓存淘汰/失效与匹配并发时，
 * 绝不能提前释放仍在使用的 Mat。
 */
class CvRefCountTest {

    @Test
    fun `cache reference keeps template alive until matches finish`() {
        val released = AtomicInteger()
        val refs = CvRefCount { released.incrementAndGet() }

        // 缓存持有一份；匹配线程再持一份。
        assertTrue(refs.tryAcquire())
        assertEquals(2, refs.count)

        // 缓存淘汰：只减缓存那一份，模板仍活着。
        refs.release()
        assertEquals(1, refs.count)
        assertEquals(0, released.get())

        // 匹配结束：归零时才真正释放。
        refs.release()
        assertEquals(0, refs.count)
        assertEquals(1, released.get())
    }

    @Test
    fun `acquire after release is rejected and duplicate release is idempotent`() {
        val released = AtomicInteger()
        val refs = CvRefCount { released.incrementAndGet() }

        refs.release()
        assertEquals(1, released.get())
        assertFalse(refs.tryAcquire())

        // 重复 release 不能把计数压成负数，也不能再次触发 native 释放。
        refs.release()
        refs.release()
        assertEquals(1, released.get())
        assertEquals(0, refs.count)
    }

    @Test
    fun `concurrent acquire and release only frees once`() {
        val released = AtomicInteger()
        val refs = CvRefCount { released.incrementAndGet() }
        val threads = 8
        val perThread = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    repeat(perThread) {
                        if (refs.tryAcquire()) refs.release()
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        // 缓存自己那一份仍在，所有临时引用都已归还。
        assertEquals(1, refs.count)
        assertEquals(0, released.get())
        refs.release()
        assertEquals(1, released.get())
    }
}
