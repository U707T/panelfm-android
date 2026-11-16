package com.u707t.panelfm.open

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** `cache/incoming` 的清理策略：年龄、残片、容量三条规则各自可验证。 */
class IncomingCacheTest {

    private fun dir(): File = Files.createTempDirectory("incoming-cache").toFile()

    private fun file(dir: File, name: String, size: Int, ageMs: Long, now: Long): File =
        File(dir, name).apply {
            writeBytes(ByteArray(size))
            setLastModified(now - ageMs)
        }

    @Test
    fun `超过 7 天的文件被删，近期文件保留`() {
        val d = dir()
        val now = System.currentTimeMillis()
        val old = file(d, "old.txt", 10, 8L * 24 * 3600 * 1000, now)
        val recent = file(d, "recent.txt", 10, 1000, now)

        IncomingCache.cleanup(d, now = now)

        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `超过 1 小时的 part 残片被删，刚写的 part 保留`() {
        val d = dir()
        val now = System.currentTimeMillis()
        val stale = file(d, "a.txt.part", 10, 2L * 3600 * 1000, now)
        val fresh = file(d, "b.txt.part", 10, 1000, now)

        IncomingCache.cleanup(d, now = now)

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `容量超限时保留最新的，删最旧的`() {
        val d = dir()
        val now = System.currentTimeMillis()
        val newest = file(d, "newest.bin", 300, 1000, now)
        val middle = file(d, "middle.bin", 300, 2000, now)
        val oldest = file(d, "oldest.bin", 300, 3000, now)

        val deleted = IncomingCache.cleanup(d, now = now, maxBytes = 500)

        assertEquals(2, deleted)
        assertTrue(newest.exists())
        assertFalse(middle.exists())
        assertFalse(oldest.exists())
    }

    @Test
    fun `容量未超限时一个都不删`() {
        val d = dir()
        val now = System.currentTimeMillis()
        val a = file(d, "a.bin", 100, 1000, now)
        val b = file(d, "b.bin", 100, 2000, now)

        assertEquals(0, IncomingCache.cleanup(d, now = now, maxBytes = 500))
        assertTrue(a.exists())
        assertTrue(b.exists())
    }

    @Test
    fun `单个文件超过容量上限时保留最新一份`() {
        val d = dir()
        val now = System.currentTimeMillis()
        val newest = file(d, "newest.bin", 600, 1000, now)
        val older = file(d, "older.bin", 100, 2000, now)

        val deleted = IncomingCache.cleanup(d, now = now, maxBytes = 500)

        // 最新文件即使自身超过 512 MB 也不能删；只删更旧的。
        assertTrue("刚收到的文件必须保留", newest.exists())
        assertFalse(older.exists())
        assertEquals(1, deleted)
    }
}
