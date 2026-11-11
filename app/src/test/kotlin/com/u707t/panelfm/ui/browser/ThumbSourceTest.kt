package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表缩略图的**资格判定**回归（v2.3.3 加本地视频、v2.3.4 加网络视频时补上）。
 *
 * 这里锁的是产品决策，不是实现细节：
 *  1. 视频对**本地 + 网络协议**取首帧，压缩包内不取（要解包才能随机读）；
 *  2. 目录永远不取（目录名可能长得像 `xxx.mp4`）；
 *  3. 未知协议不取 —— 新增协议时要显式加进 `VIDEO_SCHEMES`，不能默默生效。
 */
class ThumbSourceTest {

    @Test
    fun `图片本地与远端都取`() {
        assertEquals(ThumbSource.IMAGE, thumbSourceOf("jpg", isDirectory = false, scheme = "local"))
        assertEquals(ThumbSource.IMAGE, thumbSourceOf("PNG", isDirectory = false, scheme = "sftp"))
        assertEquals(ThumbSource.IMAGE, thumbSourceOf("heic", isDirectory = false, scheme = "dav"))
        assertEquals(ThumbSource.IMAGE, thumbSourceOf("webp", isDirectory = false, scheme = "smb"))
    }

    @Test
    fun `本地视频走本地取帧`() {
        assertEquals(ThumbSource.VIDEO_LOCAL, thumbSourceOf("mp4", isDirectory = false, scheme = "local"))
        assertEquals(ThumbSource.VIDEO_LOCAL, thumbSourceOf("MKV", isDirectory = false, scheme = "local"))
        assertEquals(ThumbSource.VIDEO_LOCAL, thumbSourceOf("rmvb", isDirectory = false, scheme = "local"))
    }

    @Test
    fun `网络视频走 Range 取帧`() {
        // WebDAV 是主要场景；SFTP / SMB / S3 的 readFullyAt 都是真随机读
        assertEquals(ThumbSource.VIDEO_REMOTE, thumbSourceOf("mp4", isDirectory = false, scheme = "dav"))
        assertEquals(ThumbSource.VIDEO_REMOTE, thumbSourceOf("mkv", isDirectory = false, scheme = "sftp"))
        assertEquals(ThumbSource.VIDEO_REMOTE, thumbSourceOf("mp4", isDirectory = false, scheme = "smb"))
        assertEquals(ThumbSource.VIDEO_REMOTE, thumbSourceOf("mov", isDirectory = false, scheme = "s3"))
    }

    @Test
    fun `FTP 与压缩包内、未知协议都不取帧`() {
        // FTP 在这套 VFS 里是顺序流：seek 只接受原位、readFullyAt 只允许从 0 起 ≤64 KB 预读
        assertEquals(ThumbSource.NONE, thumbSourceOf("mp4", isDirectory = false, scheme = "ftp"))
        assertEquals(ThumbSource.NONE, thumbSourceOf("mp4", isDirectory = false, scheme = "ftps"))
        // 压缩包内随机读 = 从头解到该位置，比顺序读完整段还贵
        assertEquals(ThumbSource.NONE, thumbSourceOf("mp4", isDirectory = false, scheme = "archive"))
        assertEquals(ThumbSource.NONE, thumbSourceOf("mp4", isDirectory = false, scheme = "content"))
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
        assertEquals(ThumbSource.NONE, thumbSourceOf("flac", isDirectory = false, scheme = "dav"))
    }
}

