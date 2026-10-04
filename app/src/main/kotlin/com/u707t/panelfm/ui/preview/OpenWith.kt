package com.u707t.panelfm.ui.preview

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtVectorIcon
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 打开方式（复刻 MT 的网格对话框）：
 *  - 内置功能 + 系统应用（View / Send 处理器，含图标）三列网格
 *  - 右上 🔍 搜索、❓ 帮助；底部「类型」过滤、「管理」删除默认、「关闭」
 *  - **长按内置项 = 设为该扩展名的默认打开方式**
 */
enum class PreviewMode(val handlerId: String, val label: String, val icon: MtIcon) {
    AUTO("auto", "自动识别", MtIcon.EXPLORE),
    TEXT("text", "文本查看器", MtIcon.DESC),
    EDITOR("editor", "编辑文本", MtIcon.EDIT),
    HEX("hex", "十六进制", MtIcon.CODE),
    IMAGE("image", "查看图片", MtIcon.IMAGE),
    MEDIA("media", "播放音乐/视频", MtIcon.MOVIE),
    PDF("pdf", "查看 PDF", MtIcon.DESC),
    APK_INFO("apk", "APK 信息", MtIcon.ANDROID),
    ARCHIVE("archive", "浏览压缩包", MtIcon.ARCHIVE),
    FONT("font", "查看字体", MtIcon.FONT),
    SYSTEM("system", "系统应用打开", MtIcon.LAUNCH),
    ;

    companion object {
        fun ofHandler(id: String?): PreviewMode? = entries.firstOrNull { it.handlerId == id }
    }
}

data class OpenWithOption(val mode: PreviewMode, val available: Boolean = true)

/** 打开预览的请求（带「打开方式」模式） */
data class PreviewRequest(val uri: com.u707t.panelfm.core.vfs.VfsUri, val mode: PreviewMode = PreviewMode.AUTO)

/** 系统应用条目（打开方式网格用） */
data class SystemOpenApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    /** Intent.ACTION_VIEW / Intent.ACTION_SEND */
    val action: String,
)

private enum class AppFilter(val label: String) { ALL("全部"), BUILTIN("内置功能"), SYSTEM("系统应用") }

@Composable
fun OpenWithDialog(
    fileName: String,
    mimeType: String?,
    localFile: Boolean,
    options: List<OpenWithOption>,
    defaultMode: PreviewMode?,
    onPick: (PreviewMode) -> Unit,
    onPickSystem: (SystemOpenApp) -> Unit,
    onSetDefault: (PreviewMode) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var help by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(AppFilter.ALL) }

    // 系统「查看 / 发送」处理器（仅本地文件）
    val systemApps by produceState(emptyList<SystemOpenApp>(), fileName, localFile) {
        value = if (localFile) withContext(Dispatchers.IO) { querySystemApps(context, mimeType) } else emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("打开方式…", modifier = Modifier.weight(1f))
                TextButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    MtVectorIcon(icon = MtIcon.SEARCH, size = 22.dp)
                }
                TextButton(onClick = { help = true }) {
                    MtVectorIcon(icon = MtIcon.HELP, size = 22.dp)
                }
            }
        },
        text = {
            Column(Modifier.height(400.dp)) {
                if (searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索应用 / 功能") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                    )
                }
                val builtin = options.filter {
                    it.mode != PreviewMode.AUTO &&
                        filter != AppFilter.SYSTEM &&
                        (query.isBlank() || it.mode.label.contains(query, ignoreCase = true))
                }
                val apps = systemApps.filter {
                    filter != AppFilter.BUILTIN &&
                        (query.isBlank() || it.label.contains(query, ignoreCase = true))
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(builtin, key = { "b-${it.mode.handlerId}" }) { option ->
                        BuiltinTile(
                            option = option,
                            isDefault = defaultMode == option.mode,
                            onPick = { onPick(option.mode) },
                            onSetDefault = { onSetDefault(option.mode) },
                        )
                    }
                    items(apps, key = { "s-${it.packageName}" }) { app ->
                        SystemTile(app = app, onPick = { onPickSystem(app) })
                    }
                }
                if (!localFile) {
                    Text(
                        "网络文件不支持系统应用打开（可先复制到本地）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            Box {
                TextButton(onClick = { typeMenu = true }) { Text("类型：${filter.label}") }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    AppFilter.entries.forEach { f ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    f.label,
                                    color = if (f == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = { filter = f; typeMenu = false },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDismiss(); onManage() }) { Text("管理") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )

    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("打开方式 · 帮助") },
            text = {
                Text(
                    "· 点击 = 用该方式打开当前文件\n" +
                        "· 长按内置项 = 设为该扩展名的默认打开方式\n" +
                        "· 「管理」可查看 / 删除已设置的默认值\n" +
                        "· 系统应用列表来自系统的「查看 / 发送」注册表",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun BuiltinTile(
    option: OpenWithOption,
    isDefault: Boolean,
    onPick: () -> Unit,
    onSetDefault: () -> Unit,
) {
    Column(
        Modifier
            .combinedClickable(
                enabled = option.available,
                onClick = onPick,
                onLongClick = onSetDefault,
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(builtinColor(option.mode).copy(alpha = if (option.available) 1f else 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            MtVectorIcon(icon = option.mode.icon, size = 24.dp, tint = Color.White)
        }
        Text(
            option.mode.label + if (isDefault) "（默认）" else "",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (isDefault) FontWeight.Bold else FontWeight.Normal,
            color = if (option.available) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SystemTile(app: SystemOpenApp, onPick: () -> Unit) {
    val icon = rememberAppIcon(app.packageName)
    Column(
        Modifier
            .clickable(onClick = onPick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(app.label.take(1), style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            app.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun builtinColor(mode: PreviewMode): Color = when (mode) {
    PreviewMode.TEXT -> Color(0xFF5C6BC0)
    PreviewMode.EDITOR -> Color(0xFF4F6BED)
    PreviewMode.HEX -> Color(0xFF78909C)
    PreviewMode.IMAGE -> Color(0xFF26A69A)
    PreviewMode.MEDIA -> Color(0xFFE57373)
    PreviewMode.PDF -> Color(0xFFEF5350)
    PreviewMode.APK_INFO -> Color(0xFF66BB6A)
    PreviewMode.ARCHIVE -> Color(0xFF8D6E63)
    PreviewMode.FONT -> Color(0xFF7E57C2)
    else -> Color(0xFF9E9E9E)
}

@Composable
private fun rememberAppIcon(packageName: String): ImageBitmap? {
    val pm = LocalContext.current.packageManager
    val state = produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                pm.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    return state.value
}

/** 查询系统「查看 / 发送」处理器（去重，按名称排序） */
private fun querySystemApps(context: android.content.Context, mime: String?): List<SystemOpenApp> {
    val pm = context.packageManager
    val type = mime?.takeIf { it.isNotBlank() } ?: "*/*"
    val dummy = android.net.Uri.parse("content://com.u707t.panelfm.preview/file")
    val out = LinkedHashMap<String, SystemOpenApp>()
    fun collect(action: String) {
        val intent = Intent(action).apply {
            this.type = type
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (action == Intent.ACTION_VIEW) setDataAndType(dummy, type)
            else putExtra(Intent.EXTRA_STREAM, dummy)
        }
        runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList()).forEach { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@forEach
            if (pkg == context.packageName) return@forEach
            out.putIfAbsent(
                pkg,
                SystemOpenApp(ri.loadLabel(pm).toString(), pkg, ri.activityInfo.name, action),
            )
        }
    }
    collect(Intent.ACTION_VIEW)
    collect(Intent.ACTION_SEND)
    return out.values.sortedBy { it.label.lowercase() }
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
