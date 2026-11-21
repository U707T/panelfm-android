package com.u707t.panelfm.ui.tools

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.NaturalOrder
import com.u707t.panelfm.core.ui.EmptyState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.MtListRow
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.ui.browser.PaneSide
import com.u707t.panelfm.ui.browser.ThumbCache
import com.u707t.panelfm.ui.browser.refresh
import com.u707t.panelfm.ui.browser.revealIn
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ================================================================================================
// 已安装应用（对齐 MT）：用户应用 / 系统应用两个分页 → 点一行出「应用信息」卡片 → 提取安装包。
//
// 与 MT 的差别只在**列表密度**：MT 是三行大卡片，这里保留紧凑的两行（包名 · 版本 · 大小都在
// 副标题里），信息量不减、一屏能多看一倍。
// ================================================================================================

/** 提取完成后「提示」对话框要记的东西（定位需要目录 + 目标 URI） */
private data class SavedApk(val side: PaneSide, val uri: VfsUri, val path: String)

@Composable
fun AppsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    /**
     * 「定位」要回到**文件浏览器**（本页可能从侧栏或主页进入，`onBack` 不一定回得到浏览器）。
     * 由 AppRoot 接成 `stack = listOf(Screen.Browser)`。
     */
    onLocate: () -> Unit = onBack,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(AppTab.USER) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    /** 加载完成前不显示「0 个」（旧实现先把 0 闪出来） */
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    /** 同一时间只导出一个（防连点并发写同一目录）；值 = 正在导出的包名 */
    var exporting by remember { mutableStateOf<String?>(null) }
    /** 点开一行的应用信息卡片 */
    var detail by remember { mutableStateOf<InstalledApp?>(null) }
    /** 提取完成后的「提示」对话框（MT：文件已保存到 … → 定位 / 关闭） */
    var saved by remember { mutableStateOf<SavedApk?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
        loaded = true
    }

    val visible = AppList.filter(apps, tab, query)

    fun export(app: InstalledApp) {
        if (exporting != null) return
        exporting = app.packageName
        status = "导出中：${app.label}…"
        val side = container.browser.state.value.focused
        // 用容器作用域：旧实现用 rememberCoroutineScope，离开本页导出会被取消
        container.scope.launch {
            val result = exportApk(context, container, side, app) { done, total ->
                val pct = if (total > 0) (done * 100 / total) else -1
                status = if (pct >= 0) "导出中：${app.label} $pct%"
                else "导出中：${app.label} " + Fmt.transferred(done, total)
            }
            status = result.message
            container.browser.showStatus(result.message)
            exporting = null
            if (result.uri != null) {
                saved = SavedApk(side, result.uri, result.uri.displayPath)
            }
        }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = "已安装应用",
            subtitle = if (loaded) {
                "${AppList.count(apps, tab)} 个（共 ${apps.size}）"
            } else {
                "读取中…"
            },
            onBack = onBack,
        )
        AppTabBar(tab = tab, apps = apps, onSelect = { tab = it })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索应用名 / 包名") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
        if (loaded && visible.isEmpty()) {
            EmptyState(
                title = if (query.isBlank()) "这一页没有应用" else "没有匹配的应用",
                subtitle = if (query.isBlank()) null else "换个关键字，或切换分页看看",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.packageName }) { app ->
                    MtListRow(
                        title = app.label,
                        subtitle = AppList.subtitleOf(app),
                        icon = { AppIcon(pm, app) },
                        onClick = { detail = app },
                        trailing = {
                            Text(
                                if (exporting == app.packageName) "导出中…" else "导出",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(enabled = exporting == null) { export(app) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        },
                    )
                }
            }
        }
    }

    detail?.let { app ->
        InstalledAppInfoDialog(
            container = container,
            app = app,
            onDismiss = { detail = null },
            onExtract = {
                detail = null
                export(app)
            },
        )
    }

    saved?.let { s ->
        AlertDialog(
            onDismissRequest = { saved = null },
            title = { Text("提示") },
            text = { Text("文件已保存到 ${s.path}") },
            dismissButton = {
                TextButton(onClick = {
                    saved = null
                    // 定位 = 回到文件列表并选中刚导出的 APK（MT 的「定位」）
                    s.uri.parent?.let { parent ->
                        container.browser.revealIn(s.side, parent, s.uri, connectionId = null, label = null)
                    }
                    onLocate()
                }) { Text("定位") }
            },
            confirmButton = { TextButton(onClick = { saved = null }) { Text("关闭") } },
        )
    }
}

/** 分页条（MT：用户应用 / 系统应用，选中项加粗 + 主题色下划线） */
@Composable
private fun AppTabBar(tab: AppTab, apps: List<InstalledApp>, onSelect: (AppTab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        AppTab.entries.forEach { t ->
            val selected = t == tab
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(t) }
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "${t.label}  ${AppList.count(apps, t)}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}

/** 一行应用的图标：真实图标（按包名 + versionCode 缓存），拿不到回退类型图标 */
@Composable
private fun AppIcon(pm: PackageManager, app: InstalledApp) {
    val bitmap: ImageBitmap? = produceState<ImageBitmap?>(null, app.packageName, app.versionCode) {
        value = runCatching {
            ThumbCache.loadInstalledIcon(pm, app.packageName, app.versionCode, 96)?.asImageBitmap()
        }.getOrNull()
    }.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
        FileIcon(name = "app.apk", isDirectory = false, size = 40.dp)
    }
}

/**
 * 读全部已安装应用（在 IO 上跑）。
 *
 * 一次算好标签 / 版本 / 大小 / 是否系统应用 —— 组合期不再碰 `PackageManager`
 * （旧实现每行在组合里调 `getApplicationLabel` + `getPackageInfo`，滚动时反复查 Binder）。
 */
private fun loadInstalledApps(context: android.content.Context): List<InstalledApp> {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val infos = runCatching {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
    }.getOrDefault(emptyList())
    return infos.mapNotNull { ai ->
        runCatching {
            val pkg = pm.getPackageInfo(ai.packageName, 0)
            val parts = buildList {
                ai.sourceDir?.let { add(File(it)) }
                ai.splitSourceDirs?.forEach { add(File(it)) }
            }
            val bytes = parts.sumOf { f -> runCatching { if (f.isFile) f.length() else 0L }.getOrDefault(0L) }
            InstalledApp(
                packageName = ai.packageName,
                label = pm.getApplicationLabel(ai).toString(),
                versionName = pkg.versionName.orEmpty(),
                versionCode = if (Build.VERSION.SDK_INT >= 28) {
                    pkg.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkg.versionCode.toLong()
                },
                bytes = bytes,
                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                sourceDir = ai.sourceDir.orEmpty(),
                splitCount = parts.size.coerceAtLeast(1),
                className = ai.className?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }.sortedWith(
        compareBy<InstalledApp, String>(NaturalOrder) { it.label }
            .thenBy(NaturalOrder) { it.packageName },
    )
}

/** 提取结果：成功时带目标 URI（「提示 → 定位」要用），失败只有文案 */
private data class ExportResult(val message: String, val uri: VfsUri? = null)

/**
 * 把已安装应用的 APK 提取到当前窗格。
 *
 * split 应用（base + 分包）打成 `.apks`，否则导出的 base.apk 装不上 —— 与 MT 同口径。
 * 打包先落缓存（`ZipOutputStream` 需要随机写，`VfsWriter` 只有顺序写），再顺序写进目标目录。
 */
private suspend fun exportApk(
    context: android.content.Context,
    container: AppContainer,
    side: PaneSide,
    app: InstalledApp,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
): ExportResult = withContext(Dispatchers.IO) {
    runCatching {
        val parts = buildList {
            File(app.sourceDir).takeIf { it.isFile }?.let { add(it) }
            // splitSourceDirs 只有分包应用才有
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(app.packageName, 0).applicationInfo?.splitSourceDirs
                ?.forEach { add(File(it)) }
        }.filter { it.isFile }
        if (parts.isEmpty()) return@runCatching ExportResult("提取失败：找不到 APK 文件")

        val isBundle = parts.size > 1
        val fileName = AppList.exportFileName(app.label, app.versionName, isBundle)
        val source: File = if (isBundle) {
            val tmp = File(context.cacheDir, "panelfm-export-${System.currentTimeMillis()}.apks")
            java.util.zip.ZipOutputStream(tmp.outputStream().buffered(256 * 1024)).use { zos ->
                parts.forEach { part ->
                    zos.putNextEntry(java.util.zip.ZipEntry(part.name))
                    part.inputStream().use { it.copyTo(zos, 256 * 1024) }
                    zos.closeEntry()
                }
            }
            tmp
        } else {
            parts.first()
        }

        try {
            val pane = container.browser.state.value.pane(side)
            val dest = container.uniqueChild(pane.uri, fileName)
            val vfs = container.locator.find(pane.uri) ?: return@runCatching ExportResult("当前窗格不可写")
            val total = source.length()
            val writer = vfs.openWrite(dest, size = total, offset = 0L)
            try {
                source.inputStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var lastTick = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        writer.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        // 250ms 节流：进度可见但不刷爆重组
                        if (now - lastTick >= 250) {
                            lastTick = now
                            onProgress(done, total)
                        }
                    }
                }
                writer.commit()
            } catch (e: Exception) {
                runCatching { writer.abort() }
                throw e
            }
            onProgress(total, total)
            container.browser.refresh(side)
            val note = if (isBundle) "（base + ${parts.size - 1} 个 split）" else ""
            ExportResult("已提取 ${dest.name}$note", dest)
        } finally {
            if (isBundle) runCatching { source.delete() }
        }
    }.getOrElse { ExportResult("提取失败：${it.message}") }
}
