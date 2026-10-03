package com.u707t.panelfm.ui.preview

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 打开方式（对齐 MT 的「打开方式」）：
 *  - 内置打开方式列表 + 系统应用
 *  - **长按某项 = 设为默认**；`管理` 可删除某扩展名的默认设置
 */
enum class PreviewMode(val handlerId: String, val label: String, val glyph: String) {
    AUTO("auto", "自动识别", "✨"),
    TEXT("text", "文本查看器", "📄"),
    EDITOR("editor", "文本编辑器", "✏"),
    HEX("hex", "十六进制查看", "＃"),
    IMAGE("image", "图片查看器", "🖼"),
    MEDIA("media", "音频/视频播放器", "▶"),
    ARCHIVE("archive", "压缩包查看器", "🗜"),
    FONT("font", "字体预览", "🅰"),
    SYSTEM("system", "系统应用打开", "↗"),
    ;

    companion object {
        fun ofHandler(id: String?): PreviewMode? = entries.firstOrNull { it.handlerId == id }
    }
}

data class OpenWithOption(val mode: PreviewMode, val available: Boolean = true)

/** 打开预览的请求（带「打开方式」模式） */
data class PreviewRequest(val uri: com.u707t.panelfm.core.vfs.VfsUri, val mode: PreviewMode = PreviewMode.AUTO)

@Composable
fun OpenWithDialog(
    fileName: String,
    options: List<OpenWithOption>,
    defaultMode: PreviewMode?,
    onPick: (PreviewMode) -> Unit,
    onSetDefault: (PreviewMode) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "打开方式 · $fileName",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "长按某一项可设为该类型的默认打开方式" + (defaultMode?.let { "（当前默认：${it.label}）" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                enabled = option.available,
                                onClick = { onPick(option.mode) },
                                onLongClick = { onSetDefault(option.mode) },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(option.mode.glyph, style = MaterialTheme.typography.titleMedium)
                        Text(
                            option.mode.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (defaultMode == option.mode) FontWeight.Bold else FontWeight.Normal,
                            color = if (option.available) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        if (defaultMode == option.mode) {
                            Text("默认", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onManage() }) { Text("管理") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 打开方式管理：查看并删除已设置的类型默认值 */
@Composable
fun OpenWithManageDialog(
    entries: List<Pair<String, String>>,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已设置的打开方式") },
        text = {
            Column {
                if (entries.isEmpty()) {
                    Text("还没有设置任何默认打开方式", style = MaterialTheme.typography.bodySmall)
                } else {
                    entries.forEach { (ext, handler) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                ".$ext → ${PreviewMode.ofHandler(handler)?.label ?: handler}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onDelete(ext) }) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
