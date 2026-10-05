package com.u707t.panelfm.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 行内图标必须**按文件类型给出不同图形**（审计/体验反馈：此前所有文件共用同一个
 * 「折角文件」剪影，只靠底色区分，列表里一眼看不出是图片还是视频）。
 */
class GlyphKindTest {

    @Test
    fun `每种常见类型映射到各自的图形`() {
        assertEquals(GlyphKind.IMAGE, glyphKindOf("a.jpg"))
        assertEquals(GlyphKind.IMAGE, glyphKindOf("a.PNG"))
        assertEquals(GlyphKind.VIDEO, glyphKindOf("a.mp4"))
        assertEquals(GlyphKind.VIDEO, glyphKindOf("a.mkv"))
        assertEquals(GlyphKind.AUDIO, glyphKindOf("a.mp3"))
        assertEquals(GlyphKind.AUDIO, glyphKindOf("a.flac"))
        assertEquals(GlyphKind.ARCHIVE, glyphKindOf("a.zip"))
        assertEquals(GlyphKind.ARCHIVE, glyphKindOf("a.7z"))
        assertEquals(GlyphKind.APK, glyphKindOf("a.apk"))
        assertEquals(GlyphKind.FONT, glyphKindOf("a.ttf"))
        assertEquals(GlyphKind.PDF, glyphKindOf("a.pdf"))
        assertEquals(GlyphKind.CODE, glyphKindOf("a.kt"))
        assertEquals(GlyphKind.TEXT, glyphKindOf("a.txt"))
        assertEquals(GlyphKind.OTHER, glyphKindOf("a.bin"))
        assertEquals(GlyphKind.OTHER, glyphKindOf("noext"))
    }

    @Test
    fun `图形种类互不重复（不再所有文件共用一个）`() {
        val kinds = listOf("a.jpg", "a.mp4", "a.mp3", "a.zip", "a.apk", "a.ttf", "a.pdf", "a.kt", "a.txt")
            .map { glyphKindOf(it) }
        assertEquals("这些类型必须映射到 9 个不同图形", 9, kinds.toSet().size)
    }
}
