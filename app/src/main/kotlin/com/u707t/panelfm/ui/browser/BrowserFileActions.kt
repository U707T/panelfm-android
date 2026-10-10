package com.u707t.panelfm.ui.browser

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.Spacer
import com.u707t.panelfm.core.ui.DividerPx
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.IconTextButton
import com.u707t.panelfm.core.ui.MtActionButton
import com.u707t.panelfm.core.ui.MtBottomIconButton
import com.u707t.panelfm.core.ui.MtGesture
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtMenuRow
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.PaneEdgeShadow
import com.u707t.panelfm.core.ui.VDividerPx
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.overallProgress
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.SpaceInfo
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.local.LocalVolume
import com.u707t.panelfm.core.vfs.local.LocalVolumes
import com.u707t.panelfm.ui.preview.OpenWithDialog
import com.u707t.panelfm.ui.preview.OpenWithManageDialog
import com.u707t.panelfm.ui.preview.OpenWithOption
import com.u707t.panelfm.ui.preview.PreviewMode
import com.u707t.panelfm.core.vfs.VfsUri
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ================================================================================================
// 从 DualPaneScreen 拆出（重审 🔴1b）：分享 / 系统打开 / 安装 APK / 提取 APK 图标。
// ================================================================================================

/**
 * 分享（支持多选）：一个文件走 [Intent.ACTION_SEND]，多个走 [Intent.ACTION_SEND_MULTIPLE]
 * —— MT 的多选分享会把选中的文件一次全交出去。
 */
internal fun shareItems(
    container: AppContainer,
    context: android.content.Context,
    items: List<FileMetadata>,
    onMessage: (String) -> Unit,
) {
    if (items.isEmpty()) return
    if (items.any { it.uri.scheme != "local" }) {
        onMessage("网络文件请先复制到本地再分享")
        return
    }
    val uris = items.mapNotNull { item ->
        val file = File(container.localVfs.absolutePath(item.uri))
        if (!file.exists()) null
        else runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }
    if (uris.isEmpty()) {
        onMessage("文件不存在")
        return
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = items.first().mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        val sameType = items.mapNotNull { it.mimeType }.distinct().singleOrNull()
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = sameType ?: "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val title = if (uris.size == 1) "分享 ${items.first().name}" else "分享 ${uris.size} 个文件"
    runCatching { context.startActivity(Intent.createChooser(intent, title)) }
        .onFailure { onMessage("没有可用的分享目标") }
}

/** 打开方式：直接交给指定的系统应用（MT 网格里点具体某个应用） */
internal fun openWithSystemApp(
    container: AppContainer,
    context: android.content.Context,
    item: FileMetadata,
    app: com.u707t.panelfm.ui.preview.SystemOpenApp,
    onMessage: (String) -> Unit,
) {
    if (item.uri.scheme != "local") {
        onMessage("网络文件请先复制到本地再打开")
        return
    }
    val file = File(container.localVfs.absolutePath(item.uri))
    if (!file.exists()) {
        onMessage("文件不存在")
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return onMessage("无法生成打开链接")
    val intent = Intent(app.action).apply {
        setClassName(app.packageName, app.activityName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (app.action == Intent.ACTION_VIEW) {
            setDataAndType(uri, item.mimeType ?: "*/*")
        } else {
            type = item.mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
    }
    runCatching { context.startActivity(intent) }
        .onFailure { onMessage("打开失败：${it.message}") }
}

/** 打开方式：交给系统选择器 */
internal fun openWithSystem(
    container: AppContainer,
    context: android.content.Context,
    item: FileMetadata,
    onMessage: (String) -> Unit,
) {
    if (item.uri.scheme != "local") {
        onMessage("网络文件请先复制到本地再打开")
        return
    }
    val file = File(container.localVfs.absolutePath(item.uri))
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return onMessage("无法生成打开链接")
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, item.mimeType ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "打开方式")) }
        .onFailure { onMessage("没有可用的应用") }
}
/**
 * 安装 APK（MT `0x7f110044`「安装」）。
 *
 * 只做「交给系统安装器」这一档（MT 还有 Shizuku / Root 两档，需要额外授权，
 * 本项目不引入）：本地文件走 FileProvider + `ACTION_VIEW(application/vnd.android.package-archive)`；
 * 网络文件提示先复制到本地（与「分享」一致的口径）。
 */
internal fun installApk(
    container: AppContainer,
    context: android.content.Context,
    item: FileMetadata,
    onMessage: (String) -> Unit,
) {
    if (!item.name.lowercase().endsWith(".apk")) {
        onMessage("只有 APK 文件可以安装")
        return
    }
    if (item.uri.scheme != "local") {
        onMessage("网络 / 压缩包内的 APK 请先复制到本地再安装")
        return
    }
    val file = runCatching { java.io.File(container.localVfs.absolutePath(item.uri)) }.getOrNull()
    if (file == null || !file.exists()) {
        onMessage("文件不存在")
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
    if (uri == null) {
        onMessage("无法生成安装链接")
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
        .onFailure { onMessage("没有可用的安装器") }
}

/**
 * 提取 APK 图标到同目录（MT `0x7f110248`「提取安装包」的轻量版：
 * 只取应用图标存成 PNG，命名 `<apk 名>-icon.png`，重名自动加序号）。
 */
internal fun extractApkIcon(
    container: AppContainer,
    context: android.content.Context,
    item: FileMetadata,
    onMessage: (String) -> Unit,
) {
    if (item.uri.scheme != "local") {
        onMessage("请先复制到本地再提取图标")
        return
    }
    container.scope.launch {
        val result: Result<android.graphics.Bitmap> = runCatching {
            val file = java.io.File(container.localVfs.absolutePath(item.uri))
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
                ?: throw IllegalStateException("无法解析 APK")
            val appInfo = info.applicationInfo ?: throw IllegalStateException("无法解析应用信息")
            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath
            val drawable = appInfo.loadIcon(pm) ?: throw IllegalStateException("无图标")
            drawable.toBitmap(192, 192)
        }
        result.onSuccess { bitmap ->
            val out = runCatching {
                val parent = item.uri.parent ?: item.uri
                val base = item.name.substringBeforeLast('.', item.name) + "-icon"
                val target = container.uniqueChild(parent, "$base.png")
                val vfs = container.locator.find(target)
                    ?: throw IllegalStateException("目标存储不可用")
                val bos = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                val bytes = bos.toByteArray()
                val w = vfs.openWrite(target, bytes.size.toLong(), 0L)
                try {
                    w.write(bytes, 0, bytes.size)
                    w.commit()
                } catch (e: Throwable) {
                    runCatching { w.abort() }
                    throw e
                }
                target.name
            }
            out.onSuccess { name ->
                onMessage("已提取图标：$name")
                container.browser.refreshAll()
            }.onFailure { onMessage("保存图标失败：${it.message}") }
        }.onFailure { onMessage("提取图标失败：${it.message}") }
    }
}
