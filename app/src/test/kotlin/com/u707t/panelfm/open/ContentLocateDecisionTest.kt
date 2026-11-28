package com.u707t.panelfm.open

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「反查 → 定位」的整体判定回归（纯逻辑部分）。
 *
 * 这里锁的是**降级链**：只要中间任何一环不成立，就必须返回 null 让调用方去复制副本，
 * 绝不能因为「定位」这个可选功能让收文件失败。
 */
class ContentLocateDecisionTest {

    /** 模拟 IncomingFileReceiver.locate() 的判定：路径 → 卷 → VfsUri，任何一步失败即 null。 */
    private fun decide(absolutePath: String?): Pair<String, String>? {
        val path = when (localPathVisibility(absolutePath)) {
            LocalPathVisibility.BROWSABLE -> normalizeAbsolutePath(absolutePath) ?: return null
            LocalPathVisibility.PROTECTED, LocalPathVisibility.UNKNOWN -> return null
        }
        val volume = absolutePathToVolume(path) ?: return null
        return volume.authority to volume.relativePath
    }

    @Test
    fun `内置存储文件定位到 emulated 卷`() {
        assertEquals("emulated" to "/Download/a.pdf", decide("/storage/emulated/0/Download/a.pdf"))
    }

    @Test
    fun `外置卡文件定位到 vol 卷`() {
        assertEquals(
            "vol:storage_1234-ABCD" to "/movie/b.mp4",
            decide("/storage/1234-ABCD/movie/b.mp4"),
        )
    }

    @Test
    fun `受保护目录必须降级为复制`() {
        assertNull(decide("/storage/emulated/0/Android/data/com.tencent.mm/files/a.pdf"))
        assertNull(decide("/storage/emulated/0/Android/obb/com.x/o.bin"))
        assertNull(decide("/data/local/tmp/a"))
    }

    @Test
    fun `拿不到路径必须降级为复制`() {
        assertNull(decide(null))
        assertNull(decide(""))
        assertNull(decide("primary:Download/a.pdf"))
    }

    @Test
    fun `根目录本身不定位`() {
        // 根目录没有「定位到文件」的意义，且 parent 为 null
        assertNull(decide("/"))
    }
}
