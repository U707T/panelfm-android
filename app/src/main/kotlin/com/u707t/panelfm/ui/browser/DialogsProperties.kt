package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.DialogIcon
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 属性面板（复刻 MT）：名称 / 目录 / 类型 / 大小（含字节数）/ 修改时间 / 权限（drwxrws---(2770)）/
 * 所有者 / 用户组 / 文件数 / 文件夹数；「更多」展开位置、ETag、链接指向、可用空间与「复制路径」。
 * 打开时异步补全最新元数据；文件夹自动统计子项。
 */
@Composable
fun PropertiesDialog(container: com.u707t.panelfm.AppContainer, item: FileMetadata, onDismiss: () -> Unit) {
    val settings by container.settings.collectAsState()
    val clipboard = LocalClipboardManager.current
    var meta by remember(item.uri) { mutableStateOf(item) }
    var spaceText by remember(item.uri) { mutableStateOf<String?>(null) }
    var summary by remember(item.uri) { mutableStateOf<FolderSummary?>(null) }
    var more by remember(item.uri) { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        val vfs = container.locator.find(item.uri)
        val fresh = withContext(Dispatchers.IO) { runCatching { vfs?.stat(item.uri) }.getOrNull() }
        fresh?.let { meta = it }
        val isDir = fresh?.isDirectory ?: item.isDirectory
        val space = withContext(Dispatchers.IO) { runCatching { vfs?.space(item.uri) }.getOrNull() }
        spaceText = space?.let { "${Fmt.transferred(it.total - it.free, it.total)} / ${Fmt.size(it.total)}" }
        if (isDir) {
            summary = folderSummary(vfs, item.uri)
        }
    }

    val conn = container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(meta.uri))
        ?: container.connectionByAuthority(meta.uri.scheme, meta.uri.authority)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // MT「对话框图标」设置（0x7f1102f3/2f4/2f5/2f6）：深色 / 浅色 / 无背景
                DialogIcon(MtIcon.INFO)
                Text("属性", modifier = Modifier.padding(start = 10.dp))
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InfoRow("名称", meta.name.ifEmpty { "/" })
                InfoRow(
                    "目录",
                    (meta.uri.parent?.displayPath ?: "/").let { if (it.endsWith("/")) it else "$it/" },
                )
                InfoRow("类型", if (meta.isDirectory) "文件夹" else (meta.mimeType ?: "未知类型"))
                if (meta.isDirectory) {
                    val s = summary
                    InfoRow(
                        "大小",
                        when {
                            s == null -> "统计中…"
                            s.truncated -> "${Fmt.size(s.bytes)} 以上（已达 5 万项上限）"
                            else -> "${Fmt.size(s.bytes)} (${s.bytes})"
                        },
                    )
                    InfoRow("文件数", s?.let { if (it.truncated) "${it.files}+" else it.files.toString() } ?: "统计中…")
                    InfoRow("文件夹数", s?.let { if (it.truncated) "${it.dirs}+" else it.dirs.toString() } ?: "统计中…")
                } else {
                    InfoRow("大小", if (meta.size >= 0) "${Fmt.size(meta.size)} (${meta.size})" else "—")
                }
                InfoRow("修改时间", if (meta.lastModified > 0) Fmt.fullTime(meta.lastModified) else "—")
                InfoRow(
                    "权限",
                    meta.permissions?.let {
                        "${Fmt.modeLong(it, meta.isDirectory, meta.isSymlink)}(${Integer.toOctalString(it and 0xFFF)})"
                    } ?: "—",
                )
                InfoRow("所有者", meta.owner ?: "—")
                InfoRow("用户组", meta.group ?: "—")
                if (more) {
                    InfoRow(
                        "位置",
                        buildString {
                            append(meta.uri.scheme)
                            conn?.name?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                            if (meta.uri.authority.isNotBlank()) append(" · ").append(meta.uri.authority)
                        },
                    )
                    meta.etag?.let { InfoRow("ETag", it) }
                    meta.symlinkTarget?.let { InfoRow("链接指向", it) }
                    InfoRow("可用空间", spaceText ?: "—")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(meta.uri.toString()))
                        }) { Text("复制路径") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = { more = !more }) { Text(if (more) "收起" else "更多") }
        },
    )
}

/** 递归统计结果；[truncated] = 达到 5 万项上限提前停止（界面不能把它显示成精确值）。 */
private data class FolderSummary(
    val files: Int,
    val dirs: Int,
    val bytes: Long,
    val truncated: Boolean,
)

/** 递归统计文件夹：文件数 / 文件夹数 / 总字节（最多 5 万项，防超大目录卡界面） */
private suspend fun folderSummary(
    vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem?,
    uri: VfsUri,
): FolderSummary = withContext(Dispatchers.IO) {
    if (vfs == null) return@withContext FolderSummary(0, 0, 0L, false)
    var files = 0
    var dirs = 0
    var bytes = 0L
    var seen = 0
    val queue = ArrayDeque<VfsUri>().apply { add(uri) }
    while (queue.isNotEmpty() && seen < 50_000) {
        val dir = queue.removeFirst()
        val children = runCatching { vfs.list(dir) }.getOrDefault(emptyList())
        for (child in children) {
            seen++
            if (child.isDirectory) {
                dirs++
                queue.add(child.uri)
            } else {
                files++
                bytes += child.size.coerceAtLeast(0)
            }
        }
    }
    FolderSummary(files, dirs, bytes, seen >= 50_000 && queue.isNotEmpty())
}

/**
 * 多选属性：只汇总列表里已有的 metadata（**不递归目录、不额外 stat**）。
 *
 * 旧实现 `properties` 在 `multi > 1` 时直接禁用 —— 选 20 个文件想知道总共多大，
 * 只能一个个点。这里给的是「已知大小的文件之和 + 目录数 + 未知项数」，
 * 明确区分「统计值」与「没算的部分」，不把目录硬算成 0。
 */
@Composable
fun MultiPropertiesDialog(items: List<FileMetadata>, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var more by remember(items) { mutableStateOf(false) }

    val files = items.filter { !it.isDirectory }
    val dirs = items.size - files.size
    val known = files.filter { it.size >= 0 }
    val knownBytes = known.sumOf { it.size }
    val unknown = files.size - known.size
    val parents = items.mapNotNull { it.uri.parent }.distinct()
    val dirText = if (parents.size == 1) {
        (parents.first().displayPath).let { if (it.endsWith("/")) it else "$it/" }
    } else {
        "多个位置（${parents.size} 个目录）"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DialogIcon(MtIcon.INFO)
                Text("属性", modifier = Modifier.padding(start = 10.dp))
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InfoRow("项目", "${items.size} 项（文件 ${files.size} / 文件夹 $dirs）")
                InfoRow("目录", dirText)
                InfoRow(
                    "总大小",
                    buildString {
                        append(Fmt.size(knownBytes)).append(" (").append(knownBytes).append(")")
                        if (dirs > 0) append(" · 不含 $dirs 个文件夹")
                        if (unknown > 0) append(" · 另 $unknown 项大小未知")
                    },
                )
                if (more) {
                    InfoRow("说明", "文件夹不递归统计；大小未知项（远程 / 无元数据）不参与合计。")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            clipboard.setText(
                                AnnotatedString(items.joinToString("\n") { it.uri.toString() })
                            )
                        }) { Text("复制路径（${items.size} 行）") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = { more = !more }) { Text(if (more) "收起" else "更多") }
        },
    )
}
