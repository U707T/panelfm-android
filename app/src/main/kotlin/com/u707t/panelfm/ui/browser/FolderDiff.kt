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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 目录差异对比（M9）：比较两个窗格当前目录（名称 + 大小 + 时间） */
enum class DiffState { ONLY_LEFT, ONLY_RIGHT, DIFFERENT, IDENTICAL }

data class DiffEntry(val name: String, val state: DiffState, val left: FileMetadata?, val right: FileMetadata?)

data class DiffResult(
    val leftDir: VfsUri,
    val rightDir: VfsUri,
    val entries: List<DiffEntry>,
    val identical: Int,
    val different: Int,
    val onlyLeft: Int,
    val onlyRight: Int,
) {
    val newerOnLeft: List<DiffEntry> get() = entries.filter {
        it.state == DiffState.DIFFERENT && (it.left?.lastModified ?: 0) > (it.right?.lastModified ?: 0)
    }
    val onlyLeftEntries: List<DiffEntry> get() = entries.filter { it.state == DiffState.ONLY_LEFT }
}

object FolderDiffEngine {

    /**
     * 对比两个目录。
     *
     * [report] = 长操作进度（审计 U4）：读取两侧目录、逐项比较都会上报，同时获得协作式取消
     * （取消后不再出结果框，只留「已取消」提示）。
     */
    suspend fun compare(
        container: AppContainer,
        left: VfsUri,
        right: VfsUri,
        report: BusyReporter? = null,
    ): DiffResult =
        withContext(Dispatchers.IO) {
            report?.note("读取左侧目录…")
            // 会话不可用必须报错：按「空目录」静默对比会得出整片「仅左侧/仅右侧」的错误结论，
            // 两个操作按钮还会据此真的发任务（见审查记录 §1-🟡6）。
            val leftVfs = container.locator.find(left)
                ?: throw VfsException.Unsupported("左侧目录不可用（会话未连接）")
            val rightVfs = container.locator.find(right)
                ?: throw VfsException.Unsupported("右侧目录不可用（会话未连接）")
            val leftItems = leftVfs.list(left).associateBy { it.name }
            report?.note("读取右侧目录…")
            val rightItems = rightVfs.list(right).associateBy { it.name }
            val names = (leftItems.keys + rightItems.keys).sorted()
            val entries = names.mapIndexed { index, name ->
                report?.report((index + 1).toLong(), names.size.toLong(), "比较 ${index + 1}/${names.size}")
                val l = leftItems[name]
                val r = rightItems[name]
                val state = when {
                    l != null && r == null -> DiffState.ONLY_LEFT
                    l == null && r != null -> DiffState.ONLY_RIGHT
                    l != null && r != null && (l.isDirectory != r.isDirectory || l.size != r.size) -> DiffState.DIFFERENT
                    l != null && r != null && l.lastModified != r.lastModified -> DiffState.DIFFERENT
                    else -> DiffState.IDENTICAL
                }
                DiffEntry(name, state, l, r)
            }
            DiffResult(
                leftDir = left,
                rightDir = right,
                entries = entries,
                identical = entries.count { it.state == DiffState.IDENTICAL },
                different = entries.count { it.state == DiffState.DIFFERENT },
                onlyLeft = entries.count { it.state == DiffState.ONLY_LEFT },
                onlyRight = entries.count { it.state == DiffState.ONLY_RIGHT },
            )
        }
}

/** 对比结果面板：统计 + 常用动作（MT 的「文件夹差异对比」） */
@Composable
fun FolderDiffDialog(
    result: DiffResult,
    onCopyOnlyLeft: () -> Unit,
    onCopyNewer: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("目录对比") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("左：${result.leftDir.displayPath}", style = MaterialTheme.typography.labelSmall)
                Text("右：${result.rightDir.displayPath}", style = MaterialTheme.typography.labelSmall)
                Text(
                    "相同 ${result.identical} · 不同 ${result.different} · 仅左侧 ${result.onlyLeft} · 仅右侧 ${result.onlyRight}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                val preview = result.entries.filter { it.state != DiffState.IDENTICAL }.take(40)
                preview.forEach { entry ->
                    val mark = when (entry.state) {
                        DiffState.ONLY_LEFT -> "← 仅左"
                        DiffState.ONLY_RIGHT -> "→ 仅右"
                        DiffState.DIFFERENT -> "≠ 不同"
                        DiffState.IDENTICAL -> "= 相同"
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(mark, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(
                            entry.name + ((entry.left ?: entry.right)?.let { m -> if (m.isDirectory) "/" else "  ${Fmt.size(m.size)}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (result.entries.size > preview.size + result.identical) {
                    Text("…（只列出差异项前 40 条）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onDismiss(); onCopyOnlyLeft() }) { Text("仅复制左侧") }
                TextButton(onClick = { onDismiss(); onCopyNewer() }) { Text("只复制较新") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
