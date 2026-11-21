package com.u707t.panelfm.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已安装应用」页的纯逻辑：分页归类 / 搜索 / 副标题 / 导出文件名。
 *
 * 这些规则以前散在 composable 里（列表行直接调 PackageManager），既不能测也没法复用；
 * 抽到 [AppList] 后，`PackageManager` 那一半只剩 [loadInstalledApps] 里的取值。
 */
class AppListTest {

    private fun app(
        pkg: String,
        label: String,
        system: Boolean = false,
        version: String = "1.0",
        bytes: Long = 1024L * 1024,
    ) = InstalledApp(
        packageName = pkg,
        label = label,
        versionName = version,
        versionCode = 1,
        bytes = bytes,
        isSystem = system,
        sourceDir = "/data/app/$pkg/base.apk",
    )

    private val sample = listOf(
        app("com.u707t.panelfm", "PanelFM"),
        app("nini22p.iris", "IRIS"),
        app("com.android.settings", "设置", system = true),
        app("com.android.systemui", "系统界面", system = true),
        app("com.mi.health", "小米运动健康", version = "3.61.0"),
    )

    @Test
    fun `分页按系统应用标记划分`() {
        assertEquals(AppTab.USER, sample[0].tab)
        assertEquals(AppTab.SYSTEM, sample[2].tab)
        assertEquals(3, AppList.count(sample, AppTab.USER))
        assertEquals(2, AppList.count(sample, AppTab.SYSTEM))
        assertFalse(AppList.filter(sample, AppTab.USER, "").any { it.isSystem })
        assertTrue(AppList.filter(sample, AppTab.SYSTEM, "").all { it.isSystem })
    }

    @Test
    fun `搜索同时匹配应用名与包名（忽略大小写）`() {
        assertEquals(listOf("IRIS"), AppList.filter(sample, AppTab.USER, "iris").map { it.label })
        assertEquals(listOf("IRIS"), AppList.filter(sample, AppTab.USER, "nini22p").map { it.label })
        assertEquals(listOf("小米运动健康"), AppList.filter(sample, AppTab.USER, "健康").map { it.label })
        // 搜索不会跨分页命中
        assertTrue(AppList.filter(sample, AppTab.USER, "设置").isEmpty())
        // 空白搜索 = 全量
        assertEquals(3, AppList.filter(sample, AppTab.USER, "   ").size)
    }

    @Test
    fun `副标题是包名 版本 大小`() {
        val s = AppList.subtitleOf(app("nini22p.iris", "IRIS", version = "2.8.1", bytes = 33L * 1024 * 1024))
        assertEquals("nini22p.iris · v2.8.1 · 33.00M", s)
    }

    @Test
    fun `没有版本号时副标题不留空版本`() {
        val s = AppList.subtitleOf(app("a.b", "AB", version = "", bytes = 512))
        assertEquals("a.b · — · 512B", s)
    }

    @Test
    fun `导出文件名是应用名_版本号`() {
        assertEquals("IRIS_2.8.1.apk", AppList.exportFileName("IRIS", "2.8.1", isBundle = false))
        // 分包应用导成 .apks
        assertEquals("豆包_15.8.0.apks", AppList.exportFileName("豆包", "15.8.0", isBundle = true))
    }

    @Test
    fun `导出文件名替换路径分隔符`() {
        assertEquals("A_B_C_1.0.apk", AppList.exportFileName("A/B\\C", "1.0", isBundle = false))
    }

    @Test
    fun `空应用名与空版本号都能兜住`() {
        assertEquals("app.apk", AppList.exportFileName("", "", isBundle = false))
        assertEquals("app_1.0.apk", AppList.exportFileName("   ", "1.0", isBundle = false))
    }
}
