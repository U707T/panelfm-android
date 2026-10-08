package com.u707t.panelfm.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SVG 渲染尺寸与 SVGZ 魔数判定（纯函数）。
 * 真正的矢量渲染依赖 Android 图形栈，无 JVM 单测 —— 实机抽验（见 CHANGELOG）。
 */
class SvgDecodeTest {

    @Test
    fun `横竖按长边等比`() {
        assertEquals(2048 to 1024, svgRenderSize(2f))
        assertEquals(2048 to 2048, svgRenderSize(1f))
        assertEquals(1024 to 2048, svgRenderSize(0.5f))
        assertEquals(2048 to 512, svgRenderSize(4f))
    }

    @Test
    fun `比例无效时按正方形兜底`() {
        listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
            assertEquals("aspect=$bad", 2048 to 2048, svgRenderSize(bad))
        }
    }

    @Test
    fun `极端长条至少保留 1px`() {
        assertEquals(2048 to 1, svgRenderSize(100000f))
        assertEquals(1 to 2048, svgRenderSize(0.00001f))
    }

    @Test
    fun `长边可配置`() {
        assertEquals(512 to 256, svgRenderSize(2f, maxEdge = 512))
        assertEquals(256 to 512, svgRenderSize(0.5f, maxEdge = 512))
    }

    @Test
    fun `SVGZ 按 gzip 魔数识别`() {
        assertTrue(isGzipMagic(0x1F, 0x8B))
        assertFalse("普通 XML（<?xml…) 不是 gzip", isGzipMagic(0x3C, 0x73))
        assertFalse(isGzipMagic(0x1F, 0x00))
    }
}
