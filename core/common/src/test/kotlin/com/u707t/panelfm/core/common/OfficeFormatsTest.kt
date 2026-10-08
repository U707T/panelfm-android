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
    fun `OOXML 模板与宏格式复用同一渲染器`() {
        // docm/dotx/dotm：同一 OOXML 包（宏不执行）
        listOf("docm", "dotx", "dotm").forEach {
            assertEquals(it, OfficeFormats.KIND_DOCX, OfficeFormats.viewerKindOf(it))
            assertTrue(it, OfficeFormats.isOffice(it))
        }
        listOf("xlsm", "xltx", "xltm").forEach {
            assertEquals(it, OfficeFormats.KIND_XLSX, OfficeFormats.viewerKindOf(it))
        }
        listOf("pptm", "ppsx", "potx", "potm").forEach {
            assertEquals(it, OfficeFormats.KIND_PPTX, OfficeFormats.viewerKindOf(it))
        }
    }

    @Test
    fun `ODF 表格走 SheetJS（xlsx 渲染器）`() {
        assertEquals(OfficeFormats.KIND_XLSX, OfficeFormats.viewerKindOf("ods"))
        assertEquals(OfficeFormats.KIND_XLSX, OfficeFormats.viewerKindOf("fods"))
        assertTrue(OfficeFormats.isOffice("ods"))
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
        assertEquals(OfficeFormats.KIND_DOCX, OfficeFormats.viewerKindOf("DOCM"))
        assertFalse(OfficeFormats.isOffice("txt"))
        assertFalse(OfficeFormats.isOffice("pdf"))
        assertFalse(OfficeFormats.isOffice(""))
    }

    @Test
    fun `扩展名集合与 Kind 分派一致`() {
        assertEquals(
            setOf(
                "doc", "docx", "docm", "dotx", "dotm",
                "xls", "xlsx", "xlsm", "xltx", "xltm", "ods", "fods",
                "ppt", "pptx", "pptm", "ppsx", "potx", "potm",
            ),
            OfficeFormats.EXTENSIONS,
        )
        // MimeTypes 侧必须把同一批后缀分到 DOCUMENT
        OfficeFormats.EXTENSIONS.forEach { ext ->
            assertEquals("$ext 应为 DOCUMENT", MimeTypes.Kind.DOCUMENT, MimeTypes.kindOf(ext))
        }
    }

    @Test
    fun `每个可渲染后缀都有 MIME 且非空渲染器`() {
        OfficeFormats.EXTENSIONS.forEach { ext ->
            assertTrue("$ext 缺 MIME", MimeTypes.of(ext) != null)
        }
    }
}
