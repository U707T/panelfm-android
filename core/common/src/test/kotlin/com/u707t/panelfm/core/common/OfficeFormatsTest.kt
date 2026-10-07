package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Office 支持矩阵：新格式给渲染器，旧二进制格式给说明文案。 */
class OfficeFormatsTest {

    @Test
    fun `新格式映射到对应渲染器`() {
        assertEquals(OfficeFormats.KIND_DOCX, OfficeFormats.viewerKindOf("docx"))
        assertEquals(OfficeFormats.KIND_XLSX, OfficeFormats.viewerKindOf("xlsx"))
        assertEquals(OfficeFormats.KIND_XLSX, OfficeFormats.viewerKindOf("xls"))
        assertEquals(OfficeFormats.KIND_PPTX, OfficeFormats.viewerKindOf("pptx"))
    }

    @Test
    fun `旧二进制格式可进预览但没有渲染器`() {
        assertTrue(OfficeFormats.isOffice("doc"))
        assertTrue(OfficeFormats.isOffice("ppt"))
        assertEquals("", OfficeFormats.viewerKindOf("doc"))
        assertEquals("", OfficeFormats.viewerKindOf("ppt"))
        assertTrue(OfficeFormats.unsupportedMessage("doc").contains(".doc"))
        assertTrue(OfficeFormats.unsupportedMessage("ppt").contains(".ppt"))
    }

    @Test
    fun `大小写不敏感，非 Office 后缀一律不是`() {
        assertEquals(OfficeFormats.KIND_DOCX, OfficeFormats.viewerKindOf("DOCX"))
        assertEquals(OfficeFormats.KIND_XLSX, OfficeFormats.viewerKindOf("XLS"))
        assertFalse(OfficeFormats.isOffice("txt"))
        assertFalse(OfficeFormats.isOffice("pdf"))
        assertFalse(OfficeFormats.isOffice(""))
    }

    @Test
    fun `扩展名集合与 Kind 分派一致`() {
        assertEquals(
            setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx"),
            OfficeFormats.EXTENSIONS,
        )
        // MimeTypes 侧必须把同一批后缀分到 DOCUMENT
        OfficeFormats.EXTENSIONS.forEach { ext ->
            assertEquals("$ext 应为 DOCUMENT", MimeTypes.Kind.DOCUMENT, MimeTypes.kindOf(ext))
        }
    }
}
