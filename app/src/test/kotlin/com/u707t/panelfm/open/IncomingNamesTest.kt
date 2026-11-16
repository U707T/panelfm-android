package com.u707t.panelfm.open

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 外部 `DISPLAY_NAME` 不可信：这里锁住「不逃出缓存目录 / 保留扩展名 / 不覆盖同名」三条底线。
 */
class IncomingNamesTest {

    @Test
    fun `路径分隔符只保留最后一段`() {
        assertEquals("evil.txt", sanitizeIncomingName("../evil.txt"))
        assertEquals("evil.txt", sanitizeIncomingName("..\\evil.txt"))
        assertEquals("a.txt", sanitizeIncomingName("/tmp/a.txt"))
    }

    @Test
    fun `Windows 非法字符被替换`() {
        assertEquals("a_b_c_.txt", sanitizeIncomingName("a:b?c*.txt"))
    }

    @Test
    fun `纯点号名字回退到时间戳`() {
        val name = sanitizeIncomingName("..")
        assertTrue("必须以 incoming- 开头：$name", name.startsWith("incoming-"))
    }

    @Test
    fun `超长名字截断但保留扩展名`() {
        val name = sanitizeIncomingName("a".repeat(200) + ".pdf")
        assertTrue("长度必须受限：${name.length}", name.length <= 120)
        assertTrue("扩展名必须保留：$name", name.endsWith(".pdf"))
    }

    @Test
    fun `同名文件追加序号而不覆盖`() {
        val dir = Files.createTempDirectory("incoming").toFile()
        File(dir, "a.txt").writeText("old")

        val first = uniqueIncomingTarget(dir, "a.txt")
        assertEquals("a (1).txt", first.name)
        first.writeText("new")

        val second = uniqueIncomingTarget(dir, "a.txt")
        assertEquals("a (2).txt", second.name)
    }

    @Test
    fun `无扩展名文件也追加序号`() {
        val dir = Files.createTempDirectory("incoming").toFile()
        File(dir, "README").writeText("old")

        assertEquals("README (1)", uniqueIncomingTarget(dir, "README").name)
    }
}
