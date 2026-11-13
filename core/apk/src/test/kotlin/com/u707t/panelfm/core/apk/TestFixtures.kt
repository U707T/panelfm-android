package com.u707t.panelfm.core.apk

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 测试用的真实 AXML（aapt2 link 产物，见 `src/test/resources`） */
internal object Fixtures {

    fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "找不到测试资源 $name" }
            .use { it.readBytes() }

    val baseManifest: ByteArray get() = bytes("manifest-base.bin")
    val abiManifest: ByteArray get() = bytes("manifest-abi.bin")
    val densityManifest: ByteArray get() = bytes("manifest-dpi.bin")
    val featureManifest: ByteArray get() = bytes("manifest-feat.bin")
}

internal fun zipOf(file: File, entries: Map<String, ByteArray>): File {
    file.parentFile?.mkdirs()
    java.io.BufferedOutputStream(file.outputStream()).use { out ->
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }
    return file
}

/** 造一个「.apks 外壳」：里面放若干 APK */
internal fun bundleOf(file: File, apks: Map<String, Map<String, ByteArray>>): File {
    file.parentFile?.mkdirs()
    java.io.BufferedOutputStream(file.outputStream()).use { out ->
        ZipOutputStream(out).use { zos ->
            apks.forEach { (path, entries) ->
                val buffer = ByteArrayOutputStream()
                ZipOutputStream(buffer).use { inner ->
                    entries.forEach { (name, data) ->
                        inner.putNextEntry(ZipEntry(name))
                        inner.write(data)
                        inner.closeEntry()
                    }
                }
                zos.putNextEntry(ZipEntry(path))
                zos.write(buffer.toByteArray())
                zos.closeEntry()
            }
        }
    }
    return file
}

internal fun dummy(size: Int, seed: Byte = 7): ByteArray = ByteArray(size) { (it + seed).toByte() }
