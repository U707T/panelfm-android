package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** API level → Android 版本名（APK 信息对话框的「目标系统 / 最低系统」两行） */
class AndroidApiTest {

    @Test
    fun `常见 API 的版本名`() {
        assertEquals("5.0", AndroidApi.versionName(21))
        assertEquals("8.0", AndroidApi.versionName(26))
        assertEquals("10", AndroidApi.versionName(29))
        assertEquals("12L", AndroidApi.versionName(32))
        assertEquals("14", AndroidApi.versionName(34))
        assertEquals("17", AndroidApi.versionName(37))
    }

    @Test
    fun `展示文案与 MT 一致`() {
        assertEquals("Android 14 (API 34)", AndroidApi.label(34))
        assertEquals("Android 5.0 (API 21)", AndroidApi.label(21))
        assertEquals("Android 17 (API 37)", AndroidApi.label(37))
    }

    @Test
    fun `未知 API 退化成 API nn，不猜版本名`() {
        assertEquals("API 19", AndroidApi.label(19))
        assertNull(AndroidApi.versionName(19))
        assertEquals("API 99", AndroidApi.label(99))
    }

    @Test
    fun `0 与负数显示为破折号（清单没写、解析失败）`() {
        assertEquals("—", AndroidApi.label(0))
        assertEquals("—", AndroidApi.label(-1))
    }
}
