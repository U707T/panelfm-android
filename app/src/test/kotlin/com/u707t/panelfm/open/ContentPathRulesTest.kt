package com.u707t.panelfm.open

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「定位此文件」的路径解析规则回归。
 *
 * 这些是**安全边界**，不只是功能：document id 来自外部应用，
 * 解析出的路径必须不能爬出根目录，也不能把 `Android/data` 当成可浏览路径。
 */
class ContentPathRulesTest {

    // ------------------------------------------------------------------ 规范化

    @Test
    fun `合并重复斜杠与点段`() {
        assertEquals("/storage/emulated/0/a/b", normalizeAbsolutePath("/storage//emulated/./0/a/b"))
        assertEquals("/a", normalizeAbsolutePath("/a/./b/.."))
    }

    @Test
    fun `拒绝相对路径与空串`() {
        assertNull(normalizeAbsolutePath(""))
        assertNull(normalizeAbsolutePath("   "))
        assertNull(normalizeAbsolutePath("storage/emulated/0/a"))
        assertNull(normalizeAbsolutePath(null))
    }

    @Test
    fun `拒绝爬出根目录`() {
        assertNull(normalizeAbsolutePath("/../etc/passwd"))
        assertNull(normalizeAbsolutePath("/a/../../etc"))
        // 爬到根然后回来是合法的
        assertEquals("/etc", normalizeAbsolutePath("/a/../etc"))
    }

    // ------------------------------------------------------------------ document id

    @Test
    fun `raw 文档 id 直接取路径`() {
        assertEquals(
            "/storage/emulated/0/Download/a.pdf",
            documentIdToAbsolutePath("raw:/storage/emulated/0/Download/a.pdf"),
        )
    }

    @Test
    fun `primary 文档 id 落到内置存储`() {
        assertEquals(
            "/storage/emulated/0/Download/a.pdf",
            documentIdToAbsolutePath("primary:Download/a.pdf"),
        )
    }

    @Test
    fun `home 文档 id 落到内置存储根`() {
        assertEquals(
            "/storage/emulated/0/Documents/a.pdf",
            documentIdToAbsolutePath("home:Documents/a.pdf"),
        )
    }

    @Test
    fun `外置卡卷 id 文档`() {
        assertEquals(
            "/storage/1234-ABCD/movie/b.mp4",
            documentIdToAbsolutePath("1234-ABCD:movie/b.mp4"),
        )
    }

    @Test
    fun `中文与空格文件名保留`() {
        assertEquals(
            "/storage/emulated/0/下载/报告 2026.pdf",
            documentIdToAbsolutePath("primary:下载/报告 2026.pdf"),
        )
    }

    @Test
    fun `不认识的文档 id 返回 null 以触发降级`() {
        assertNull(documentIdToAbsolutePath("msf:1000000123"))
        assertNull(documentIdToAbsolutePath(""))
        assertNull(documentIdToAbsolutePath(null))
        assertNull(documentIdToAbsolutePath("没有冒号"))
        assertNull(documentIdToAbsolutePath(":只有路径"))
    }

    @Test
    fun `文档 id 里的越界路径被拒绝`() {
        assertNull(documentIdToAbsolutePath("raw:/../../etc/passwd"))
        // 没有爬出 /，但爬出了所属卷：不能借 document id 跳到别的卷
        assertNull(documentIdToAbsolutePath("primary:../../data/panel"))
        assertNull(documentIdToAbsolutePath("home:../../data/panel"))
        assertNull(documentIdToAbsolutePath("1234-ABCD:../../emulated/0/x"))
    }

    /** 卷内正常上跳仍然允许（`primary:Download/../Documents/a` → 内置存储/Documents）。 */
    @Test
    fun `卷内上跳仍然允许`() {
        assertEquals(
            "/storage/emulated/0/Documents/a.pdf",
            documentIdToAbsolutePath("primary:Download/../Documents/a.pdf"),
        )
    }

    // ------------------------------------------------------------------ 可见性

    @Test
    fun `内置存储可浏览`() {
        assertEquals(
            LocalPathVisibility.BROWSABLE,
            localPathVisibility("/storage/emulated/0/Download/a.pdf"),
        )
    }

    @Test
    fun `外置卡可浏览`() {
        assertEquals(
            LocalPathVisibility.BROWSABLE,
            localPathVisibility("/storage/1234-ABCD/movie/b.mp4"),
        )
    }

    @Test
    fun `Android data 与 obb 受保护`() {
        assertEquals(
            LocalPathVisibility.PROTECTED,
            localPathVisibility("/storage/emulated/0/Android/data/com.tencent.mm/files/a.pdf"),
        )
        assertEquals(
            LocalPathVisibility.PROTECTED,
            localPathVisibility("/storage/emulated/0/Android/obb/com.x/o.bin"),
        )
        assertEquals(
            LocalPathVisibility.PROTECTED,
            localPathVisibility("/storage/1234-ABCD/Android/data/com.x/a.txt"),
        )
    }

    @Test
    fun `系统区受保护`() {
        assertEquals(LocalPathVisibility.PROTECTED, localPathVisibility("/data/local/tmp/a"))
        assertEquals(LocalPathVisibility.PROTECTED, localPathVisibility("/system/build.prop"))
    }

    @Test
    fun `根目录与空路径不可定位`() {
        assertEquals(LocalPathVisibility.UNKNOWN, localPathVisibility("/"))
        assertEquals(LocalPathVisibility.UNKNOWN, localPathVisibility(null))
        assertEquals(LocalPathVisibility.UNKNOWN, localPathVisibility("not/absolute"))
    }

    /** `Android/data` 的判定必须是「目录边界」，不能把 `Android/database` 也当成受保护。 */
    @Test
    fun `受保护判定按目录边界而不是前缀`() {
        assertEquals(
            LocalPathVisibility.BROWSABLE,
            localPathVisibility("/storage/emulated/0/Android/database.txt"),
        )
        assertTrue(isUnderAbsolute("/storage/emulated/0/Android/data/x", "/storage/emulated/0/Android/data"))
        assertFalse(isUnderAbsolute("/storage/emulated/0/Android/datax", "/storage/emulated/0/Android/data"))
    }

    // ------------------------------------------------------------------ 绝对路径 → 卷

    @Test
    fun `内置存储路径映射成 emulated 卷`() {
        assertEquals(
            VolumeMapping("emulated", "/Download/a.pdf"),
            absolutePathToVolume("/storage/emulated/0/Download/a.pdf"),
        )
        assertEquals(VolumeMapping("emulated", "/"), absolutePathToVolume("/storage/emulated/0"))
    }

    /** 必须与 LocalVfs.absolutePath / LocalVolumes 的 vol: 编码一致，否则定位会落空。 */
    @Test
    fun `外置卡路径映射成 vol 卷且编码与 LocalVfs 一致`() {
        assertEquals(
            VolumeMapping("vol:storage_1234-ABCD", "/movie/b.mp4"),
            absolutePathToVolume("/storage/1234-ABCD/movie/b.mp4"),
        )
    }

    @Test
    fun `根目录其他路径映射成 root 卷`() {
        assertEquals(VolumeMapping("root", "/sdcard/x.txt"), absolutePathToVolume("/sdcard/x.txt"))
    }

    @Test
    fun `系统区不映射卷`() {
        assertNull(absolutePathToVolume("/data/local/tmp/a"))
        assertNull(absolutePathToVolume("/system/build.prop"))
        assertNull(absolutePathToVolume("relative/path"))
        assertNull(absolutePathToVolume(null))
    }
}
