package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「APK 信息」卡片的行文案（对齐 MT 截图）。
 *
 * 这些断言是**版式契约**：字段顺序、标签写法、单位格式都在这里钉死；
 * 真正的解析（PackageManager / zip）在 [loadApkInfo] 里，需要真机才能验证。
 */
class ApkInfoRowsTest {

    private fun sample(
        installed: Boolean = true,
        bundleNote: String? = null,
        size: Long = 5L * 1024 * 1024,
    ) = ApkInfo(
        label = "抖音极速版",
        versionName = "37.8.0",
        packageName = "com.ss.android.ugc.aweme.lite",
        versionCode = 370801,
        size = size,
        signatures = "V1 + V2 + V3",
        hardening = "未检测到加固",
        targetSdk = 34,
        minSdk = 21,
        installedVersion = if (installed) "41.1.0 (410101)" else null,
        dataDir = "/data/user/0/com.ss.android.ugc.aweme.lite",
        externalDataDir = "/storage/emulated/0/Android/data/com.ss.android.ugc.aweme.lite",
        apkPath = "/storage/emulated/0/Download/抖音极速版_37.8.0.apk",
        installedPath = if (installed) "/data/app/~~19LUD26vwSEvJrU/base.apk" else null,
        firstInstall = if (installed) 1_760_000_000_000 else null,
        lastUpdate = if (installed) 1_760_000_000_000 else null,
        uid = if (installed) 10794 else null,
        bundleNote = bundleNote,
        localApkPath = "/data/user/0/com.u707t.panelfm/cache/apk/pkg-abc.apk",
    )

    private fun rowsOf(info: ApkInfo): Map<String, String> =
        ApkInfoRows.of(info).toMap()

    @Test
    fun `行顺序与 MT 一致`() {
        assertEquals(
            listOf(
                "包名",
                "版本号",
                "安装包大小",
                "签名状态",
                "加固状态",
                "目标系统",
                "最低系统",
                "已安装",
                "数据目录 1",
                "数据目录 2",
                "APK 路径",
                "已安装路径",
                "首次安装",
                "最后更新",
                "UID",
            ),
            ApkInfoRows.of(sample()).map { it.first },
        )
    }

    @Test
    fun `版本号一行是 versionCode，标题用的是 versionName`() {
        val info = sample()
        assertEquals("37.8.0", info.versionName)
        assertEquals("370801", rowsOf(info)["版本号"])
    }

    @Test
    fun `系统版本带 Android 名与 API`() {
        val rows = rowsOf(sample())
        assertEquals("Android 14 (API 34)", rows["目标系统"])
        assertEquals("Android 5.0 (API 21)", rows["最低系统"])
    }

    @Test
    fun `大小用 MT 的紧凑写法（不带空格）`() {
        assertEquals("5.00M", rowsOf(sample(size = 5L * 1024 * 1024))["安装包大小"])
        assertEquals("512B", rowsOf(sample(size = 512))["安装包大小"])
    }

    @Test
    fun `已安装时给出已安装版本、路径、时间与 UID`() {
        val rows = rowsOf(sample())
        assertEquals("41.1.0 (410101)", rows["已安装"])
        assertEquals("/data/app/~~19LUD26vwSEvJrU/base.apk", rows["已安装路径"])
        assertEquals("10794", rows["UID"])
        assertTrue(rows.containsKey("首次安装"))
        assertTrue(rows.containsKey("最后更新"))
    }

    @Test
    fun `未安装时只留一行「未安装」，不显示时间与 UID`() {
        val rows = rowsOf(sample(installed = false))
        assertEquals("未安装", rows["已安装"])
        assertFalse("未安装不该显示已安装路径", rows.containsKey("已安装路径"))
        assertFalse("未安装不该显示首次安装", rows.containsKey("首次安装"))
        assertFalse("未安装不该显示最后更新", rows.containsKey("最后更新"))
        assertFalse("未安装不该显示 UID", rows.containsKey("UID"))
        // 数据目录仍然显示（按包名拼出来的，用户要复制去查）
        assertEquals("/data/user/0/com.ss.android.ugc.aweme.lite", rows["数据目录 1"])
    }

    @Test
    fun `签名与加固状态原样展示`() {
        val rows = rowsOf(sample())
        assertEquals("V1 + V2 + V3", rows["签名状态"])
        assertEquals("未检测到加固", rows["加固状态"])
    }

    @Test
    fun `APK 路径是文件本身，不是安装位置`() {
        val rows = rowsOf(sample())
        assertEquals("/storage/emulated/0/Download/抖音极速版_37.8.0.apk", rows["APK 路径"])
    }
}
