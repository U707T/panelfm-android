package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 渲染预览矩阵（Markdown / CSV）：kind 映射与大小写、以及「默认仍是文本查看器」的口径。 */
class RenderFormatsTest {

    @Test
    fun `markdown 与 csv 映射到对应渲染器`() {
        assertEquals(RenderFormats.KIND_MARKDOWN, RenderFormats.viewerKindOf("md"))
        assertEquals(RenderFormats.KIND_MARKDOWN, RenderFormats.viewerKindOf("markdown"))
        assertEquals(RenderFormats.KIND_CSV, RenderFormats.viewerKindOf("csv"))
    }

    @Test
    fun `大小写不敏感且其它后缀不可渲染`() {
        assertEquals(RenderFormats.KIND_MARKDOWN, RenderFormats.viewerKindOf("MD"))
        assertEquals(RenderFormats.KIND_CSV, RenderFormats.viewerKindOf("CSV"))
        assertEquals("", RenderFormats.viewerKindOf("txt"))
        assertEquals("", RenderFormats.viewerKindOf(""))
        assertFalse(RenderFormats.isRenderable("txt"))
        assertFalse(RenderFormats.isRenderable("docx"))
    }

    @Test
    fun `可渲染集合与扩展名集合一致`() {
        assertEquals(setOf("md", "markdown", "csv"), RenderFormats.EXTENSIONS)
        RenderFormats.EXTENSIONS.forEach { ext ->
            assertTrue("$ext 应有渲染器", RenderFormats.viewerKindOf(ext).isNotEmpty())
        }
    }

    @Test
    fun `渲染预览被分类为文本或代码（默认打开方式仍是查看器）`() {
        // md / csv 仍走 TEXT 分类：默认打开方式是文本查看器，渲染预览是显式选择
        assertEquals(MimeTypes.Kind.TEXT, MimeTypes.kindOf("md"))
        assertEquals(MimeTypes.Kind.TEXT, MimeTypes.kindOf("csv"))
    }
}
