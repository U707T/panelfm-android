package com.u707t.panelfm.ui.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 已安装应用列表的磁盘缓存。
 *
 * 缓存只影响「首帧」——每次打开仍会后台重扫并覆盖，所以这里的契约只有三条：
 * 往返一致、坏数据当没有、版本不符当没有（绝不半信半疑地用半份列表）。
 */
class AppListCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample() = listOf(
        InstalledApp(
            packageName = "com.u707t.panelfm",
            label = "PanelFM",
            versionName = "2.5.7",
            versionCode = 130,
            bytes = 22L * 1024 * 1024,
            isSystem = false,
            sourceDir = "/data/app/~~abc/com.u707t.panelfm-1/base.apk",
            splitCount = 2,
            className = "com.u707t.panelfm.PanelApp",
        ),
        InstalledApp(
            packageName = "com.android.settings",
            label = "设置",
            versionName = "",
            versionCode = 0,
            bytes = 0,
            isSystem = true,
            sourceDir = "/system/priv-app/Settings/Settings.apk",
        ),
    )

    @Test
    fun `编解码往返一致`() {
        assertEquals(sample(), AppListCache.decode(AppListCache.encode(sample())))
    }

    @Test
    fun `空列表也能往返`() {
        assertEquals(emptyList<InstalledApp>(), AppListCache.decode(AppListCache.encode(emptyList())))
    }

    @Test
    fun `写文件再读回来一致`() {
        val file = File(tmp.newFolder("cache"), AppListCache.FILE_NAME)
        assertTrue(AppListCache.write(file, sample()))
        assertEquals(sample(), AppListCache.read(file))
    }

    @Test
    fun `文件不存在返回 null`() {
        assertNull(AppListCache.read(File(tmp.root, "nope.json")))
    }

    @Test
    fun `损坏的 JSON 返回 null`() {
        assertNull(AppListCache.decode("{oops"))
        assertNull(AppListCache.decode(""))
    }

    @Test
    fun `格式版本不符当没有缓存`() {
        val text = AppListCache.encode(sample()).replace("\"v\":${AppListCache.VERSION}", "\"v\":999")
        assertNull(AppListCache.decode(text))
    }

    @Test
    fun `缺关键字段当损坏丢弃`() {
        assertNull(AppListCache.decode("""{"v":1,"apps":[{"l":"缺包名"}]}"""))
        assertNull(AppListCache.decode("""{"v":1}"""))
    }
}
