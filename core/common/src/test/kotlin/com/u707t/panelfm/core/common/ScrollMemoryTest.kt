package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「进子目录再返回，列表停在原地」的记忆表回归。
 */
class ScrollMemoryTest {

    @Test
    fun `记下并取回滚动位置`() {
        val mem = ScrollMemory()
        mem.remember("local://emulated/DCIM", index = 12, offset = 34)
        val got = mem.recall("local://emulated/DCIM")
        assertEquals(12, got?.index)
        assertEquals(34, got?.offset)
    }

    @Test
    fun `没记过的路径返回 null`() {
        assertNull(ScrollMemory().recall("local://emulated/Download"))
    }

    @Test
    fun `停在顶部不占名额`() {
        val mem = ScrollMemory()
        mem.remember("a", index = 0, offset = 0)
        assertEquals(0, mem.size)
        assertNull(mem.recall("a"))
    }

    @Test
    fun `回到顶部会清掉旧位置`() {
        val mem = ScrollMemory()
        mem.remember("a", index = 8, offset = 0)
        assertEquals(8, mem.recall("a")?.index)
        // 用户滚回顶部 → 下次进来就该在顶部
        mem.remember("a", index = 0, offset = 0)
        assertNull(mem.recall("a"))
    }

    @Test
    fun `超过上限按最近使用淘汰`() {
        val mem = ScrollMemory(maxEntries = 3)
        mem.remember("a", 1, 0)
        mem.remember("b", 2, 0)
        mem.remember("c", 3, 0)
        // 重新触碰 a → a 变成最近使用
        mem.remember("a", 1, 0)
        mem.remember("d", 4, 0)
        assertEquals(3, mem.size)
        assertEquals(1, mem.recall("a")?.index)
        assertEquals(4, mem.recall("d")?.index)
        // b 是最久未用的，被淘汰
        assertNull(mem.recall("b"))
    }

    @Test
    fun `forget 与 clear 生效`() {
        val mem = ScrollMemory()
        mem.remember("a", 5, 0)
        mem.forget("a")
        assertNull(mem.recall("a"))
        mem.remember("b", 6, 0)
        mem.clear()
        assertEquals(0, mem.size)
    }

    @Test
    fun `负下标被夹到 0`() {
        val mem = ScrollMemory()
        mem.remember("a", -3, 0)
        // index <= 0 且 offset <= 0 → 视为顶部，不记
        assertEquals(0, mem.size)
        mem.remember("a", -3, 40)
        assertEquals(0, mem.recall("a")?.index)
        assertEquals(40, mem.recall("a")?.offset)
    }

    @Test
    fun `含 返回上级 行的下标换算`() {
        // 有 .. 行：items 的 0 号 → 列表的 1 号
        assertEquals(1, ScrollMemory.toListIndex(0, hasParentRow = true))
        assertEquals(13, ScrollMemory.toListIndex(12, hasParentRow = true))
        // 没有 .. 行（根目录 / 压缩包根）：不偏移
        assertEquals(0, ScrollMemory.toListIndex(0, hasParentRow = false))
        assertEquals(12, ScrollMemory.toListIndex(12, hasParentRow = false))
        // 反向换算：列表 0 号 = .. 行 → -1
        assertEquals(-1, ScrollMemory.toItemIndex(0, hasParentRow = true))
        assertEquals(11, ScrollMemory.toItemIndex(12, hasParentRow = true))
        assertEquals(12, ScrollMemory.toItemIndex(12, hasParentRow = false))
        // 往返一致
        for (i in 0..30) {
            assertEquals(i, ScrollMemory.toItemIndex(ScrollMemory.toListIndex(i, true), true))
        }
    }
}
