package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.ui.ClaudeDropdownMenu
import com.u707t.panelfm.core.ui.ClaudeMenuItem
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.ui.preview.PreviewMode
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * APK 信息对话框（复刻 MT：**单击** apk / apks 就弹这张卡片，而不是直接进预览页）。
 *
 * 版式对齐 MT 截图：标题 = 图标 + 应用名 + 版本号；正文 14 行（包名 / 版本号 / 安装包大小 /
 * 签名状态 / 加固状态 / 目标系统 / 最低系统 / 已安装 / 数据目录 1、2 / APK 路径 / 首次安装 /
 * 最后更新 / UID）；底部 `功能` · `查看` · `安装`。
 *
 * 三个按钮的语义（与 MT 一致）：
 *  - `功能`：整页「APK 详细信息」、提取图标、转为 APK（分包包）、校验值、属性、复制路径；
 *  - `查看`：把包当 zip 打开，进压缩包内浏览（v2.5.2 之前单击 apk 就是直接进这里）；
 *  - `安装`：本地 `.apk` 交系统安装器、`.apks` 走 PackageInstaller；远端 / 包内的包在解析时
 *    已经把同一份字节缓存到 `cache/apk`，直接装缓存那份（不再让用户「先复制到本地」重来一遍）。
 */
@Composable
internal fun ApkInfoDialog(
    container: AppContainer,
    controller: BrowserController,
    item: FileMetadata,
    onDismiss: () -> Unit,
    /** 分包安装包的「转为 APK」（由 BrowserDialogHost 接上转换对话框） */
    onConvertApk: () -> Unit,
    /** 校验值（结果由 BrowserDialogHost 弹消息框） */
    onChecksum: (String) -> Unit,
    onProperties: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var stage by remember(item.uri) { mutableStateOf("正在解析安装包…") }
    var info by remember(item.uri) { mutableStateOf<ApkInfo?>(null) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    var menu by remember(item.uri) { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        try {
            info = loadApkInfo(container, context, item) { stage = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e::class.java.simpleName
        }
    }

    val icon: ImageBitmap? = produceState<ImageBitmap?>(null, info?.localApkPath) {
        val path = info?.localApkPath ?: return@produceState
        value = runCatching {
            ThumbCache.loadApkIconOf(context.packageManager, File(path), 96)?.asImageBitmap()
        }.getOrNull()
    }.value

    /** 解析时落到本地的副本（远端 / 压缩包内的包才有；安装直接用它，不必让用户再复制一遍） */
    val cachedApk = info?.localApkPath?.let { File(it) }
    val title = info?.label ?: item.name
    val subtitle = info?.versionName ?: if (isApkBundleName(item.name)) "分包安装包" else "APK 安装包"

    AlertDialog(
        onDismissRequest = onDismiss,
        // MT 的卡片是 24dp 圆角（默认 M3 是 28dp 的对话框 + 更窄的内边距，观感差一档）
        shape = RoundedCornerShape(24.dp),
        title = { ApkInfoCardHeader(icon = icon, title = title, subtitle = subtitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    error != null -> {
                        Text(
                            "解析失败：$error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "损坏的包、只有分包没有 base.apk、或存储已断开都会走到这里。" +
                                "可以先用「功能 → 属性」确认文件大小，或先复制到本地再试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    info == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stage,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    else -> {
                        val s = info!!
                        s.bundleNote?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                        }
                        ApkInfoTable(info = s) { label, value ->
                            clipboard.setText(AnnotatedString(value))
                            controller.showStatus("已复制「$label」")
                        }
                    }
                }
            }
        },
        dismissButton = {
            Box {
                TextButton(onClick = { menu = true }) { Text("功能") }
                ClaudeDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ClaudeMenuItem(label = "APK 详细信息", icon = MtIcon.ANDROID) {
                        menu = false
                        onDismiss()
                        controller.openWith(item, PreviewMode.APK_INFO)
                    }
                    ClaudeMenuItem(
                        label = "提取图标",
                        icon = MtIcon.IMAGE,
                        enabled = item.uri.scheme == "local",
                    ) {
                        menu = false
                        onDismiss()
                        extractApkIcon(container, context, item) { msg -> controller.showStatus(msg) }
                    }
                    if (isApkBundleName(item.name)) {
                        ClaudeMenuItem(
                            label = "转为 APK",
                            icon = MtIcon.SAVE_ALT,
                            enabled = item.uri.scheme == "local",
                        ) {
                            menu = false
                            onDismiss()
                            onConvertApk()
                        }
                    }
                    ClaudeMenuItem(label = "校验值 MD5", icon = MtIcon.TAG) {
                        menu = false
                        onChecksum("MD5")
                    }
                    ClaudeMenuItem(label = "校验值 SHA-256", icon = MtIcon.TAG) {
                        menu = false
                        onChecksum("SHA-256")
                    }
                    ClaudeMenuItem(label = "属性", icon = MtIcon.INFO) {
                        menu = false
                        onDismiss()
                        onProperties()
                    }
                    ClaudeMenuItem(label = "复制路径", icon = MtIcon.COPY) {
                        menu = false
                        clipboard.setText(AnnotatedString(info?.apkPath ?: item.uri.toString()))
                        controller.showStatus("路径已复制")
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 查看 = 当压缩包打开（v2.5.2 之前单击 apk 的默认行为）
                TextButton(onClick = {
                    onDismiss()
                    controller.openWith(item, PreviewMode.ARCHIVE)
                }) { Text("查看") }
                TextButton(onClick = {
                    onDismiss()
                    when {
                        // 本地文件：.apk 交系统安装器、.apks 走 PackageInstaller
                        item.uri.scheme == "local" ->
                            installApk(container, context, item) { msg -> controller.showStatus(msg) }
                        // 远端 / 包内：解析时已经缓存了同一份字节，直接装缓存那份
                        cachedApk != null && !isApkBundleName(item.name) ->
                            installApkFile(context, cachedApk) { msg -> controller.showStatus(msg) }
                        else -> controller.showStatus("网络 / 压缩包内的安装包请先复制到本地再安装")
                    }
                }) { Text("安装") }
            }
        },
    )
}

/**
 * 卡片头部：图标 + 应用名 + 版本 —— 文件浏览的「APK 信息」与「已安装应用」的详情共用。
 *
 * 图标为 null 时画首字占位（解析失败 / 还没加载完），不留空洞。
 */
@Composable
internal fun ApkInfoCardHeader(icon: ImageBitmap?, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(title.take(1), style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 卡片正文的 15 行表格（对齐 MT）：标签**深色加粗**、值用系统字体、单行省略、行距 2dp。
 *
 * 值被截断时**点这一行就复制全文**（比在单行省略号上长按选中更好用）。
 * 文件浏览的「APK 信息」与「已安装应用」的详情共用这一份。
 */
@Composable
internal fun ApkInfoTable(info: ApkInfo, onCopy: (label: String, value: String) -> Unit) {
    Column {
        ApkInfoRows.of(info).forEach { (label, value) ->
            InfoRow(
                label = label,
                value = value,
                labelWidth = 84.dp,
                // lineHeight 收到 16sp：M3 的 bodyMedium 默认 20sp 行高，15 行就是 60dp 的纯空白
                labelStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                valueStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                ),
                maxLines = 1,
                verticalPadding = 2.dp,
                labelColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { onCopy(label, value) },
            )
        }
    }
}
