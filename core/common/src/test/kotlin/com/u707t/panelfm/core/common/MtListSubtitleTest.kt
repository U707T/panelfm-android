package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MT「文件列表显示策略」两档（文案 `0x7f110200 / 201`）的渲染回归。
 *
 * v2.5.1 起**文件重新带上大小**（对齐 MT：MT 的副标题是同一个 TextView，体积跟在时间 / 权限后面），
 * 但**目录永远不带** —— 目录体积要递归统计，只在「属性」里给。
 * 这两条都是产品口径，不是实现细节：改 render 的参数前先看这里的断言。
 */
class MtListSubtitleTest {

    @Test
    fun `档位 0 是时间加文件大小`() {
        val out = MtListSubtitle.render(
            mode = 0,
            permissionText = "drwxr-xr-x",
            timeText = "26-11-20 17:40",
            sizeText = "1.50 M",
        )
        assertEquals("26-11-20 17:40  1.50 M", out)
    }

    @Test
    fun `档位 1 是权限加文件大小`() {
        val out = MtListSubtitle.render(
            mode = 1,
            permissionText = "-rw-r--r--",
            timeText = "26-11-20 17:40",
            sizeText = "512 B",
        )
        assertEquals("-rw-r--r--  512 B", out)
    }

    @Test
    fun `文件夹不带大小`() {
        // 目录 / 大小未知 → 调用方传空串，副标题只剩时间或权限（不出现「0 B」或悬空分隔符）
        assertEquals(
            "26-11-20 17:40",
            MtListSubtitle.render(0, "-rw-r--r--", "26-11-20 17:40", ""),
        )
        assertEquals(
            "drwxr-xr-x",
            MtListSubtitle.render(1, "drwxr-xr-x", "26-11-20 17:40", ""),
        )
    }

    @Test
    fun `档位 1 拿不到权限时退回时间`() {
        // 远端协议（WebDAV / S3 等）不给权限位：副标题不能空着
        assertEquals(
            "26-11-20 17:40",
            MtListSubtitle.render(1, permissionText = null, timeText = "26-11-20 17:40"),
        )
        assertEquals(
            "26-11-20 17:40",
            MtListSubtitle.render(1, permissionText = "   ", timeText = "26-11-20 17:40"),
        )
        // 有时间又有大小时，退回时间也不能把大小丢掉
        assertEquals(
            "26-11-20 17:40  2.00 M",
            MtListSubtitle.render(1, permissionText = null, timeText = "26-11-20 17:40", sizeText = "2.00 M"),
        )
    }

    @Test
    fun `时间和大小都拿不到时只剩大小`() {
        // 极端情况（远端不给 mtime）：不能出现前导空格
        assertEquals("2.00 M", MtListSubtitle.render(0, null, "", "2.00 M"))
        assertEquals("", MtListSubtitle.render(0, null, "", ""))
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
    fun `档位名只有两档`() {
        assertEquals(2, MtListSubtitle.MODE_LABELS.size)
        assertTrue(MtListSubtitle.MODE_LABELS[0].contains("时间"))
        assertTrue(MtListSubtitle.MODE_LABELS[1].contains("权限"))
    }
}
