package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表缩略图的**资格判定**回归（v2.3.3 加视频首帧时补上）。
 *
 * 这里锁的是两条产品决策，不是实现细节：
 *  1. 视频只对**本地**文件取帧（远端 / 压缩包内一律回退类型图标）；
 *  2. 目录永远不取（目录名可能长得像 `xxx.mp4`）。
 */
class ThumbSourceTest {

    @Test
    fun `图片本地与远端都取`() {
        assertEquals(ThumbSource.IMAGE, thumbSourceOf("jpg", isDirectory = false, scheme = "local"))
        assertEquals(ThumbSource.IMAGE, thumbSourceOf("PNG", isDirectory = false, scheme = "sftp"))
        assertEquals(ThumbSource.IMAGE, thumbSourceOf("heic", isDirectory = false, scheme = "webdav"))
        assertEquals(ThumbSource.IMAGE, thumbSourceOf("webp", isDirectory = false, scheme = "smb"))
    }

    @Test
    fun `视频只对本地取首帧`() {
        assertEquals(ThumbSource.VIDEO, thumbSourceOf("mp4", isDirectory = false, scheme = "local"))
        assertEquals(ThumbSource.VIDEO, thumbSourceOf("MKV", isDirectory = false, scheme = "local"))
        assertEquals(ThumbSource.VIDEO, thumbSourceOf("rmvb", isDirectory = false, scheme = "local"))
        // 远端取帧要解 moov（十几次随机读）+ 每文件超时，收益不抵成本
        assertEquals(ThumbSource.NONE, thumbSourceOf("mp4", isDirectory = false, scheme = "sftp"))
        assertEquals(ThumbSource.NONE, thumbSourceOf("mp4", isDirectory = false, scheme = "webdav"))
        assertEquals(ThumbSource.NONE, thumbSourceOf("mp4", isDirectory = false, scheme = "s3"))
        // 压缩包内要先解包才能读 → 不做
        assertEquals(ThumbSource.NONE, thumbSourceOf("mp4", isDirectory = false, scheme = "archive"))
    }

    @Test
    fun `目录与其它类型不取`() {
        assertEquals(ThumbSource.NONE, thumbSourceOf("mp4", isDirectory = true, scheme = "local"))
        assertEquals(ThumbSource.NONE, thumbSourceOf("jpg", isDirectory = true, scheme = "local"))
        assertEquals(ThumbSource.NONE, thumbSourceOf("pdf", isDirectory = false, scheme = "local"))
        assertEquals(ThumbSource.NONE, thumbSourceOf("docx", isDirectory = false, scheme = "local"))
        assertEquals(ThumbSource.NONE, thumbSourceOf("", isDirectory = false, scheme = "local"))
    }

    @Test
    fun `音频不取首帧`() {
        // 音频没有画面；封面内嵌图（ID3 APIC）是另一条管线，不在缩略图里做
        assertEquals(ThumbSource.NONE, thumbSourceOf("mp3", isDirectory = false, scheme = "local"))
        assertEquals(ThumbSource.NONE, thumbSourceOf("flac", isDirectory = false, scheme = "local"))
    }
}
