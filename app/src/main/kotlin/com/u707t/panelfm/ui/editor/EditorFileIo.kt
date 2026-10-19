package com.u707t.panelfm.ui.editor

import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.TextEncodings
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/*
 * 编辑器文件层的读写基础设施（与 Compose 无关，可单测）。
 *
 * v2.0.16 起**不再有只读分段浏览**：文本文件一律整篇读进编辑器（可编辑，NP / MT 同款），
 * 大文件靠两层兜底——
 *  1. 超过 [AUTO_FLUENT_BYTES] 自动开「流畅模式」（关语法高亮 / 当前行 / 括号高亮）；
 *  2. 超过 [OPEN_CONFIRM_BYTES] 先确认；超过 [OPEN_LIMIT_BYTES] 直接拒绝（保护内存）。
 * 判定收敛在纯函数 [editorOpenPlan] 里（回归测试 `EditorOpenPlanTest`）。
 */

/** 超过这个大小自动开启流畅模式（MT 的「自动开启流畅模式阀值」同款语义） */
internal const val AUTO_FLUENT_BYTES = 2L * 1024 * 1024

/** 超过这个大小，打开前先确认（大文件可能占内存 / 卡一下） */
internal const val OPEN_CONFIRM_BYTES = 16L * 1024 * 1024

/** 打开上限：超过直接拒绝（整篇进内存 + sora 的文档模型，再大就有 OOM 风险） */
internal const val OPEN_LIMIT_BYTES = 64L * 1024 * 1024

/** 语法高亮字符上限（超过则退化为纯文本，保证输入流畅） */
internal const val HL_MAX_CHARS = 640_000

/** 打开大文件时的处理档位。 */
internal enum class EditorOpenDecision { OPEN, CONFIRM, REFUSE }

/** [editorOpenPlan] 的结论：[autoFluent] = 这次打开要不要自动开流畅模式。 */
internal data class EditorOpenPlan(val decision: EditorOpenDecision, val autoFluent: Boolean)

/**
 * 按文件大小决定「怎么打开」（纯函数，可单测；阈值见上面三个常量）。
 *
 * 为什么不再只读分段：分段浏览对用户来说等于「大文件打不开 / 改不了」，
 * NP 与 MT 都是直接进编辑器（大文件关高亮 + 手动「流畅模式」），这里对齐它们；
 * 内存保护只留一条很粗的线（[OPEN_LIMIT_BYTES]），而不是 2 MB 就锁死。
 */
internal fun editorOpenPlan(size: Long): EditorOpenPlan = when {
    size > OPEN_LIMIT_BYTES -> EditorOpenPlan(EditorOpenDecision.REFUSE, autoFluent = false)
    size > OPEN_CONFIRM_BYTES -> EditorOpenPlan(EditorOpenDecision.CONFIRM, autoFluent = true)
    size > AUTO_FLUENT_BYTES -> EditorOpenPlan(EditorOpenDecision.OPEN, autoFluent = true)
    else -> EditorOpenPlan(EditorOpenDecision.OPEN, autoFluent = false)
}

/**
 * 读至多 [max] 字节；**流里还有更多数据时返回 null**（不能把截断文本当全文）。
 *
 * 为什么返回值必须是可空：调用方（[readWholeText]）会把它当「整篇文本」灌进编辑器，
 * 用户改完一保存就把大文件写成了前 64 MB —— 静默数据丢失。多读 1 个字节即可区分
 * 「正好 max」与「超过 max」，比事后比较文件大小可靠（远端 VFS 的 size 可能是 -1 / 会变动）。
 */
internal suspend fun readAtMost(
    vfs: VirtualFileSystem,
    uri: VfsUri,
    max: Long,
): ByteArray? = withContext(Dispatchers.IO) {
    val reader = vfs.openRead(uri)
    try {
        readUpTo(max) { buf, off, len -> reader.read(buf, off, len) }
    } finally {
        runCatching { reader.close() }
    }
}

/**
 * [readAtMost] 的纯读取逻辑（用 lambda 代替流，便于单测）：
 * 循环直到读完 / 超过 [max]；超过返回 null。
 *
 * 实现已收敛到 `core.common.readBounded`（预览 / EPUB 也用它，三处不再各写一份）。
 */
internal suspend fun readUpTo(max: Long, read: suspend (ByteArray, Int, Int) -> Int): ByteArray? =
    com.u707t.panelfm.core.common.readBounded(max, read)

/** 保存结果（v2.0.15：保存路径从「回调里拼文案」改为「返回结构 + 纯函数出文案」，便于单测）。 */
internal data class SaveOutcome(
    val ok: Boolean,
    /** 实际写入的编码（无法表示时可能已回退 UTF-8） */
    val charset: String = "",
    val bytes: Int = 0,
    /** 「保存时自动 .bak 备份」的备份文件名；未开启 / 失败为 null */
    val backupName: String? = null,
    val backupFailed: Boolean = false,
    val error: String? = null,
)

/**
 * 保存：[text] 按 [charset] 编码写回（BOM 重建 / 无法表示字符回退见 `TextEncodings.encode`），
 * 备份先做且成败都进返回结果；会话断开时自动重连一次（与其余入口一致）。
 *
 * 只回结果、不碰 UI：状态栏文案由 [saveStatusText] 出（纯函数，可单测）。
 */
internal suspend fun saveText(
    container: AppContainer,
    uri: VfsUri,
    text: String,
    charset: String,
    originalMode: Int?,
): SaveOutcome {
    return try {
        val vfs = container.resolveSession(uri) ?: throw IllegalStateException("会话不可用")
        val encoded = TextEncodings.encode(text, charset)
        val bytes = encoded.bytes
        // MT「保存文件时自动将原文件重命名为 .bak 备份文件」：备份先做，且必须能看见成败
        val backupEnabled = container.settings.value.backupOnSave
        val backupName = if (backupEnabled) backupBeforeSave(container, vfs, uri) else null
        withContext(Dispatchers.IO) {
            val writer = vfs.openWrite(uri, size = bytes.size.toLong(), offset = 0L)
            try {
                writer.write(bytes, 0, bytes.size)
                writer.commit()
            } catch (e: Exception) {
                // 失败必须收尾：FTP 不清理会连控制锁一起泄漏（会话死锁）；WebDAV / S3 会遗留
                // .part / 未完成分片；SFTP / SMB 会泄漏句柄。abort 只清临时物、不动目标文件。
                runCatching { writer.abort() }
                throw e
            } finally {
                runCatching { writer.close() }
            }
            // 保留原有权限（本地/SFTP/FTP 支持 chmod 时）
            if (originalMode != null && vfs.capabilities.permissions) {
                runCatching { vfs.setPermissions(uri, originalMode) }
            }
        }
        SaveOutcome(
            ok = true,
            charset = encoded.charset,
            bytes = bytes.size,
            backupName = backupName,
            backupFailed = backupEnabled && backupName == null,
        )
    } catch (e: Exception) {
        SaveOutcome(ok = false, error = e.message ?: "未知错误")
    }
}

/** 保存结果 → 状态栏文案（纯函数；「转存自 …」「备份失败」都由这里说清楚）。 */
internal fun saveStatusText(outcome: SaveOutcome, requestedCharset: String): String {
    if (!outcome.ok) return "保存失败：${outcome.error}"
    val transcoded = if (outcome.charset != requestedCharset) "·转存自 $requestedCharset" else ""
    val backupNote = when {
        outcome.backupName != null -> "，原文件已备份为 ${outcome.backupName}"
        outcome.backupFailed -> "；⚠️ 备份失败（本次未生成备份）"
        else -> ""
    }
    return "已保存（${outcome.charset}$transcoded，${outcome.bytes} 字节$backupNote）"
}

/**
 * 整篇读取（首次装载 / 重新加载）。
 *
 * [charset] == null → 走 `TextEncodings.decode` 的自动识别；
 * 指定编码（NP 的「指定编码重新加载」）时**不猜**，直接按该编码解（BOM 存在则剥离）。
 *
 * **超过 [max] 返回 null**（打开时会再报「文件过大」，见 [editorOpenPlan]）——
 * 宁可明确拒绝，也不能把截断的前 N MB 当全文交给用户编辑。
 */
internal suspend fun readWholeText(
    vfs: VirtualFileSystem,
    uri: VfsUri,
    charset: String?,
    max: Long = OPEN_LIMIT_BYTES,
): TextEncodings.Decoded? = withContext(Dispatchers.IO) {
    val bytes = readAtMost(vfs, uri, max) ?: return@withContext null
    if (charset == null) TextEncodings.decode(bytes)
    else TextEncodings.Decoded(charset, TextEncodings.decodeWith(charset, bytes))
}

/**
 * 保存前判定「文件是否已被外部改动」（纯函数，可单测）。
 *
 * 口径（宁少勿误报）：[now] == null（文件被删除 / 不可达）算改动；
 * 大小两边都已知且不同算改动；修改时间两边都已知（> 0）且不同算改动——
 * 远端 VFS 常常给不出 mtime（-1），那种情况只看大小。
 */
internal fun fileChangedSince(before: FileMetadata, now: FileMetadata?): Boolean {
    if (now == null) return true
    if (before.size >= 0 && now.size >= 0 && before.size != now.size) return true
    if (before.lastModified > 0 && now.lastModified > 0 && before.lastModified != now.lastModified) return true
    return false
}

/** 保存前重新取一次元数据；取不到（已删除 / 断连）返回 null。 */
internal suspend fun statQuietly(container: AppContainer, uri: VfsUri): FileMetadata? = runCatching {
    withContext(Dispatchers.IO) {
        val vfs = container.resolveSession(uri) ?: return@withContext null
        vfs.stat(uri)
    }
}.getOrNull()

/**
 * 「保存前自动 .bak 备份」的实际实现。
 *
 * 复制到不冲突的 `.bak` / `.bak.1` / …（复用 [AppContainer.uniqueChild]），
 * 返回备份名；失败返回 null，由调用方在状态里明确提示。
 */
internal suspend fun backupBeforeSave(
    container: AppContainer,
    vfs: VirtualFileSystem,
    uri: VfsUri,
): String? {
    val parent = uri.parent ?: return null
    return runCatching {
        val dest = container.uniqueChild(parent, uri.name + ".bak")
        // 服务端复制优先（本地 / S3 是秒级）；不支持时退化为「读流 → 写流」
        if (vfs.capabilities.serverSideCopy && vfs.serverSideCopy(uri, dest)) {
            return@runCatching dest.name
        }
        val reader = vfs.openRead(uri)
        try {
            val writer = vfs.openWrite(dest, size = reader.size, offset = 0L)
            try {
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = reader.read(buf, 0, buf.size)
                    if (n < 0) break
                    writer.write(buf, 0, n)
                }
                writer.commit()
            } catch (e: Exception) {
                runCatching { writer.abort() }
                throw e
            }
        } finally {
            runCatching { reader.close() }
        }
        dest.name
    }.getOrNull()
}
