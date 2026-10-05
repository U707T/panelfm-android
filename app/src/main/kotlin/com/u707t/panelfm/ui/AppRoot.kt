package com.u707t.panelfm.ui

import androidx.core.net.toUri
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.LocalNetwork
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.ui.bookmarks.BookmarksScreen
import com.u707t.panelfm.ui.browser.DualPaneScreen
import com.u707t.panelfm.ui.connections.ConnectionEditScreen
import com.u707t.panelfm.ui.connections.LanScanScreen
import com.u707t.panelfm.ui.home.HomeScreen
import com.u707t.panelfm.ui.preview.PreviewScreen
import com.u707t.panelfm.ui.settings.SettingsScreen
import com.u707t.panelfm.ui.tasks.TasksScreen
import com.u707t.panelfm.ui.tools.TextDiffScreen
import com.u707t.panelfm.ui.tools.AppsScreen
import com.u707t.panelfm.ui.tools.RemoteScreen
import com.u707t.panelfm.ui.tools.TrashScreen

/** 简单屏幕栈（不引入 navigation-compose：单人项目减少依赖，行为完全可控）。 */
sealed interface Screen {
    data object Home : Screen

    /** 打开即是双列（MT 手册：进入 MT 管理器首先看到的是左右两个文件列表窗口） */
    data object Browser : Screen
    data object Tasks : Screen
    data object Settings : Screen
    data object Bookmarks : Screen
    data object LanScan : Screen
    data object Trash : Screen
    data object Apps : Screen
    data object Remote : Screen
    data class ConnectionEdit(
        val connectionId: Long?,
        val prefillHost: String? = null,
        val prefillPort: Int? = null,
        val initialType: com.u707t.panelfm.core.model.ConnectionType? = null,
    ) : Screen
    data class Preview(val request: com.u707t.panelfm.ui.preview.PreviewRequest) : Screen
    data class TextDiff(val left: VfsUri, val right: VfsUri) : Screen
}

@Composable
fun AppRoot(container: AppContainer) {
    val context = LocalContext.current
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Browser)) }
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // MT「退出前双次确认」：在主页连按两次返回才退出（默认关，设置里可开）
    var exitArmed by remember { mutableStateOf(false) }
    val settingsForExit by container.settings.collectAsState()
    BackHandler(enabled = stack.size <= 1 && settingsForExit.confirmExit) {
        if (exitArmed) {
            runCatching { (context as? android.app.Activity)?.finish() }
        } else {
            exitArmed = true
            android.widget.Toast.makeText(context, "再按一次退出程序", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            kotlinx.coroutines.delay(2000)
            exitArmed = false
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { runCatching { container.browser.persistPaths() } }
    }

    // 预览请求（点击文件）由控制器发起
    val previewRequest by container.browser.previewRequest.collectAsState()
    LaunchedEffect(previewRequest) {
        previewRequest?.let {
            container.browser.dismissPreviewRequest()
            push(Screen.Preview(it))
        }
    }
    val diffRequest by container.browser.diffRequest.collectAsState()
    LaunchedEffect(diffRequest) {
        diffRequest?.let { (l, r) ->
            container.browser.dismissDiffRequest()
            push(Screen.TextDiff(l, r))
        }
    }

    // ---------------- 启动授权弹窗（MT：打开即用，权限用弹窗补齐）
    var askStorage by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 30) !Environment.isExternalStorageManager() else false
        )
    }
    var askLocalNet by remember { mutableStateOf(!LocalNetwork.isGranted(context)) }
    var askNotification by remember { mutableStateOf(false) }

    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        askStorage = if (Build.VERSION.SDK_INT >= 30) !Environment.isExternalStorageManager() else false
        if (askStorage) askNotification = true
    }
    val localNetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askLocalNet = !it
        askNotification = true
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askNotification = false
    }

    // 「设为首页」的目录（默认就是内部存储根）
    LaunchedEffect(Unit) {
        runCatching { container.browser.openHomeIfConfigured() }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            // 通知用于后台传输进度，等前两个权限处理完再问
            if (granted) askNotification = false
        }
    }

    if (askStorage) {
        AlertDialog(
            onDismissRequest = { askStorage = false; askLocalNet = !LocalNetwork.isGranted(context) },
            title = { Text("需要「所有文件访问」权限") },
            text = { Text("Android 11 及以上必须授权，才能像 MT 一样浏览 /storage/emulated/0、Android/data 之外的完整文件系统。\n未授权时会退化为只能访问应用私有目录。") },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            storageLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData("package:${context.packageName}".toUri())
                            )
                        }
                    } else {
                        // askStorage 在 API < 30 默认就是 false；这里仅防止未来状态来源变化。
                        askStorage = false
                    }
                    askStorage = false
                    askLocalNet = !LocalNetwork.isGranted(context)
                }) { Text("去授权") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askStorage = false
                    askLocalNet = !LocalNetwork.isGranted(context)
                }) { Text("稍后") }
            },
        )
    } else if (askLocalNet) {
        AlertDialog(
            onDismissRequest = { askLocalNet = false; askNotification = true },
            title = { Text("需要「局域网访问」权限（Android 17）") },
            text = { Text("访问 NAS / Alist / SMB / SFTP 等局域网设备必须授权；未授权时连接会直接超时且没有提示。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { localNetLauncher.launch(LocalNetwork.PERMISSION) }
                    askLocalNet = false
                    askNotification = true
                }) { Text("去授权") }
            },
            dismissButton = {
                TextButton(onClick = { askLocalNet = false; askNotification = true }) { Text("稍后") }
            },
        )
    } else if (askNotification && Build.VERSION.SDK_INT >= 33) {
        AlertDialog(
            onDismissRequest = { askNotification = false },
            title = { Text("允许发送通知？") },
            text = { Text("用于显示后台传输进度与完成提醒（不加任何广告/统计）。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { notificationLauncher.launch("android.permission.POST_NOTIFICATIONS") }
                    askNotification = false
                }) { Text("允许") }
            },
            dismissButton = { TextButton(onClick = { askNotification = false }) { Text("不用了") } },
        )
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 注意：安全区内缩由各屏幕自己处理（core.ui.safeAreaPadding）——
        // 播放器需要真正的整屏（黑色铺满），若在这里统一内缩会把播放器包进「白框」。
        when (val current = stack.last()) {
            Screen.Browser -> DualPaneScreen(
                container = container,
                onOpenHome = { push(Screen.Home) },
                onOpenTasks = { push(Screen.Tasks) },
                onOpenSettings = { push(Screen.Settings) },
                onOpenBookmarks = { push(Screen.Bookmarks) },
                onOpenLanScan = { push(Screen.LanScan) },
                onOpenTrash = { push(Screen.Trash) },
                onOpenApps = { push(Screen.Apps) },
                onOpenRemote = { push(Screen.Remote) },
                onAddConnection = { type -> push(Screen.ConnectionEdit(null, initialType = type)) },
                onEditConnection = { id -> push(Screen.ConnectionEdit(id)) },
                onOpenPreview = { uri ->
                    push(Screen.Preview(com.u707t.panelfm.ui.preview.PreviewRequest(uri)))
                },
                onOpenEditor = { uri ->
                    push(
                        Screen.Preview(
                            com.u707t.panelfm.ui.preview.PreviewRequest(
                                uri,
                                com.u707t.panelfm.ui.preview.PreviewMode.EDITOR,
                            )
                        )
                    )
                },
                onOpenDiff = { l, r -> push(Screen.TextDiff(l, r)) },
            )

            Screen.Home -> HomeScreen(
                container = container,
                onOpenBrowser = pop,
                onOpenTasks = { push(Screen.Tasks) },
                onOpenSettings = { push(Screen.Settings) },
                onOpenBookmarks = { push(Screen.Bookmarks) },
                onScanLan = { push(Screen.LanScan) },
                onAddConnection = { push(Screen.ConnectionEdit(null)) },
                onEditConnection = { id -> push(Screen.ConnectionEdit(id)) },
                onOpenTrash = { push(Screen.Trash) },
                onOpenApps = { push(Screen.Apps) },
                onOpenRemote = { push(Screen.Remote) },
                onOpenEditor = { push(Screen.Browser) },
            )

            Screen.Tasks -> TasksScreen(container = container, onBack = pop)
            Screen.Settings -> SettingsScreen(container = container, onBack = pop)
            Screen.Bookmarks -> BookmarksScreen(
                container = container,
                onBack = pop,
                onOpen = { stack = listOf(Screen.Browser) },
            )
            Screen.Trash -> TrashScreen(container = container, onBack = pop)
            Screen.Apps -> AppsScreen(container = container, onBack = pop)
            Screen.Remote -> RemoteScreen(container = container, onBack = pop)
            Screen.LanScan -> LanScanScreen(
                container = container,
                onBack = pop,
                onPick = { host, port -> stack = stack.dropLast(1) + Screen.ConnectionEdit(null, host, port) },
            )
            is Screen.ConnectionEdit -> ConnectionEditScreen(
                container = container,
                connectionId = current.connectionId,
                onBack = pop,
                prefillHost = current.prefillHost,
                prefillPort = current.prefillPort,
                initialType = current.initialType,
            )
            is Screen.Preview -> PreviewScreen(
                container = container,
                request = current.request,
                onBack = pop,
            )
            is Screen.TextDiff -> TextDiffScreen(
                container = container,
                left = current.left,
                right = current.right,
                onBack = pop,
            )
        }
    }
}
