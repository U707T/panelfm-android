package com.u707t.panelfm.core.vfs

import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** 统一列目录排序（各协议共用；修复「降序把文件夹翻到底部」的实现）。 */
class FileListingTest {

    private fun f(name: String, dir: Boolean = false, size: Long = 0, time: Long = 0) =
        FileMetadata(uri = VfsUri.of("mem", "x", "/$name"), name = name, isDirectory = dir, size = size, lastModified = time)

    private val items get() = listOf(
        f("a.txt", size = 10, time = 100),
        f("dirB", dir = true, time = 300),
        f("b.txt", size = 20, time = 200),
        f("dirA", dir = true, time = 400),
    )

    @Test
    fun `降序时文件夹仍然置顶`() {
        val out = sortFileItems(items, SortSpec(by = SortBy.NAME, ascending = false, dirsFirst = true))
        assertEquals(listOf("dirB", "dirA", "b.txt", "a.txt"), out.map { it.name })
    }

    @Test
    fun `升序时文件夹置顶且各段有序`() {
        val out = sortFileItems(items, SortSpec(by = SortBy.NAME, ascending = true, dirsFirst = true))
        assertEquals(listOf("dirA", "dirB", "a.txt", "b.txt"), out.map { it.name })
    }

    @Test
    fun `不置顶时纯方向排序`() {
        val out = sortFileItems(items, SortSpec(by = SortBy.NAME, ascending = false, dirsFirst = false))
        assertEquals(listOf("dirB", "dirA", "b.txt", "a.txt"), out.map { it.name })
    }

    @Test
    fun `按大小与时间排序`() {
        val bySize = sortFileItems(items, SortSpec(by = SortBy.SIZE, ascending = true, dirsFirst = true))
        assertEquals(listOf("a.txt", "b.txt"), bySize.filter { !it.isDirectory }.map { it.name })

        val byTime = sortFileItems(items, SortSpec(by = SortBy.TIME, ascending = false, dirsFirst = true))
        assertEquals(listOf("dirA", "dirB", "b.txt", "a.txt"), byTime.map { it.name })
    }

    @Test
    fun `名称排序是自然序`() {
        val files = listOf(f("10.txt"), f("2.txt"), f("1.txt"), f("a10.txt"), f("a2.txt"))
        val out = sortFileItems(files, SortSpec(by = SortBy.NAME, ascending = true, dirsFirst = false))
        assertEquals(listOf("1.txt", "2.txt", "10.txt", "a2.txt", "a10.txt"), out.map { it.name })
    }

    @Test
    fun `同扩展名按名称自然序`() {
        val files = listOf(
            f("b10.zip"), f("b2.zip"), f("a1.zip"),
            f("noext10"), f("noext2"),
        )
        val out = sortFileItems(files, SortSpec(by = SortBy.TYPE, ascending = true, dirsFirst = false))
        // 无扩展名的排在前面（扩展名为空串），同扩展名内按名称自然序
        assertEquals(listOf("noext2", "noext10", "a1.zip", "b2.zip", "b10.zip"), out.map { it.name })
    }
}
