package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MT「文件列表显示策略」两档（文案 `0x7f110200 / 201`）的渲染回归。
 *
 * v2.4.2 起列表**不再显示大小**（体积只在「属性」里看），第三档「时间+大小」随之下线，
 * 所以这里不再有 sizeText 参数 —— 这一条本身就是回归断言：接口里没有「大小」这个输入。
 */
class MtListSubtitleTest {

    @Test
    fun `档位 0 只显示时间`() {
        val out = MtListSubtitle.render(
            mode = 0,
            permissionText = "drwxr-xr-x",
            timeText = "2026-10-04 12:00",
        )
        assertEquals("2026-10-04 12:00", out)
    }

    @Test
    fun `档位 1 只显示权限`() {
        val out = MtListSubtitle.render(
            mode = 1,
            permissionText = "-rw-r--r--",
            timeText = "2026-10-04 12:00",
        )
        assertEquals("-rw-r--r--", out)
    }

    @Test
    fun `档位 1 拿不到权限时退回时间`() {
        // 远端协议（WebDAV / S3 等）不给权限位：副标题不能空着
        assertEquals(
            "2026-10-04 12:00",
            MtListSubtitle.render(1, permissionText = null, timeText = "2026-10-04 12:00"),
        )
        assertEquals(
            "2026-10-04 12:00",
            MtListSubtitle.render(1, permissionText = "   ", timeText = "2026-10-04 12:00"),
        )
    }

    @Test
    fun `越界档位被夹到合法范围`() {
        // 旧版本存下来的 2（时间+大小）→ 按最高档 1（权限）夹；这里只保证不崩、落在两档之内
        assertEquals(
            MtListSubtitle.render(1, "-rw-r--r--", "t"),
            MtListSubtitle.render(9, "-rw-r--r--", "t"),
        )
        assertEquals(
            MtListSubtitle.render(0, "-rw-r--r--", "t"),
            MtListSubtitle.render(-3, "-rw-r--r--", "t"),
        )
    }

    @Test
    fun `档位名只有两档且不再提大小`() {
        assertEquals(2, MtListSubtitle.MODE_LABELS.size)
        assertTrue(MtListSubtitle.MODE_LABELS[0].contains("时间"))
        assertTrue(MtListSubtitle.MODE_LABELS[1].contains("权限"))
        assertTrue(MtListSubtitle.MODE_LABELS.none { it.contains("大小") })
    }
}
