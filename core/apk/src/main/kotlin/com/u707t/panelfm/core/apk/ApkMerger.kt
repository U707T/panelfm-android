package com.u707t.panelfm.core.apk

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 把 base + 分包并成一个 APK。
 *
 * 合并口径（**不做**资源表合并，见 docs/APKS-TO-APK.md）：
 *  - base：全部条目照搬，`AndroidManifest.xml` 换成打过补丁的版本，旧签名文件丢掉；
 *  - 分包：只并 `lib/`（原生库）、`assets/`、`classes*.dex`（改名接续 `classesN.dex`）等**与资源表无关**的条目；
 *    `res/`、`resources.arsc`、`AndroidManifest.xml` 一律不并 —— 密度 / 语言分包的资源在运行时是按
 *    各自的 `resources.arsc` 挂上去的，简单拼文件不会被资源管理器认到，反而白占体积。
 *  - 同名条目以 base 为准（分包里的是重复拷贝）。
 */
internal object ApkMerger {

    data class Stats(
        val writtenEntries: Int,
        val libEntries: Int,
        val dexEntries: Int,
        val manifestChanges: List<String>,
    )

    private val SIGNATURE_FILES =
        Regex("^META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))$", RegexOption.IGNORE_CASE)

    private val DEX_NAME = Regex("^classes(\\d*)\\.dex$")

    fun merge(
        baseApk: File,
        splits: List<File>,
        keptAbis: Set<String>?,
        output: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Stats {
        val total = baseApk.length() + splits.sumOf { it.length() }
        var done = 0L
        var written = 0
        var libs = 0
        var dexes = 0
        val writtenNames = HashSet<String>()

        output.parentFile?.mkdirs()
        ZipFile(baseApk).use { baseZip ->
            val manifestEntry = baseZip.getEntry("AndroidManifest.xml")
                ?: error("基础包缺少 AndroidManifest.xml")
            val manifestBytes = baseZip.getInputStream(manifestEntry).use { it.readBytes() }
            val patch = ApkManifestPatcher.patchForStandalone(manifestBytes)
            val info = runCatching { ApkManifestReader.parse(manifestBytes) }.getOrNull()
            // extractNativeLibs=false 时 .so 必须不压缩（系统直接 mmap APK 里的库）
            val storeLibs = info?.extractNativeLibs == false
            var dexNext = nextDexIndex(baseZip)

            ZipOutputStream(BufferedOutputStream(FileOutputStream(output), 512 * 1024)).use { zos ->
                for (entry in baseZip.entries()) {
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (SIGNATURE_FILES.matches(name)) continue
                    if (!abiAllowed(name, keptAbis)) continue
                    writtenNames += name
                    written++
                    if (name == "AndroidManifest.xml") {
                        writeBytes(zos, name, entry.time, patch.bytes, store = false) { done += it; onProgress(done, total) }
                    } else {
                        val store = storeLibs && isNativeLib(name)
                        if (store) libs++
                        writeFromZip(zos, baseZip, entry, name, store) { done += it; onProgress(done, total) }
                    }
                }

                for (split in splits) {
                    ZipFile(split).use { splitZip ->
                        for (entry in splitZip.entries()) {
                            if (entry.isDirectory) continue
                            val name = entry.name
                            if (name == "AndroidManifest.xml" || name == "resources.arsc") continue
                            if (name.startsWith("res/")) continue
                            if (name.startsWith("META-INF/") && !name.startsWith("META-INF/services/")) continue
                            if (!abiAllowed(name, keptAbis)) continue
                            // 注意 1 号必须是 classes.dex：类加载器只认 classes.dex / classesN.dex（N≥2），
                            // 生成 classes1.dex 等于把这份 dex 丢掉（base 无代码、代码全在功能模块时会出现）
                            val target = if (DEX_NAME.matches(name)) {
                                val index = dexNext++
                                if (index <= 1) "classes.dex" else "classes$index.dex"
                            } else {
                                name
                            }
                            if (target in writtenNames) continue
                            writtenNames += target
                            written++
                            if (DEX_NAME.matches(name)) dexes++
                            val store = storeLibs && isNativeLib(target)
                            if (store) libs++
                            writeFromZip(zos, splitZip, entry, target, store) {
                                done += it
                                onProgress(done, total)
                            }
                        }
                    }
                }
            }
            return Stats(written, libs, dexes, patch.changes)
        }
    }

    private fun isNativeLib(name: String): Boolean = name.startsWith("lib/") && name.endsWith(".so")

    private val ABI_FOLDERS = setOf(
        "armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64", "mips", "mips64", "riscv64",
    )

    private fun abiAllowed(name: String, keptAbis: Set<String>?): Boolean {
        if (keptAbis == null || !name.startsWith("lib/")) return true
        val abi = name.substringAfter("lib/").substringBefore('/')
        if (abi !in ABI_FOLDERS) return true
        return abi in keptAbis
    }

    private fun nextDexIndex(zip: ZipFile): Int {
        var max = 1
        for (entry in zip.entries()) {
            val index = dexIndex(entry.name) ?: continue
            if (index >= max) max = index + 1
        }
        return max
    }

    private fun dexIndex(name: String): Int? {
        val match = DEX_NAME.matchEntire(name) ?: return null
        val digits = match.groupValues[1]
        return if (digits.isEmpty()) 1 else digits.toIntOrNull()
    }

    private fun writeFromZip(
        zos: ZipOutputStream,
        zip: ZipFile,
        entry: ZipEntry,
        targetName: String,
        store: Boolean,
        onBytes: (Long) -> Unit,
    ) {
        val time = if (entry.time > 0) entry.time else 0L
        if (!store) {
            zos.putNextEntry(ZipEntry(targetName).apply { method = ZipEntry.DEFLATED; this.time = time })
            zip.getInputStream(entry).use { input -> pump(input, zos, onBytes) }
            zos.closeEntry()
            return
        }
        // STORED 必须先知道 size / CRC：先扫一遍再写一遍（.so 不会太大，两遍比读进内存稳）
        var size = 0L
        val crc = CRC32()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                size += n
            }
        }
        zos.putNextEntry(
            ZipEntry(targetName).apply {
                method = ZipEntry.STORED
                this.size = size
                compressedSize = size
                this.crc = crc.value
                this.time = time
            },
        )
        zip.getInputStream(entry).use { input -> pump(input, zos, onBytes) }
        zos.closeEntry()
    }

    private fun writeBytes(
        zos: ZipOutputStream,
        name: String,
        time: Long,
        bytes: ByteArray,
        store: Boolean,
        onBytes: (Long) -> Unit,
    ) {
        val entry = ZipEntry(name).apply {
            method = if (store) ZipEntry.STORED else ZipEntry.DEFLATED
            if (store) {
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().also { it.update(bytes) }.value
            }
            if (time > 0) this.time = time
        }
        zos.putNextEntry(entry)
        ByteArrayInputStream(bytes).use { pump(it, zos, onBytes) }
        zos.closeEntry()
    }

    private fun pump(input: InputStream, zos: ZipOutputStream, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            zos.write(buffer, 0, n)
            onBytes(n.toLong())
        }
    }
}
