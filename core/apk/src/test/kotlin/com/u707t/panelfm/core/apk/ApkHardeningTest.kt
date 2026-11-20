package com.u707t.panelfm.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加固检测（MT 的「加固状态」）：特征库匹配，两条信号 ——
 *  1. 包内条目名（`lib/arm64-v8a/libjiagu_a64.so` 之类）；
 *  2. 清单里 `<application android:name>` 的壳入口类。
 *
 * 这里只测「命中 / 不命中 / 不误报」，不追求覆盖全部厂商（表会长期增删）。
 */
class ApkHardeningTest {

    @Test
    fun `干净包 = 未检测到加固`() {
        val entries = listOf(
            "AndroidManifest.xml",
            "classes.dex",
            "resources.arsc",
            "lib/arm64-v8a/libflutter.so",
            "META-INF/CERT.SF",
        )
        assertNull(ApkHardening.detect(entries))
        assertNull(ApkHardening.detect(entries, "com.example.MyApp"))
        assertEquals("未检测到加固", ApkHardening.NONE_LABEL)
    }

    @Test
    fun `360 加固：libjiagu 命中`() {
        val hit = ApkHardening.detect(
            listOf("AndroidManifest.xml", "lib/armeabi-v7a/libjiagu.so", "classes.dex"),
        )
        assertEquals("360", hit?.vendor)
        assertEquals("lib/armeabi-v7a/libjiagu.so", hit?.evidence)
        assertEquals("360 加固", hit?.label())
    }

    @Test
    fun `腾讯乐固：libshella 命中（大小写不敏感）`() {
        val hit = ApkHardening.detect(listOf("lib/arm64-v8a/libshella-2.10.6.6.so"))
        assertEquals("腾讯", hit?.vendor)
        assertEquals("腾讯 加固", hit?.label())
    }

    @Test
    fun `梆梆：libDexHelper 命中`() {
        assertEquals("梆梆", ApkHardening.detect(listOf("lib/armeabi/libDexHelper.so"))?.vendor)
    }

    @Test
    fun `爱加密：assets 里的 ijiami 标记命中`() {
        assertEquals("爱加密", ApkHardening.detect(listOf("assets/ijiami.ajm"))?.vendor)
    }

    @Test
    fun `壳入口类也能判定（没有特征 so 时）`() {
        val entries = listOf("AndroidManifest.xml", "classes.dex")
        assertEquals("360", ApkHardening.detect(entries, "com.stub.StubApp")?.vendor)
        assertEquals("梆梆", ApkHardening.detect(entries, "com.secneo.apkwrapper.ApplicationWrapper")?.vendor)
        assertEquals("腾讯", ApkHardening.detect(entries, "com.tencent.StubShell.TxAppEntry")?.vendor)
        // 证据保留原始类名（大小写敏感的原样），方便用户复核
        assertEquals("com.stub.StubApp", ApkHardening.detect(entries, "com.stub.StubApp")?.evidence)
    }

    @Test
    fun `so 命中优先于 Application 类（前者证据更硬）`() {
        val hit = ApkHardening.detect(listOf("lib/arm64-v8a/libjiagu_a64.so"), "com.example.App")
        assertEquals("360", hit?.vendor)
    }

    @Test
    fun `相似但不同的库名不误报`() {
        // libjiagu 是 360 的特征，libjiguard 不是
        assertNull(ApkHardening.detect(listOf("lib/arm64-v8a/libjiguard.so")))
        // 普通的 libexec.so 只有完整文件名才命中（这里是 libexec_helper.so）
        assertNull(ApkHardening.detect(listOf("lib/arm64-v8a/libexec_helper.so")))
    }

    @Test
    fun `Application 类只是前缀相似不误报`() {
        assertNull(ApkHardening.detect(listOf("classes.dex"), "com.stub.StubHelper"))
        // 前缀命中即可（壳类后面常带内部子类）
        assertTrue(ApkHardening.detect(listOf("classes.dex"), "com.qihoo.util.StubApplication\$1") != null)
    }
}
