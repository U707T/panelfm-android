package com.u707t.panelfm.ui

import androidx.core.net.toUri
import android.Manifest
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.MtSpec
import androidx.compose.ui.platform.LocalContext
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.LocalNetwork
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.open.IncomingState
import com.u707t.panelfm.ui.bookmarks.BookmarksScreen
import com.u707t.panelfm.ui.browser.DualPaneScreen
import com.u707t.panelfm.ui.browser.openArchiveInPane
import com.u707t.panelfm.ui.connections.ConnectionEditScreen
import com.u707t.panelfm.ui.connections.LanScanScreen
import com.u707t.panelfm.ui.connections.connectionTypeForScanPort
import com.u707t.panelfm.ui.home.HomeScreen
import com.u707t.panelfm.ui.preview.PreviewScreen
import com.u707t.panelfm.ui.settings.SettingsScreen
import com.u707t.panelfm.ui.tasks.TasksScreen
import com.u707t.panelfm.ui.tools.TextDiffScreen
import com.u707t.panelfm.ui.tools.AppsScreen
import com.u707t.panelfm.ui.tools.NetToolboxScreen
import com.u707t.panelfm.ui.tools.RemoteScreen
import com.u707t.panelfm.ui.tools.TextToolboxScreen
import com.u707t.panelfm.ui.tools.TrashScreen

internal enum class StartupPermissionStep { STORAGE, LEGACY_STORAGE, LOCAL_NETWORK, NOTIFICATIONS, DONE }

internal fun firstStartupPermissionStep(
    sdk: Int,
    allFilesGranted: Boolean,
    legacyReadGranted: Boolean,
    legacyWriteGranted: Boolean,
    localNetworkGranted: Boolean,
    notificationsGranted: Boolean,
): StartupPermissionStep = startupPermissionSteps(
    sdk, allFilesGranted, legacyReadGranted, legacyWriteGranted, localNetworkGranted, notificationsGranted,
).firstOrNull() ?: StartupPermissionStep.DONE

internal fun startupPermissionSteps(
    sdk: Int,
    allFilesGranted: Boolean,
    legacyReadGranted: Boolean,
    legacyWriteGranted: Boolean,
    localNetworkGranted: Boolean,
    notificationsGranted: Boolean,
): List<StartupPermissionStep> = buildList {
    if (sdk >= 30 && !allFilesGranted) add(StartupPermissionStep.STORAGE)
    if (sdk in 26..29 && (!legacyReadGranted || !legacyWriteGranted)) add(StartupPermissionStep.LEGACY_STORAGE)
    if (sdk >= 37 && !localNetworkGranted) add(StartupPermissionStep.LOCAL_NETWORK)
    if (sdk >= 33 && !notificationsGranted) add(StartupPermissionStep.NOTIFICATIONS)
}

/** 简单屏幕栈（不引入 navigation-compose：单人项目减少依赖，行为完全可控）。 */
sealed interface Screen {
    data object Home : Screen

    /** 打开即是双列（MT 手册：进入 MT 管理器首先看到的是左右两个文件列表窗口） */
    data object Browser : Screen
    data object Tasks : Screen
    data object Settings : Screen
    data object Bookmarks : Screen
    data object LanScan : Screen
    /** 网络工具箱（Ping / DNS / HTTP / 端口，v2.1.0） */
    data object NetToolbox : Screen
    /** 字符串工具箱（编码 / 摘要 / 文本 / 进制，v2.0.12，借鉴 NP管理器「工具箱」） */
    data object TextToolbox : Screen
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
fun AppRoot(container: AppContainer, suppressAutoResume: Boolean = false) {
    val context = LocalContext.current
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Browser)) }
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // 退出：栈底时由这里兜底。
    //  - 「退出前双次确认」开启 → 连按两次才退出；
    //  - 关闭（默认）→ 直接退到桌面（moveTaskToBack 而不是 finish：与 MT 一致，
    //    回来时还在原来的目录，不会丢现场）。
    var exitArmed by remember { mutableStateOf(false) }
    val settingsForExit by container.settings.collectAsState()
    BackHandler(enabled = stack.size <= 1) {
        val activity = context as? android.app.Activity
        if (!settingsForExit.confirmExit || exitArmed) {
            runCatching { activity?.moveTaskToBack(true) }
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

    // 外部「用其他应用打开」：复制完成后直接进预览；复制中由下面的模态对话框显示进度。
    // key 只取「终态」：Copying 每 200ms 变一次，不能让它反复重启这个 effect。
    val incoming by container.incoming.state.collectAsState()
    val incomingTerminal = when (incoming) {
        is IncomingState.Ready -> incoming
        is IncomingState.Error -> incoming
        else -> null
    }
    var pendingIncoming by remember { mutableStateOf<IncomingState.Ready?>(null) }
    var approvedIncoming by remember { mutableStateOf<IncomingState.Ready?>(null) }
    LaunchedEffect(incomingTerminal, approvedIncoming) {
        val approved = approvedIncoming
        approvedIncoming = null
        when (val s = approved ?: incomingTerminal) {
            is IncomingState.Ready -> {
                if (approved == null && stack.lastOrNull() is Screen.Preview) {
                    pendingIncoming = s
                    return@LaunchedEffect
                }
                pendingIncoming = null
                container.incoming.consume()
                container.browser.showStatus("已接收「${s.name}」到缓存（编辑只改副本）")
                val ext = s.name.substringAfterLast('.', "").lowercase()
                val kind = com.u707t.panelfm.core.common.MimeTypes.kindOf(ext)
                when {
                    // EPUB 本质是 zip，但用户「打开」它通常是想读，不是看包结构。
                    ext == "epub" -> push(
                        Screen.Preview(
                            com.u707t.panelfm.ui.preview.PreviewRequest(
                                s.uri,
                                com.u707t.panelfm.ui.preview.PreviewMode.EPUB,
                            ),
                        ),
                    )
                    // 压缩包不在 PreviewScreen 里看（那里只能提示「返回列表」）：
                    // 回到双窗格，复用 MT 的「进入压缩包」链路。
                    kind == com.u707t.panelfm.core.common.MimeTypes.Kind.ARCHIVE -> {
                        stack = listOf(Screen.Browser)
                        runCatching {
                            container.browser.openArchiveInPane(
                                container.browser.state.value.focused,
                                com.u707t.panelfm.core.vfs.FileMetadata(
                                    uri = s.uri,
                                    name = s.name,
                                    isDirectory = false,
                                    size = s.size,
                                    mimeType = s.mime,
                                ),
                            )
                        }.onFailure {
                            container.browser.showStatus("打开压缩包失败：${it.message ?: "未知错误"}")
                        }
                    }
                    else -> push(
                        Screen.Preview(
                            com.u707t.panelfm.ui.preview.PreviewRequest(
                                s.uri,
                                com.u707t.panelfm.ui.preview.PreviewMode.AUTO,
                            ),
                        ),
                    )
                }
            }
            is IncomingState.Error -> {
                container.incoming.consume()
                container.browser.showStatus(s.message)
            }
            else -> Unit
        }
    }

    pendingIncoming?.let { received ->
        AlertDialog(
            onDismissRequest = {
                pendingIncoming = null
                container.incoming.consume()
                container.browser.showStatus("新文件已保留在「接收文件」，当前文件未切换")
            },
            title = { Text("打开新收到的文件？") },
            text = { Text("当前文件可能还有未保存修改。切换到「${received.name}」会离开当前预览；请先保存需要保留的修改。也可留在当前文件，稍后从「接收文件」打开新文件。") },
            confirmButton = {
                TextButton(onClick = { approvedIncoming = received; pendingIncoming = null }) { Text("仍然切换") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingIncoming = null
                    container.incoming.consume()
                    container.browser.showStatus("新文件已保留在「接收文件」，当前文件未切换")
                }) { Text("留在当前文件") }
            },
        )
    }

    // ---------------- 启动授权状态机：存储 → 局域网 → 通知；“稍后”只跳过本次当前步骤。
    var skippedPermissionSteps by remember { mutableStateOf(emptySet<StartupPermissionStep>()) }
    var permissionRefresh by remember { mutableStateOf(0) }
    val permissionSdk = remember(permissionRefresh) { Build.VERSION.SDK_INT }
    val allFilesGranted = permissionSdk < 30 || Environment.isExternalStorageManager()
    val legacyReadGranted = permissionSdk >= 30 || context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val legacyWriteGranted = permissionSdk >= 30 || context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val localNetworkGranted = LocalNetwork.isGranted(context)
    val notificationsGranted = permissionSdk < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val permissionStep = startupPermissionSteps(
        permissionSdk,
        allFilesGranted,
        legacyReadGranted,
        legacyWriteGranted,
        localNetworkGranted,
        notificationsGranted,
    ).firstOrNull { it !in skippedPermissionSteps } ?: StartupPermissionStep.DONE

    fun skipPermission(step: StartupPermissionStep) {
        skippedPermissionSteps = skippedPermissionSteps + step
        permissionRefresh++
    }

    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionRefresh++
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            skipPermission(StartupPermissionStep.STORAGE)
        }
    }
    val legacyStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionRefresh++
        if (grants[Manifest.permission.READ_EXTERNAL_STORAGE] != true ||
            (Build.VERSION.SDK_INT <= 29 && grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] != true)
        ) skipPermission(StartupPermissionStep.LEGACY_STORAGE)
    }
    val localNetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRefresh++
        if (!granted) skipPermission(StartupPermissionStep.LOCAL_NETWORK)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRefresh++
        if (!granted) skipPermission(StartupPermissionStep.NOTIFICATIONS)
    }

    /**
     * 恢复「上次打开着的文件」。
     *
     * 场景：看视频时被系统杀掉 / 直接退出 App —— 重新打开应当回到那个文件。
     * 判定：记录还在（用户没主动从预览返回）+ 24 小时内 + 文件仍可访问。
     */
    var resumeChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (resumeChecked) return@LaunchedEffect
        resumeChecked = true
        // 本次是「用其他应用打开」：让 incoming 流程接管，不要再恢复上次预览。
        // 用 Activity 传入的稳定标志，而不是只看 incoming 状态 —— 小文件可能在首帧前
        // 就已经 Ready 并被消费成 Idle，只看状态会漏判、叠出两个 Preview 页面。
        if (suppressAutoResume || container.incoming.state.value !is IncomingState.Idle) {
            return@LaunchedEffect
        }
        val last = runCatching { container.prefs.lastOpenedPreview() }.getOrNull() ?: return@LaunchedEffect
        if (System.currentTimeMillis() - last.at > 24L * 3600 * 1000) return@LaunchedEffect
        val target = runCatching { VfsUri.parse(last.uri) }.getOrNull() ?: return@LaunchedEffect
        val reachable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val vfs = container.resolveSession(target) ?: return@runCatching false
                !vfs.stat(target).isDirectory
            }.getOrDefault(false)
        }
        if (!reachable) return@LaunchedEffect
        val mode = runCatching { com.u707t.panelfm.ui.preview.PreviewMode.valueOf(last.mode) }
            .getOrDefault(com.u707t.panelfm.ui.preview.PreviewMode.AUTO)
        push(Screen.Preview(com.u707t.panelfm.ui.preview.PreviewRequest(target, mode)))
    }

    when (permissionStep) {
        StartupPermissionStep.STORAGE, StartupPermissionStep.LEGACY_STORAGE -> AlertDialog(
            onDismissRequest = { skipPermission(permissionStep) },
            title = { Text(if (permissionSdk >= 30) "需要「所有文件访问」权限" else "需要存储读写权限") },
            text = { Text("授权后可浏览和管理共享存储。未授权时只能使用应用私有目录；可稍后从系统应用权限设置授予。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        if (permissionSdk >= 30) {
                            storageLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData("package:${context.packageName}".toUri()),
                            )
                        } else {
                            legacyStorageLauncher.launch(arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            ))
                        }
                    }.onFailure { skipPermission(permissionStep) }
                }) { Text("去授权") }
            },
            dismissButton = { TextButton(onClick = { skipPermission(permissionStep) }) { Text("稍后") } },
        )
        StartupPermissionStep.LOCAL_NETWORK -> AlertDialog(
            onDismissRequest = { skipPermission(permissionStep) },
            title = { Text("需要「局域网访问」权限") },
            text = { Text("连接 NAS / SMB / SFTP 等局域网设备需要该权限。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { localNetLauncher.launch(LocalNetwork.PERMISSION) }
                        .onFailure { skipPermission(permissionStep) }
                }) { Text("去授权") }
            },
            dismissButton = { TextButton(onClick = { skipPermission(permissionStep) }) { Text("稍后") } },
        )
        StartupPermissionStep.NOTIFICATIONS -> AlertDialog(
            onDismissRequest = { skipPermission(permissionStep) },
            title = { Text("允许发送通知？") },
            text = { Text("用于显示后台传输进度与完成提醒。未允许通知也可在应用内查看任务。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        .onFailure { skipPermission(permissionStep) }
                }) { Text("允许") }
            },
            dismissButton = { TextButton(onClick = { skipPermission(permissionStep) }) { Text("稍后") } },
        )
        StartupPermissionStep.DONE -> Unit
    }

    // 外部文件复制进度：模态显示，避免用户连点 / 不知道大文件为什么没反应。
    (incoming as? IncomingState.Copying)?.let { copying ->
        AlertDialog(
            onDismissRequest = { container.incoming.cancel() },
            title = { Text("正在接收文件") },
            text = {
                Column {
                    Text(
                        copying.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (copying.total > 0) {
                        LinearProgressIndicator(
                            progress = { (copying.copied.toFloat() / copying.total).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                        Text(
                            "${Fmt.size(copying.copied)} / ${Fmt.size(copying.total)}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                        Text(
                            "已接收 ${Fmt.size(copying.copied)}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Text(
                        "文件会先复制到应用缓存；编辑只改副本，不回写来源。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { container.incoming.cancel() }) { Text("取消") }
            },
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
                onOpenNetToolbox = { push(Screen.NetToolbox) },
                onOpenTextToolbox = { push(Screen.TextToolbox) },
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
                onOpenNetToolbox = { push(Screen.NetToolbox) },
                onOpenTextToolbox = { push(Screen.TextToolbox) },
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
            Screen.Apps -> AppsScreen(
                container = container,
                onBack = pop,
                // 「提取安装包 → 定位」必须落到浏览器（本页也可能从主页/侧栏进来）
                onLocate = { stack = listOf(Screen.Browser) },
            )
            Screen.Remote -> RemoteScreen(container = container, onBack = pop)
            Screen.NetToolbox -> NetToolboxScreen(container = container, onBack = pop)
            Screen.TextToolbox -> TextToolboxScreen(container = container, onBack = pop)
            Screen.LanScan -> LanScanScreen(
                container = container,
                onBack = pop,
                onPick = { host, port ->
                    // 按扫描端口预选协议：扫 445/21 不再默认打开 SFTP 表单（用户进入后可改）
                    stack = stack.dropLast(1) +
                        Screen.ConnectionEdit(null, host, port, initialType = connectionTypeForScanPort(port))
                },
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

        // ---------------- 全局状态提示（所有页面共用）
        //
        // 为什么搬到这里：旧实现把 Snackbar 放在 BrowserDialogHost 里 —— 那个宿主只在
        // 「双列浏览页」组合时才存在。于是在回收站还原 / 删除、文本对比、编辑器保存、
        // 局域网扫描、设置页里 showStatus(...) 的结果**用户完全看不到**（消息进了队列，
        // 下一次回浏览页才弹出来，早已对不上刚才的操作）。而且浏览页一旦被移出组合，
        // 队列里的消息没有任何人消费，会一直堆着。
        StatusHost(container)
    }
}

/**
 * 全局状态提示宿主（审计 U9 队列语义保留）：一条条显示、错误类停更久、可手动「知道了」。
 *
 * 位置：栈最外层，覆盖所有页面；底距 = 底栏高度 + 手势安全区，不再写死 66dp
 * （全面屏手势条会压住 Snackbar 的下半截）。
 */
@Composable
private fun BoxScope.StatusHost(container: AppContainer) {
    val controller = container.browser
    val queue by controller.statusQueue.collectAsState()
    val message = queue.firstOrNull() ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Snackbar(
            modifier = Modifier
                .padding(bottom = MtSpec.BottomBarHeight)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 12.dp),
            action = {
                TextButton(onClick = { controller.consumeStatus() }) { Text("知道了") }
            },
        ) { Text(message) }
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(controller.statusDurationMs(message))
            controller.consumeStatus()
        }
    }
}
