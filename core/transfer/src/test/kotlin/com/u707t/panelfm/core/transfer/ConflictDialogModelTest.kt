package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.model.ConflictInfo
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.defaultPolicy
import com.u707t.panelfm.core.model.explanationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冲突对话框的「四象限」文案与默认项（第 4 批审计 🟡1）：
 *  - 文件 → 文件夹 = 递归删除整个文件夹：文案写明、默认改「跳过」；
 *  - 其余三个组合保持 MT 习惯（默认「替换」），文案各自写清后果。
 */
class ConflictDialogModelTest {

    private fun info(sourceIsDirectory: Boolean, destIsDirectory: Boolean) = ConflictInfo(
        index = 0, total = 1,
        sourceName = "src", sourceSize = 1, sourceModified = 0,
        destName = "dest", destSize = 1, destModified = 0,
        isDirectory = destIsDirectory,
        isMove = false,
        sourceIsDirectory = sourceIsDirectory,
    )

    @Test
    fun `默认项：只有文件覆盖文件夹改默认跳过`() {
        assertEquals(ConflictPolicy.OVERWRITE, info(false, false).defaultPolicy())
        assertEquals("文件→文件夹默认必须安全", ConflictPolicy.SKIP, info(false, true).defaultPolicy())
        assertEquals("目录→目录 = 合并，保持替换默认", ConflictPolicy.OVERWRITE, info(true, true).defaultPolicy())
        assertEquals(ConflictPolicy.OVERWRITE, info(true, false).defaultPolicy())
    }

    @Test
    fun `解说文案按四象限分派且写清破坏面`() {
        assertTrue(info(false, false).explanationText().contains("覆盖目标文件"))
        assertTrue(
            "文件→文件夹必须写明删除整个文件夹",
            info(false, true).explanationText().contains("删除该文件夹及其全部内容"),
        )
        assertTrue(info(true, true).explanationText().contains("递归合并"))
        assertTrue(info(true, false).explanationText().contains("删除该文件并创建文件夹"))
    }
}
