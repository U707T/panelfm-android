package com.u707t.panelfm.ui.preview

import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随机播放纯函数的回归（2026-10-08 重审 §2 拆分后新增）。
 *
 * 锁两条不变量：「当前曲目恒在首位」「洗牌/恢复都不丢项、不引入项」——
 * 这两条在旧实现里只能靠肉眼看 UI，改一次赌一次。
 */
class MediaPlaylistTest {

    private fun item(name: String) = FileMetadata(
        uri = VfsUri.parse("local:///media/$name"),
        name = name,
        isDirectory = false,
        size = 1,
        lastModified = 0,
    )

    private val a = item("a.mp3")
    private val b = item("b.mp3")
    private val c = item("c.mp3")
    private val d = item("d.mp4")

    private fun keys(list: List<FileMetadata>) = list.map { it.uri.toString() }.sorted()

    // ------------------------------------------------------------------ shuffledFrom

    @Test
    fun `洗牌后当前曲目恒在首位且不丢项`() {
        val list = listOf(a, b, c, d)
        // shuffled 是随机的：多跑几轮覆盖不同排列，确认首项不变量与集合等价
        repeat(20) {
            val out = shuffledFrom(list, b.uri.toString())
            assertEquals(b.uri.toString(), out.first().uri.toString())
            assertEquals(keys(list), keys(out))
            assertEquals(4, out.size)
        }
    }

    @Test
    fun `当前曲目不在列表时无置顶项（整体打乱、不丢项）`() {
        val list = listOf(a, b, c)
        val out = shuffledFrom(list, "local:///media/ghost.mp3")
        assertEquals(keys(list), keys(out))
        assertEquals(3, out.size)
    }

    @Test
    fun `空列表洗牌仍为空`() {
        assertEquals(emptyList<FileMetadata>(), shuffledFrom(emptyList(), a.uri.toString()))
    }

    // ------------------------------------------------------------------ planShuffleToggle

    @Test
    fun `打开随机：洗牌且当前曲目置顶`() {
        val ordered = listOf(a, b, c, d)
        val out = planShuffleToggle(
            isShuffled = false,
            playlist = ordered,
            ordered = ordered,
            currentKey = c.uri.toString(),
        )
        assertTrue(out.shuffleOn)
        assertEquals(c.uri.toString(), out.playlist.first().uri.toString())
        assertEquals(keys(ordered), keys(out.playlist))
    }

    @Test
    fun `关闭随机：恢复文件本来顺序`() {
        val ordered = listOf(a, b, c, d)
        val shuffled = listOf(c, a, d, b)   // 模拟此前的洗牌结果
        val out = planShuffleToggle(
            isShuffled = true,
            playlist = shuffled,
            ordered = ordered,
            currentKey = c.uri.toString(),
        )
        assertFalse(out.shuffleOn)
        assertEquals(ordered.map { it.uri.toString() }, out.playlist.map { it.uri.toString() })
    }

    @Test
    fun `原顺序缺失时退回当前列表（不丢项）`() {
        val shuffled = listOf(c, a)
        val out = planShuffleToggle(
            isShuffled = true,
            playlist = shuffled,
            ordered = emptyList(),
            currentKey = c.uri.toString(),
        )
        assertFalse(out.shuffleOn)
        assertEquals(keys(shuffled), keys(out.playlist))
        assertEquals(2, out.playlist.size)
    }
}
