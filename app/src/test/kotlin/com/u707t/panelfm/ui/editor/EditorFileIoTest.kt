package com.u707t.panelfm.ui.editor

import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器文件层里**纯逻辑**的两处（读取 / 分页那套 IO 需要真 VFS，另行实机验证）：
 *  - 保存前的「文件是否被外部改动」判定；
 *  - 保存结果 → 状态栏文案。
 *
 * 误报会让用户每次保存都被拦一道，漏报会静默覆盖别人的改动——两个方向都要用例。
 */
class EditorFileIoTest {

    private fun meta(size: Long = 100, mtime: Long = 1_000, name: String = "a.txt") =
        FileMetadata(
            uri = VfsUri.parse("local://emulated/0/$name"),
            name = name,
            isDirectory = false,
            size = size,
            lastModified = mtime,
        )

    @Test
    fun `大小或时间变了就算被外部改动`() {
        assertTrue(fileChangedSince(meta(size = 100), meta(size = 101)))
        assertTrue(fileChangedSince(meta(mtime = 1_000), meta(mtime = 2_000)))
    }

    @Test
    fun `文件不见了算被外部改动`() {
        assertTrue(fileChangedSince(meta(), null))
    }

    @Test
    fun `完全没变不算改动`() {
        assertFalse(fileChangedSince(meta(), meta()))
    }

    @Test
    fun `远端给不出修改时间与大小时不误报`() {
        // 两边 size 都是 -1（未知）、mtime 都是 -1：没有可比信息 → 不拦
        assertFalse(fileChangedSince(meta(size = -1, mtime = -1), meta(size = -1, mtime = -1)))
        // 只有一边有 mtime：无法比较 → 不拦（宁少勿误报）
        assertFalse(fileChangedSince(meta(mtime = -1), meta(mtime = 2_000)))
        assertFalse(fileChangedSince(meta(mtime = 1_000), meta(mtime = -1)))
    }

    @Test
    fun `保存文案：正常·转存·备份·备份失败·出错`() {
        assertEquals(
            "已保存（UTF-8，12 字节）",
            saveStatusText(SaveOutcome(ok = true, charset = "UTF-8", bytes = 12), "UTF-8"),
        )
        assertEquals(
            "已保存（UTF-8·转存自 GBK，12 字节）",
            saveStatusText(SaveOutcome(ok = true, charset = "UTF-8", bytes = 12), "GBK"),
        )
        assertEquals(
            "已保存（UTF-8，12 字节，原文件已备份为 a.txt.bak）",
            saveStatusText(
                SaveOutcome(ok = true, charset = "UTF-8", bytes = 12, backupName = "a.txt.bak"),
                "UTF-8",
            ),
        )
        assertEquals(
            "已保存（UTF-8，12 字节；⚠️ 备份失败（本次未生成备份））",
            saveStatusText(SaveOutcome(ok = true, charset = "UTF-8", bytes = 12, backupFailed = true), "UTF-8"),
        )
        assertEquals(
            "保存失败：会话不可用",
            saveStatusText(SaveOutcome(ok = false, error = "会话不可用"), "UTF-8"),
        )
    }
}
