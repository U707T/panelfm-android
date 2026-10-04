package com.u707t.panelfm.ui.preview

import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放地址的构造 / 解析回归（修「视频无法播放」时锁死语义）。
 *
 * ## 为什么需要这些断言
 *
 * Media3 用 `Uri.getLastPathSegment()` 的后缀推断容器类型
 * （`Util.inferContentType` → m3u8 / mpd / 其它）。
 * 早期实现把播放地址写成 `panelfm://vfs?u=<编码后的 VFS URI>`（**path 为空**），
 * 于是 `getLastPathSegment()` 返回 authority `vfs`（没有点号）
 * → 类型恒为 `CONTENT_TYPE_OTHER`，且 `MediaItem.fromUri` 的 mimeType 为 null
 * → 部分容器的 Extractor 选不出来 → **播放失败 / 黑屏**。
 *
 * 现在的约定（由 [mediaUriString] / [vfsUriFromMediaUri] 保证）：
 *  1. path 末尾**必须**是真实文件名（含扩展名）；
 *  2. query `u` 里是完整的 VFS URI，编解码成对（空格编成 `%20` 而不是 `+`）；
 *  3. 常见容器额外显式给 `mimeType`（[mimeTypeForName]）。
 *
 * 这里只测**纯函数**（`android.net.Uri` 在 JVM 单测里是 not mocked 的桩）。
 */
class MediaUriTest {

    private fun local(path: String, name: String): VfsUri =
        VfsUri.of("local", "emulated", path).child(name)

    // ------------------------------------------------------------------ path 必须带真实文件名

    @Test
    fun `播放地址的 path 末尾是真实文件名（含扩展名）`() {
        val url = mediaUriString(local("/Download", "movie.mp4"))
        assertTrue("应以 panelfm://vfs/ 开头：$url", url.startsWith("panelfm://vfs/"))
        // Media3 用 getLastPathSegment() 的后缀判容器 —— path 段必须是 movie.mp4
        val pathPart = url.removePrefix("panelfm://vfs/").substringBefore('?')
        assertEquals("movie.mp4", pathPart)
    }

    @Test
    fun `扩展名保留（大小写与多段扩展都原样保留）`() {
        fun pathOf(name: String) =
            mediaUriString(local("/v", name)).removePrefix("panelfm://vfs/").substringBefore('?')
        assertEquals("clip.MKV", pathOf("clip.MKV"))
        assertEquals("archive.tar.gz", pathOf("archive.tar.gz"))
    }

    @Test
    fun `文件名带空格时编成 %20（不是 +，否则 Uri 解码会带出加号）`() {
        val url = mediaUriString(local("/Download", "我的 视频 01.mp4"))
        assertTrue("空格必须是 %20：$url", url.contains("%20"))
        assertTrue("不能出现裸加号：$url", !url.substringBefore('?').contains("+"))
        // 解回来要还原
        assertEquals("我的 视频 01.mp4", pathOfUrl(url))
    }

    @Test
    fun `文件名带百分号与加号时能原样往返`() {
        val name = "100% +50.mp4"
        val url = mediaUriString(local("/v", name))
        assertEquals(name, pathOfUrl(url))
    }

    @Test
    fun `名字为空时兜底为 media（避免 path 段为空）`() {
        val url = mediaUriString(VfsUri.of("local", "emulated", "/"))
        assertEquals("media", pathOfUrl(url))
    }

    // ------------------------------------------------------------------ query 里是完整 VFS URI

    @Test
    fun `query 里的 u 能原样解回完整 VFS URI`() {
        val vfs = local("/Download", "movie.mp4")
        val url = mediaUriString(vfs)
        assertEquals(vfs.toString(), vfsUriFromMediaUri(url)?.toString())
    }

    @Test
    fun `中文路径与文件名能原样往返`() {
        val vfs = local("/下载/电影", "片段.mkv")
        assertEquals(vfs.toString(), vfsUriFromMediaUri(mediaUriString(vfs))?.toString())
    }

    @Test
    fun `带 query 的连接参数也能往返（c=123）`() {
        val vfs = VfsUri.parse("dav://192.168.1.9:5244/movie.mp4?c=7")
        assertEquals(vfs.toString(), vfsUriFromMediaUri(mediaUriString(vfs))?.toString())
    }

    @Test
    fun `scheme 与 authority 固定为 panelfm 与 vfs`() {
        val url = mediaUriString(local("/v", "a.mp4"))
        assertEquals("panelfm", url.substringBefore("://"))
        assertEquals("vfs", url.substringAfter("://").substringBefore('/'))
    }

    @Test
    fun `非法播放地址返回 null（数据源据此抛错）`() {
        assertNull(vfsUriFromMediaUri("panelfm://vfs/a.mp4"))
        assertNull(vfsUriFromMediaUri("panelfm://vfs/a.mp4?u="))
        assertNull(vfsUriFromMediaUri(""))
        assertNull(vfsUriFromMediaUri("not a uri at all"))
    }

    // ------------------------------------------------------------------ mimeType

    @Test
    fun `常见视频容器给出 mimeType`() {
        assertEquals("video/mp4", mimeTypeForName("a.mp4"))
        assertEquals("video/x-matroska", mimeTypeForName("a.mkv"))
        assertEquals("video/webm", mimeTypeForName("a.webm"))
        assertEquals("video/quicktime", mimeTypeForName("a.mov"))
        assertEquals("video/3gpp", mimeTypeForName("a.3gp"))
        assertEquals("video/mp2t", mimeTypeForName("a.ts"))
    }

    @Test
    fun `常见音频容器给出 mimeType`() {
        assertEquals("audio/mpeg", mimeTypeForName("a.mp3"))
        assertEquals("audio/flac", mimeTypeForName("a.flac"))
        assertEquals("audio/mp4", mimeTypeForName("a.m4a"))
        assertEquals("audio/ogg", mimeTypeForName("a.ogg"))
    }

    @Test
    fun `mp4 家族区分视频与纯音频（m4a 不是 video）`() {
        assertEquals("video/mp4", mimeTypeForName("movie.mp4"))
        assertEquals("audio/mp4", mimeTypeForName("song.m4a"))
    }

    @Test
    fun `流媒体清单也给出 mimeType`() {
        assertEquals("application/x-mpegURL", mimeTypeForName("index.m3u8"))
        assertEquals("application/dash+xml", mimeTypeForName("manifest.mpd"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals("video/mp4", mimeTypeForName("A.MP4"))
        assertEquals("audio/mpeg", mimeTypeForName("A.Mp3"))
    }

    @Test
    fun `未收录的扩展名返回 null（交给 Media3 按后缀自行推断）`() {
        assertNull(mimeTypeForName("a.xyz"))
        assertNull(mimeTypeForName("noext"))
        assertNull(mimeTypeForName(""))
    }

    // ------------------------------------------------------------------ 端到端往返

    @Test
    fun `各种文件名端到端往返都一致`() {
        val cases = listOf(
            local("/Download", "movie.mp4"),
            local("/下载/电影", "片段.mkv"),
            local("/v", "100% +50.mp3"),
            local("/v", "a.b.c.flac"),
            local("/v", "带 空格 的 视频.webm"),
        )
        for (vfs in cases) {
            val url = mediaUriString(vfs)
            // 数据源 open() 里的两步：取 path 段（判容器）+ 解 query（拿 VFS URI）
            assertEquals("path 段应保留文件名：$vfs", vfs.name, pathOfUrl(url))
            assertEquals("query 应能解回：$vfs", vfs.toString(), vfsUriFromMediaUri(url)?.toString())
        }
    }

    /** 从播放地址里取出解码后的 path 段（等价于 Android 的 `Uri.getLastPathSegment()`） */
    private fun pathOfUrl(url: String): String =
        percentDecode(url.removePrefix("panelfm://vfs/").substringBefore('?'))
}
