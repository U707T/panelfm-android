package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MT「文件列表显示策略」三档（文案 `0x7f110200 / 201 / 202`）的渲染回归。
 */
class MtListSubtitleTest {

    @Test
    fun `档位 0 只显示时间`() {
        val out = MtListSubtitle.render(
            mode = 0,
            permissionText = "drwxr-xr-x",
            timeText = "2026-10-04 12:00",
            sizeText = "1.2 MB",
        )
        assertEquals("2026-10-04 12:00", out)
    }

    @Test
    fun `档位 2 显示时间加大小`() {
        val out = MtListSubtitle.render(
            mode = 2,
            permissionText = "drwxr-xr-x",
            timeText = "2026-10-04 12:00",
            sizeText = "1.2 MB",
        )
        assertEquals("2026-10-04 12:00  ·  1.2 MB", out)
    }

    @Test
    fun `档位 1 显示权限加大小`() {
        val out = MtListSubtitle.render(
            mode = 1,
            permissionText = "-rw-r--r--",
            timeText = "2026-10-04 12:00",
            sizeText = "1.2 MB",
        )
        assertEquals("-rw-r--r--  ·  1.2 MB", out)
    }

    @Test
    fun `档位 1 目录无大小时只有权限`() {
        val out = MtListSubtitle.render(
            mode = 1,
            permissionText = "drwxr-xr-x",
            timeText = "2026-10-04 12:00",
            sizeText = "",
        )
        assertEquals("drwxr-xr-x", out)
    }

    @Test
    fun `档位 1 拿不到权限时退回时间`() {
        val out = MtListSubtitle.render(
            mode = 1,
            permissionText = null,
            timeText = "2026-10-04 12:00",
            sizeText = "",
        )
        assertEquals("2026-10-04 12:00", out)
    }

    @Test
    fun `越界档位被夹到合法范围`() {
        // 大于 2 按最高档（时间+大小）处理
        assertEquals(
            MtListSubtitle.render(2, "-rw-r--r--", "t", "s"),
            MtListSubtitle.render(9, "-rw-r--r--", "t", "s"),
        )
        // 小于 0 按最低档（只显示时间）处理
        assertEquals(
            MtListSubtitle.render(0, "-rw-r--r--", "t", "s"),
            MtListSubtitle.render(-3, "-rw-r--r--", "t", "s"),
        )
    }

    @Test
    fun `档位名与 MT 文案一致`() {
        assertEquals(3, MtListSubtitle.MODE_LABELS.size)
        assertTrue(MtListSubtitle.MODE_LABELS[0].contains("不显示权限"))
        assertTrue(MtListSubtitle.MODE_LABELS[1].contains("权限+大小"))
        assertTrue(MtListSubtitle.MODE_LABELS[2].contains("时间+大小"))
    }
}
