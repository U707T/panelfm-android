package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 扩展名分类回归（新增媒体别名 / zip 家族 / 代码文本扩表都要落在这里）。
 */
class MimeTypesTest {

    @Test
    fun `媒体别名进入播放器`() {
        listOf("m4b", "m4r", "amr", "awb", "oga", "mka").forEach {
            assertEquals("$it 应为 AUDIO", MimeTypes.Kind.AUDIO, MimeTypes.kindOf(it))
        }
        listOf("3gpp", "f4v", "m2ts", "m4v", "3gp").forEach {
            assertEquals("$it 应为 VIDEO", MimeTypes.Kind.VIDEO, MimeTypes.kindOf(it))
        }
        // 播放器 mime 映射与分类同步（MediaScreen 用）
        assertEquals("audio/mp4", MimeTypes.of("m4b"))
        assertEquals("audio/amr", MimeTypes.of("amr"))
        assertEquals("video/3gpp", MimeTypes.of("3gpp"))
    }

    @Test
    fun `zip 家族按压缩包处理（可直接浏览）`() {
        listOf("epub", "whl", "nupkg", "vsix", "crx", "kmz", "xapk", "apks", "apkm").forEach {
            assertEquals("$it 应为 ARCHIVE", MimeTypes.Kind.ARCHIVE, MimeTypes.kindOf(it))
        }
    }

    @Test
    fun `新增归档后缀按压缩包处理`() {
        listOf("lzma", "z", "lz4", "cpio", "ar", "deb", "tgz", "tbz2", "txz").forEach {
            assertEquals("$it 应为 ARCHIVE", MimeTypes.Kind.ARCHIVE, MimeTypes.kindOf(it))
        }
    }

    @Test
    fun `代码与文本扩表`() {
        listOf("c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "lua", "toml", "kts", "ps1", "bat", "diff").forEach {
            assertEquals("$it 应为 CODE", MimeTypes.Kind.CODE, MimeTypes.kindOf(it))
        }
        listOf("srt", "ass", "vtt", "lrc", "markdown").forEach {
            assertEquals("$it 应为 TEXT", MimeTypes.Kind.TEXT, MimeTypes.kindOf(it))
        }
        // 老格式分类不变（回归）
        assertEquals(MimeTypes.Kind.VIDEO, MimeTypes.kindOf("ts"))
        assertEquals(MimeTypes.Kind.TEXT, MimeTypes.kindOf("csv"))
        assertEquals(MimeTypes.Kind.IMAGE, MimeTypes.kindOf("svg"))
    }

    @Test
    fun `新增图片别名与 MIME 齐全`() {
        assertEquals(MimeTypes.Kind.IMAGE, MimeTypes.kindOf("jfif"))
        assertEquals(MimeTypes.Kind.IMAGE, MimeTypes.kindOf("svgz"))
        assertEquals("image/svg+xml", MimeTypes.of("svgz"))
        assertEquals("image/jpeg", MimeTypes.of("jpe"))
        listOf("jfif", "jpe", "jpeg").forEach { assertNotNull(it, MimeTypes.of(it)) }
    }
}
