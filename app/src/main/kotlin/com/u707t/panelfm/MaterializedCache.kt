package com.u707t.panelfm

import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.sha256Hex
import com.u707t.panelfm.core.common.sweepStaleFiles
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.copyTo
import java.io.File
import java.io.OutputStream

/**
 * 「远端 / 包内文件先落盘，再交给只认文件路径的系统 API」的**唯一实现**。
 *
 * 用它的两处：APK 解析（`PackageManager.getPackageArchiveInfo`）、
 * PDF 渲染（`PdfRenderer` 只接受文件描述符）。两处原本各写一份，连缓存目录、
 * 键的算法、`.part` 中转、3 天清理、失败删残片都一模一样 —— 改一处忘另一处就会出现
 * 「PDF 清了、APK 没清」这种存储投诉。
 *
 * 约定：
 *  - 本地文件**不复制**，直接返回原路径（零成本、也不会和用户的原文件脱节）；
 *  - 缓存键 = URI 的 SHA-256 前 16 个 hex 字符 + 大小（旧实现用 `String.hashCode()`，
 *    32 位撞哈希会打开别人的缓存文件）；
 *  - 先写 `.part` 再 `rename`：中途被杀不会留下一个「看起来命中缓存」的半截文件；
 *  - 每次未命中顺手清理 3 天没碰过的旧缓存（见 [sweepStaleFiles]）。
 *
 * @param subdir 缓存子目录（`apk` / `pdf`）
 * @param prefix 文件名前缀（`pkg` / `doc`，便于人工辨认）
 * @param extension 落地扩展名（不带点）
 * @param onStage 进度文案（可选；调用方负责切回主线程再写 UI 状态）
 */
internal suspend fun materializeToCache(
    container: AppContainer,
    uri: VfsUri,
    size: Long,
    subdir: String,
    prefix: String,
    extension: String,
    onStage: (String) -> Unit = {},
): File {
    if (uri.scheme == "local") {
        val path = runCatching { container.localVfs.absolutePath(uri) }.getOrNull()
        if (path != null && File(path).isFile) return File(path)
    }
    val dir = File(container.appDirs.cacheDir, subdir).apply { runCatching { mkdirs() } }
    val key = sha256Hex(uri.toString().toByteArray(), takeBytes = 8)
    val target = File(dir, "$prefix-$key-$size.$extension")
    if (target.isFile && target.length() > 0) return target
    sweepStaleFiles(dir, keep = target)

    onStage("正在缓存到本地（${Fmt.sizeCompact(size)}）…")
    val vfs = container.resolveSession(uri)
        ?: throw IllegalStateException("会话不可用（存储已断开）")
    writeAtomically(target) { out -> vfs.openRead(uri).use { it.copyTo(out) } }
    return target
}

/**
 * 先写 `<目标>.part`、成功后再 `rename` —— **缓存落盘的唯一写法**。
 *
 * 为什么必须原子：中途被杀 / 取消时留下半截文件，下次会因为「目标已存在且长度 > 0」
 * 直接命中缓存，用户看到的是**截断的包**（解析失败或更糟：装上一个坏 APK）。
 * 失败与取消都要把 `.part` 删掉，否则残片会一直占着存储。
 */
internal suspend fun writeAtomically(target: File, block: suspend (OutputStream) -> Unit) {
    val tmp = File(target.parentFile, "${target.name}.part")
    try {
        tmp.outputStream().use { block(it) }
    } catch (t: Throwable) {
        runCatching { tmp.delete() }
        throw t
    }
    if (!tmp.renameTo(target)) {
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
    }
}
