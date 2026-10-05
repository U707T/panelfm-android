package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按类型的二级菜单（MT 对齐）：
 *  - 压缩包 → 解压三件套 + 浏览压缩包 + 打开方式…
 *  - APK → 安装 / APK 信息 / 提取图标 + 打开方式…
 *  - 图片 / 视频 / 音频 / 字体 / PDF / 文本 → 内置查看 + 打开方式…
 *  - 目录 → 无类型菜单；压缩包内部 → 去掉解压 / 安装
 */
class TypeActionsTest {

    private fun ids(ext: String, dir: Boolean = false, inArchive: Boolean = false, installable: Boolean = true) =
        TypeActions.typedActionIds(ext, isDirectory = dir, inArchive = inArchive, apkInstallable = installable)

    @Test
    fun `压缩包给出解压三件套与浏览`() {
        assertEquals(
            listOf(
                TypeActions.ACTION_EXTRACT_HERE,
                TypeActions.ACTION_EXTRACT_OWN_FOLDER,
                TypeActions.ACTION_EXTRACT_PICK,
                TypeActions.ACTION_BROWSE_ARCHIVE,
                TypeActions.ACTION_OPEN_WITH,
            ),
            ids("zip"),
        )
        // 7z / rar / tar.gz 同属压缩包
        assertEquals(ids("zip"), ids("7z"))
        assertEquals(ids("zip"), ids("rar"))
    }

    @Test
    fun `压缩包内部不再给解压`() {
        val out = ids("zip", inArchive = true)
        assertFalse(out.contains(TypeActions.ACTION_EXTRACT_HERE))
        assertFalse(out.contains(TypeActions.ACTION_EXTRACT_OWN_FOLDER))
        assertFalse(out.contains(TypeActions.ACTION_EXTRACT_PICK))
        assertTrue(out.contains(TypeActions.ACTION_BROWSE_ARCHIVE))
    }

    @Test
    fun `APK 给出安装与信息`() {
        assertEquals(
            listOf(
                TypeActions.ACTION_INSTALL,
                TypeActions.ACTION_APK_INFO,
                TypeActions.ACTION_EXTRACT_APK_ICON,
                TypeActions.ACTION_OPEN_WITH,
            ),
            ids("apk"),
        )
    }

    @Test
    fun `不可安装的 APK 去掉安装项`() {
        val out = ids("apk", installable = false)
        assertFalse(out.contains(TypeActions.ACTION_INSTALL))
        assertTrue(out.contains(TypeActions.ACTION_APK_INFO))
    }

    @Test
    fun `压缩包内的 APK 不给安装`() {
        assertFalse(ids("apk", inArchive = true).contains(TypeActions.ACTION_INSTALL))
    }

    @Test
    fun `媒体与文本给出内置查看`() {
        listOf("jpg", "png", "mp4", "mkv", "mp3", "flac", "ttf", "pdf", "txt", "kt").forEach { ext ->
            val out = ids(ext)
            assertEquals("$ext 应有内置查看", true, out.contains(TypeActions.ACTION_OPEN_INTERNAL))
            assertEquals("$ext 最后应为打开方式", TypeActions.ACTION_OPEN_WITH, out.last())
        }
    }

    @Test
    fun `未知类型只有打开方式`() {
        assertEquals(listOf(TypeActions.ACTION_OPEN_WITH), ids("xyz"))
        assertEquals(listOf(TypeActions.ACTION_OPEN_WITH), ids("bin"))
    }

    @Test
    fun `目录没有类型菜单`() {
        assertTrue(ids("zip", dir = true).isEmpty())
        assertTrue(ids("apk", dir = true).isEmpty())
    }

    @Test
    fun `查看器文案对齐 MT`() {
        assertEquals("查看图片", TypeActions.viewerLabel(TypeActions.kindOf("png")))
        assertEquals("播放音乐", TypeActions.viewerLabel(TypeActions.kindOf("mp3")))
        assertEquals("播放视频", TypeActions.viewerLabel(TypeActions.kindOf("mp4")))
        assertEquals("查看字体", TypeActions.viewerLabel(TypeActions.kindOf("ttf")))
        assertNull(TypeActions.viewerLabel(TypeActions.kindOf("zip")))
        assertNull(TypeActions.viewerLabel(TypeActions.kindOf("apk")))
    }
}
