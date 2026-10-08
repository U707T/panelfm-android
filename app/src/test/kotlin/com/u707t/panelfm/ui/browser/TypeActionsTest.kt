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
 *  - 文本 / 代码 → 查看文本 + 编辑文本 + 打开方式…
 *  - Office 文档（docx/xlsx/pptx；含旧 doc/ppt）→ 文档预览 + 打开方式…
 *  - 图片 / 视频 / 音频 / 字体 / PDF → 内置查看 + 打开方式…
 *  - 未识别类型 → 通用兜底：查看文本 + 编辑文本 + 打开方式…（手动选择）
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
        // 新增：单文件压缩流 / tar 系复合后缀（tgz/txz）/ 裸归档 / zip 家族（epub 等）
        listOf("gz", "xz", "txz", "tgz", "tbz2", "bz2", "lzma", "lz4", "cpio", "deb", "epub", "apks").forEach { ext ->
            assertEquals("$ext 应与 zip 同菜单", ids("zip"), ids(ext))
        }
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
    fun `Office 文档给文档预览入口`() {
        listOf(
            "doc", "docx", "docm", "dotx",
            "xls", "xlsx", "xlsm", "ods", "fods",
            "ppt", "pptx", "pptm", "ppsx",
        ).forEach { ext ->
            val out = ids(ext)
            assertEquals("$ext 应有文档预览", true, out.contains(TypeActions.ACTION_OPEN_INTERNAL))
            assertEquals("$ext 最后应为打开方式", TypeActions.ACTION_OPEN_WITH, out.last())
            assertFalse("$ext 不应给编辑文本", out.contains(TypeActions.ACTION_EDIT_TEXT))
        }
        assertEquals("文档预览", TypeActions.labelOf(TypeActions.ACTION_OPEN_INTERNAL, TypeActions.kindOf("docx")))
    }

    @Test
    fun `媒体与文本给出内置查看`() {
        listOf(
            "jpg", "png", "jfif", "mp4", "mkv", "3gpp", "mp3", "flac", "amr", "m4b", "oga",
            "ttf", "pdf", "txt", "kt", "c", "go", "srt",
        ).forEach { ext ->
            val out = ids(ext)
            assertEquals("$ext 应有内置查看", true, out.contains(TypeActions.ACTION_OPEN_INTERNAL))
            assertEquals("$ext 最后应为打开方式", TypeActions.ACTION_OPEN_WITH, out.last())
        }
    }

    @Test
    fun `未识别类型给通用查看编辑与打开方式`() {
        val generic = listOf(TypeActions.ACTION_OPEN_INTERNAL, TypeActions.ACTION_EDIT_TEXT, TypeActions.ACTION_OPEN_WITH)
        assertEquals(generic, ids("xyz"))
        assertEquals(generic, ids("bin"))
    }

    @Test
    fun `文本与代码给查看与编辑文本`() {
        listOf("txt", "log", "md", "kt", "json").forEach { ext ->
            val out = ids(ext)
            assertEquals("$ext 应有查看文本", true, out.contains(TypeActions.ACTION_OPEN_INTERNAL))
            assertEquals("$ext 应有编辑文本", true, out.contains(TypeActions.ACTION_EDIT_TEXT))
            assertEquals("$ext 最后应为打开方式", TypeActions.ACTION_OPEN_WITH, out.last())
        }
    }

    @Test
    fun `二级菜单文案按 id 与类型给`() {
        assertEquals("编辑文本", TypeActions.labelOf(TypeActions.ACTION_EDIT_TEXT, TypeActions.kindOf("kt")))
        assertEquals("查看文本", TypeActions.labelOf(TypeActions.ACTION_OPEN_INTERNAL, TypeActions.kindOf("xyz")))
        assertEquals("查看图片", TypeActions.labelOf(TypeActions.ACTION_OPEN_INTERNAL, TypeActions.kindOf("png")))
        assertEquals("播放音乐", TypeActions.labelOf(TypeActions.ACTION_OPEN_INTERNAL, TypeActions.kindOf("mp3")))
        assertEquals("打开方式…", TypeActions.labelOf(TypeActions.ACTION_OPEN_WITH, TypeActions.kindOf("kt")))
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
