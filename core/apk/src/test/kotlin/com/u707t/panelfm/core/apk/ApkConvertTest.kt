package com.u707t.panelfm.core.apk

import com.android.apksig.ApkVerifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkConvertTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun standardApks(name: String = "demo.apks"): File = bundleOf(
        File(tmp.root, name),
        mapOf(
            "splits/base-master.apk" to mapOf(
                "AndroidManifest.xml" to Fixtures.baseManifest,
                "classes.dex" to dummy(120),
                "resources.arsc" to dummy(30, seed = 1),
                "lib/arm64-v8a/libbase.so" to dummy(64),
                "META-INF/CERT.SF" to dummy(8),
                "META-INF/MANIFEST.MF" to dummy(8),
            ),
            "splits/base-arm64_v8a.apk" to mapOf(
                "AndroidManifest.xml" to Fixtures.abiManifest,
                "lib/arm64-v8a/libfoo.so" to dummy(48),
                "META-INF/CERT.RSA" to dummy(8),
            ),
            "splits/base-xxhdpi.apk" to mapOf(
                "AndroidManifest.xml" to Fixtures.densityManifest,
                "res/drawable-xxhdpi/icon.png" to dummy(24),
                "resources.arsc" to dummy(16, seed = 9),
            ),
            "splits/feature1-master.apk" to mapOf(
                "AndroidManifest.xml" to Fixtures.featureManifest,
                "classes.dex" to dummy(40),
                "assets/feature.bin" to dummy(12),
            ),
        ),
    )

    private fun service(): ApksToApk = ApksToApk(
        workRoot = tmp.newFolder("work-${System.nanoTime()}"),
        signingKeyDir = tmp.newFolder("keys-${System.nanoTime()}"),
    )

    private fun entriesOf(file: File): Map<String, ZipEntry> = ZipFile(file).use { zip ->
        java.util.Collections.list(zip.entries()).associateBy { it.name }
    }

    private fun manifestOf(file: File): ApkManifestInfo = ZipFile(file).use { zip ->
        ApkManifestReader.parse(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() })
    }

    private fun assertSigned(file: File) {
        val result = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(26).build().verify()
        assertTrue("签名校验失败：${result.errors}", result.isVerified)
        assertTrue("缺少 v2 签名", result.isVerifiedUsingV2Scheme)
    }

    @Test
    fun `base 加 ABI 分包合并后能装（签名可校验 清单已改）`() {
        val apks = standardApks()
        val service = service()
        val bundle = service.inspect(apks)
        val plan = service.plan(bundle, ConvertOptions(abis = setOf("arm64-v8a")))
        assertEquals(1, plan.merging.size)
        assertEquals(1, plan.skippedRes.size)
        assertEquals(1, plan.skippedFeature.size)
        assertTrue(plan.needsMerge)

        val output = File(tmp.root, "demo.apk")
        val result = service.convert(plan, output) {}

        assertFalse(result.direct)
        assertEquals("com.example.app", result.packageName)
        assertEquals("2.1", result.versionName)
        assertEquals(1, result.mergedSplits)
        assertEquals(1, result.skippedResSplits)
        assertEquals(1, result.skippedFeatureSplits)

        val entries = entriesOf(output)
        assertTrue(entries.containsKey("classes.dex"))
        assertTrue(entries.containsKey("lib/arm64-v8a/libbase.so"))
        assertTrue("ABI 分包里的 .so 必须并进来", entries.containsKey("lib/arm64-v8a/libfoo.so"))
        assertFalse("密度分包的 res 不该并", entries.containsKey("res/drawable-xxhdpi/icon.png"))
        assertTrue("base 的 resources.arsc 必须保留", entries.containsKey("resources.arsc"))
        assertArrayEquals(
            "分包自己的 resources.arsc 不该覆盖 base",
            dummy(30, seed = 1),
            ZipFile(output).use { it.getInputStream(it.getEntry("resources.arsc")).use { s -> s.readBytes() } },
        )
        assertFalse(entries.containsKey("META-INF/CERT.SF"))
        assertFalse(entries.containsKey("META-INF/CERT.RSA"))
        assertFalse(entries.containsKey("META-INF/MANIFEST.MF"))
        assertFalse("功能模块默认不并", entries.containsKey("classes2.dex"))
        assertFalse(entries.containsKey("assets/feature.bin"))

        // extractNativeLibs=false → .so 必须不压缩
        assertEquals(ZipEntry.STORED, entries.getValue("lib/arm64-v8a/libfoo.so").method)

        val manifest = manifestOf(output)
        assertFalse(manifest.isSplitRequired)
        assertNull(manifest.requiredSplitTypes)
        assertTrue(manifest.usesSplits.isEmpty())
        assertEquals("com.example.app", manifest.packageName)

        assertSigned(output)
    }

    @Test
    fun `勾上功能模块时它的 dex 会改名接续`() {
        val apks = standardApks()
        val service = service()
        val plan = service.plan(
            service.inspect(apks),
            ConvertOptions(abis = setOf("arm64-v8a"), includeFeatureSplits = true),
        )
        val output = File(tmp.root, "demo-feature.apk")
        service.convert(plan, output) {}

        val entries = entriesOf(output)
        assertTrue(entries.containsKey("classes.dex"))
        assertTrue("功能模块的 dex 要接成 classes2.dex", entries.containsKey("classes2.dex"))
        assertTrue(entries.containsKey("assets/feature.bin"))
        assertSigned(output)
    }

    @Test
    fun `base 没有 dex 时功能模块的 dex 必须叫 classes_dex`() {
        // 类加载器只认 classes.dex / classesN.dex（N≥2）；生成 classes1.dex 等于把这份代码丢掉
        val apks = bundleOf(
            File(tmp.root, "nodex.apks"),
            mapOf(
                "splits/base-master.apk" to mapOf("AndroidManifest.xml" to Fixtures.baseManifest),
                "splits/feature1-master.apk" to mapOf(
                    "AndroidManifest.xml" to Fixtures.featureManifest,
                    "classes.dex" to dummy(30),
                ),
            ),
        )
        val service = service()
        val plan = service.plan(service.inspect(apks), ConvertOptions(includeFeatureSplits = true))
        val output = File(tmp.root, "nodex.apk")
        service.convert(plan, output) {}
        val entries = entriesOf(output)
        assertTrue(entries.containsKey("classes.dex"))
        assertFalse(entries.containsKey("classes1.dex"))
    }

    @Test
    fun `ABI 白名单会同时过滤 base 自带的多余 ABI`() {
        val apks = standardApks("abi.apks")
        val service = service()
        val plan = service.plan(service.inspect(apks), ConvertOptions(abis = setOf("armeabi-v7a")))
        assertEquals(0, plan.merging.size)
        assertEquals(1, plan.skippedAbi.size)
        val output = File(tmp.root, "demo-abi.apk")
        service.convert(plan, output) {}
        val entries = entriesOf(output)
        assertFalse(entries.containsKey("lib/arm64-v8a/libbase.so"))
        assertFalse(entries.containsKey("lib/arm64-v8a/libfoo.so"))
        assertSigned(output)
    }

    @Test
    fun `universal 包直接取出 不合并不重签`() {
        val universalBytes = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(Fixtures.baseManifest)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(dummy(32))
                zip.closeEntry()
            }
        }.toByteArray()
        val apks = bundleOf(
            File(tmp.root, "universal.apks"),
            mapOf("universal.apk" to mapOf("AndroidManifest.xml" to Fixtures.baseManifest)),
        )
        // 手工替换成真正的 universal.apk 字节
        val real = File(tmp.root, "universal-real.apks")
        java.io.BufferedOutputStream(real.outputStream()).use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("universal.apk"))
                zip.write(universalBytes)
                zip.closeEntry()
            }
        }
        assertTrue(apks.exists())

        val service = service()
        val bundle = service.inspect(real)
        assertNotNull(bundle.universal)
        val plan = service.plan(bundle, ConvertOptions())
        val output = File(tmp.root, "direct.apk")
        val result = service.convert(plan, output) {}
        assertTrue(result.direct)
        assertArrayEquals(universalBytes, output.readBytes())
    }

    @Test
    fun `签名材料只生成一次并且可复用`() {
        val dir = tmp.newFolder("keys-reuse")
        val store = ApkSigningKeyStore(dir)
        val first = store.loadOrCreate()
        val second = store.loadOrCreate()
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
        assertArrayEquals(first.privateKey.encoded, second.privateKey.encoded)
        assertTrue(dir.listFiles().orEmpty().any { it.name.endsWith(".p12") })
        assertTrue(first.certificate.subjectX500Principal.name.contains("CN=PanelFM Test"))
    }
}
