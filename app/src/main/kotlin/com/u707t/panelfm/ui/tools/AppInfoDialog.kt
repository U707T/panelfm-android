package com.u707t.panelfm.ui.tools

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.ui.ClaudeDropdownMenu
import com.u707t.panelfm.core.ui.ClaudeMenuItem
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.ui.browser.ApkInfo
import com.u707t.panelfm.ui.browser.ApkInfoCardHeader
import com.u707t.panelfm.ui.browser.ApkInfoTable
import com.u707t.panelfm.ui.browser.ThumbCache
import com.u707t.panelfm.ui.browser.loadInstalledAppInfo
import kotlinx.coroutines.CancellationException

/**
 * 「已安装应用」点开一行的**应用信息卡片**（对齐 MT）：图标 + 应用名 + 版本，14 行包信息，
 * 底部 `更多` · `提取安装包`。
 *
 * 与文件浏览里的「APK 信息」共用同一套数据（[ApkInfo] / [ApkInfoTable]）与版式，
 * 区别只在入口（APK 直接取 `sourceDir`）与按钮（安装包不存在「安装 / 查看」，改成提取）。
 */
@Composable
internal fun InstalledAppInfoDialog(
    container: AppContainer,
    app: InstalledApp,
    onDismiss: () -> Unit,
    onExtract: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val clipboard = LocalClipboardManager.current
    var info by remember(app.packageName) { mutableStateOf<ApkInfo?>(null) }
    var error by remember(app.packageName) { mutableStateOf<String?>(null) }
    var menu by remember(app.packageName) { mutableStateOf(false) }

    LaunchedEffect(app.packageName) {
        try {
            info = loadInstalledAppInfo(
                context = context,
                sourceDir = app.sourceDir,
                size = app.bytes,
                splitCount = app.splitCount,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e::class.java.simpleName
        }
    }

    val icon: ImageBitmap? = produceState<ImageBitmap?>(null, app.packageName, app.versionCode) {
        value = runCatching {
            ThumbCache.loadInstalledIcon(pm, app.packageName, app.versionCode, 96)?.asImageBitmap()
        }.getOrNull()
    }.value

    val title = info?.label ?: app.label
    val subtitle = info?.versionName ?: app.versionName
    val launchIntent = remember(app.packageName) { pm.getLaunchIntentForPackage(app.packageName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { ApkInfoCardHeader(icon = icon, title = title, subtitle = subtitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    error != null -> Text(
                        "解析失败：$error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    info == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            "正在读取应用信息…",
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
                            container.browser.showStatus("已复制「$label」")
                        }
                    }
                }
            }
        },
        dismissButton = {
            Box {
                TextButton(onClick = { menu = true }) { Text("更多") }
                ClaudeDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ClaudeMenuItem(
                        label = "启动",
                        icon = MtIcon.PLAY,
                        enabled = launchIntent != null,
                    ) {
                        menu = false
                        launchIntent?.let { runCatching { context.startActivity(it) } }
                    }
                    ClaudeMenuItem(label = "应用详情", icon = MtIcon.INFO) {
                        menu = false
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")),
                            )
                        }
                    }
                    ClaudeMenuItem(
                        label = "卸载",
                        icon = MtIcon.DELETE,
                        // 系统应用不能卸载（系统也不给入口）
                        enabled = !app.isSystem,
                    ) {
                        menu = false
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
                        }
                    }
                    ClaudeMenuItem(label = "复制包名", icon = MtIcon.COPY) {
                        menu = false
                        clipboard.setText(AnnotatedString(app.packageName))
                        container.browser.showStatus("已复制包名：${app.packageName}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onExtract()
            }) { Text("提取安装包") }
        },
    )
}
