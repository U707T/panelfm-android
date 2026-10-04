package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批量重命名：MT 表达式 + 查找/替换（0x7f0c0099） */
class BatchRenameTest {

    private fun item(name: String, modified: Long = 1_700_000_000_000L) = FileMetadata(
        uri = VfsUri.of("local", "emulated", "/tmp/$name"),
        name = name,
        isDirectory = false,
        size = 10,
        lastModified = modified,
    )

    @Test
    fun `表达式 P 与 S`() {
        assertEquals("photo", BatchRename.newName("{P}", item("photo.jpg"), 0))
        assertEquals(".jpg", BatchRename.newName("{S}", item("photo.jpg"), 0))
        assertEquals("photo.jpg", BatchRename.newName("{P}{S}", item("photo.jpg"), 0))
    }

    @Test
    fun `序号 N 不补零`() {
        assertEquals("a1", BatchRename.newName("a{1}", item("x.txt"), 0))
        assertEquals("a3", BatchRename.newName("a{1}", item("x.txt"), 2))
    }

    @Test
    fun `补零 zN 与 MT 文档一致（z8 得到 08 09 10 11）`() {
        // MT 0x7f1105bc 原文：{z8} 重命名会得到 08、09、10、11…
        assertEquals("08", BatchRename.newName("{z8}", item("x.txt"), 0))
        assertEquals("09", BatchRename.newName("{z8}", item("x.txt"), 1))
        assertEquals("10", BatchRename.newName("{z8}", item("x.txt"), 2))
        assertEquals("11", BatchRename.newName("{z8}", item("x.txt"), 3))
        // 补零宽度至少 2 位
        assertEquals("a01", BatchRename.newName("a{z1}", item("x.txt"), 0))
        // 起始值本身位数更多时按位数补
        assertEquals("a100", BatchRename.newName("a{z100}", item("x.txt"), 0))
    }

    @Test
    fun `查找替换 纯文本`() {
        // 表达式保持原名，再替换 IMG -> PIC
        val out = BatchRename.newName("{P}{S}", item("IMG_001.jpg"), 0, find = "IMG", replace = "PIC")
        assertEquals("PIC_001.jpg", out)
    }

    @Test
    fun `查找替换 正则`() {
        val out = BatchRename.newName(
            "{P}{S}", item("IMG_001.jpg"), 0,
            find = """_(\d+)""", replace = "-$1", useRegex = true,
        )
        assertEquals("IMG-001.jpg", out)
    }

    @Test
    fun `查找为空时只走表达式（MT 原文：不使用替换功能请将查找内容留空）`() {
        val out = BatchRename.newName("{P}", item("a.txt"), 0, find = "", replace = "XXX")
        assertEquals("a", out)
    }

    @Test
    fun `非法正则不抛异常且保持表达式结果`() {
        val out = BatchRename.newName("{P}{S}", item("a.txt"), 0, find = "([", replace = "x", useRegex = true)
        assertEquals("a.txt", out)
    }

    @Test
    fun `重名检测`() {
        val items = listOf(item("a.txt"), item("b.txt"))
        assertFalse(BatchRename.hasConflict(items, "{P}{S}"))
        // 全部改成同一个名字 → 冲突
        assertTrue(BatchRename.hasConflict(items, "same"))
    }

    @Test
    fun `预览只取前 N 项`() {
        val items = (1..20).map { item("f$it.txt") }
        assertEquals(8, BatchRename.preview(items, "{P}").size)
    }
}
