package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F10 回归：「解压到文件夹…」对话框「目标路径」的解析。
 *
 * 旧实现里输入框是**死控件**（值被忽略、永远走「选择当前目录」模式）；
 * 现在语义：留空 / 与当前目录相同 = 保持选择模式；否则按输入路径直接解压。
 */
class ExtractTargetTest {

    @Test
    fun `留空不解析（保持选择目录模式）`() {
        assertNull(typedExtractPath(null, "/sdcard/Download"))
        assertNull(typedExtractPath("", "/sdcard/Download"))
        assertNull(typedExtractPath("   ", "/sdcard/Download"))
    }

    @Test
    fun `与当前目录相同不解析（默认值不改行为）`() {
        assertNull(typedExtractPath("/sdcard/Download", "/sdcard/Download"))
        assertNull(typedExtractPath("/sdcard/Download/", "/sdcard/Download"))
    }

    @Test
    fun `自定义路径规范化（补全开头斜杠、去掉末尾斜杠）`() {
        assertEquals("/sdcard/Documents", typedExtractPath("sdcard/Documents", "/sdcard/Download"))
        assertEquals("/sdcard/Documents", typedExtractPath("/sdcard/Documents/", "/sdcard/Download"))
        assertEquals("/", typedExtractPath("/", "/sdcard/Download"))
    }
}
