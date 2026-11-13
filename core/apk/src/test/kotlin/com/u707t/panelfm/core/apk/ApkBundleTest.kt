package com.u707t.panelfm.core.apk

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun standardApks(name: String = "demo.apks"): File = bundleOf(
        File(tmp.root, name),
        mapOf(
            "splits/base-master.apk" to mapOf(
                "AndroidManifest.xml" to Fixtures.baseManifest,
                "classes.dex" to dummy(120),
                "lib/arm64-v8a/libbase.so" to dummy(64),
            ),
            "splits/base-arm64_v8a.apk" to mapOf(
                "AndroidManifest.xml" to Fixtures.abiManifest,
                "lib/arm64-v8a/libfoo.so" to dummy(48),
            ),
            "splits/base-xxhdpi.apk" to mapOf(
                "AndroidManifest.xml" to Fixtures.densityManifest,
                "res/drawable-xxhdpi/icon.png" to dummy(24),
                "resources.arsc" to dummy(16),
            ),
            "splits/feature1-master.apk" to mapOf(
                "AndroidManifest.xml" to Fixtures.featureManifest,
                "classes.dex" to dummy(40),
                "assets/feature.bin" to dummy(12),
            ),
        ),
    )

    @Test
    fun `能认出基础包与各类分包`() {
        val bundle = ApkBundleReader.inspect(standardApks())
        assertNotNull(bundle.base)
        assertEquals("splits/base-master.apk", bundle.base?.path)
        assertEquals("com.example.app", bundle.base?.manifest?.packageName)
        assertEquals(3, bundle.splits.size)
        assertNull(bundle.universal)
        assertTrue(bundle.standalones.isEmpty())

        val byName = bundle.splits.associateBy { it.displayName }
        assertEquals(SplitKind.ABI, byName.getValue("base-arm64_v8a.apk").kind)
        assertEquals("arm64-v8a", byName.getValue("base-arm64_v8a.apk").abiFolder)
        assertEquals(SplitKind.DENSITY, byName.getValue("base-xxhdpi.apk").kind)
        assertEquals(SplitKind.FEATURE, byName.getValue("feature1-master.apk").kind)
    }

    @Test
    fun `universal apk 会被单独标出来`() {
        val file = bundleOf(
            File(tmp.root, "universal.apks"),
            mapOf(
                "universal.apk" to mapOf(
                    "AndroidManifest.xml" to Fixtures.baseManifest,
                    "classes.dex" to dummy(32),
                ),
            ),
        )
        val bundle = ApkBundleReader.inspect(file)
        assertNotNull(bundle.universal)
        assertEquals("universal.apk", bundle.universal?.path)
        assertNull(bundle.base)
        assertTrue(bundle.hasReadyMade)
    }

    @Test
    fun `standalones 按本机 ABI 挑，匹配不上就不算现成包`() {
        val file = bundleOf(
            File(tmp.root, "standalone.apks"),
            mapOf(
                "standalones/standalone-arm64_v8a_xxhdpi.apk" to mapOf("AndroidManifest.xml" to Fixtures.baseManifest),
                "standalones/standalone-armeabi_v7a_mdpi.apk" to mapOf("AndroidManifest.xml" to Fixtures.baseManifest),
            ),
        )
        val bundle = ApkBundleReader.inspect(file)
        assertEquals(2, bundle.standalones.size)
        val picked = ApkBundleReader.pickStandalone(bundle.standalones, "arm64-v8a", 480, requireAbiMatch = true)
        assertEquals("standalones/standalone-arm64_v8a_xxhdpi.apk", picked?.path)
        val none = ApkBundleReader.pickStandalone(bundle.standalones, "x86_64", 480, requireAbiMatch = true)
        assertNull(none)
    }

    @Test
    fun `xapk apkm 这类扁平结构也能认`() {
        val file = bundleOf(
            File(tmp.root, "app.xapk"),
            mapOf(
                "com.example.app.apk" to mapOf(
                    "AndroidManifest.xml" to Fixtures.baseManifest,
                    "classes.dex" to dummy(50),
                ),
                "split_config.arm64_v8a.apk" to mapOf(
                    "AndroidManifest.xml" to Fixtures.abiManifest,
                    "lib/arm64-v8a/libfoo.so" to dummy(20),
                ),
            ),
        )
        val bundle = ApkBundleReader.inspect(file)
        // manifest.json 不是 .apk，不会被当成安装包
        assertEquals("com.example.app.apk", bundle.base?.path)
        assertEquals(1, bundle.splits.size)
        assertEquals(SplitKind.ABI, bundle.splits.first().kind)
    }

    @Test
    fun `同一分包在多个 ABI 变体里的副本只算一次`() {
        // bundletool 真实产物：base-af.apk / base-af_2.apk / base-af_3.apk 内容相同
        val file = bundleOf(
            File(tmp.root, "dupes.apks"),
            mapOf(
                "splits/base-master.apk" to mapOf("AndroidManifest.xml" to Fixtures.baseManifest),
                "splits/base-af.apk" to mapOf("AndroidManifest.xml" to Fixtures.densityManifest),
                "splits/base-af_2.apk" to mapOf("AndroidManifest.xml" to Fixtures.densityManifest),
                "splits/base-af_3.apk" to mapOf("AndroidManifest.xml" to Fixtures.densityManifest),
            ),
        )
        val bundle = ApkBundleReader.inspect(file)
        assertEquals(1, bundle.splits.size)
        assertEquals("config.xxhdpi", bundle.splits.first().splitName)
    }

    @Test
    fun `ABI 按完整分词匹配 x86 不会命中 x86_64`() {
        // 子串匹配的经典坑：x86 是 x86_64 的前缀
        assertTrue(ApkBundleReader.pathMatchesAbi("standalone-x86_64_xxhdpi.apk", "x86_64"))
        assertTrue(ApkBundleReader.pathMatchesAbi("standalone-x86_mdpi.apk", "x86"))
        assertTrue(ApkBundleReader.pathMatchesAbi("standalone-arm64_v8a_xxhdpi.apk", "arm64_v8a"))
        assertFalse(ApkBundleReader.pathMatchesAbi("standalone-x86_64_xxhdpi.apk", "x86"))
        assertFalse(ApkBundleReader.pathMatchesAbi("standalone-armeabi_v7a_mdpi.apk", "armeabi"))
        assertFalse(ApkBundleReader.pathMatchesAbi("standalone-x86_mdpi.apk", "x86_64"))
        // 只有一份时也必须按同一规则判，不能退回「反正只有一个」直接放行
        val only = BundleApk("standalones/standalone-x86_64_xxhdpi.apk", 0, null)
        assertNull(ApkBundleReader.pickStandalone(listOf(only), "x86", 480, requireAbiMatch = true))
        assertEquals(only, ApkBundleReader.pickStandalone(listOf(only), "x86_64", 480, requireAbiMatch = true))
    }

    @Test
    fun `没有 APK 的压缩包给出明确错误`() {
        val file = zipOf(File(tmp.root, "empty.zip"), mapOf("readme.txt" to "x".toByteArray()))
        val error = runCatching { ApkBundleReader.inspect(file) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("没有 APK"))
    }

}
