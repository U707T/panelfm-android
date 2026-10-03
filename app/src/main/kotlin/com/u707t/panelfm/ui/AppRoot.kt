package com.u707t.panelfm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.ui.browser.DualPaneScreen
import com.u707t.panelfm.ui.connections.ConnectionEditScreen
import com.u707t.panelfm.ui.connections.LanScanScreen
import com.u707t.panelfm.ui.home.HomeScreen
import com.u707t.panelfm.ui.preview.PreviewScreen
import com.u707t.panelfm.ui.settings.SettingsScreen
import com.u707t.panelfm.ui.tasks.TasksScreen

/** 简单屏幕栈（不引入 navigation-compose：单人项目减少依赖，行为完全可控）。 */
sealed interface Screen {
    data object Home : Screen
    data object Browser : Screen
    data object Tasks : Screen
    data object Settings : Screen
    data class ConnectionEdit(
        val connectionId: Long?,
        val prefillHost: String? = null,
        val prefillPort: Int? = null,
    ) : Screen
    data object LanScan : Screen
    data class Preview(val uri: VfsUri) : Screen
}

@Composable
fun AppRoot(container: AppContainer) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // 预览请求（双击文件）由控制器发起
    val previewUri by container.browser.previewRequest.collectAsState()
    LaunchedEffect(previewUri) {
        previewUri?.let {
            container.browser.dismissPreviewRequest()
            push(Screen.Preview(it))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val current = stack.last()) {
            Screen.Home -> HomeScreen(
                container = container,
                onOpenBrowser = { push(Screen.Browser) },
                onOpenTasks = { push(Screen.Tasks) },
                onOpenSettings = { push(Screen.Settings) },
                onAddConnection = { push(Screen.ConnectionEdit(null)) },
                onEditConnection = { id -> push(Screen.ConnectionEdit(id)) },
                onScanLan = { push(Screen.LanScan) },
            )

            Screen.Browser -> DualPaneScreen(
                container = container,
                onOpenHome = pop,
                onOpenTasks = { push(Screen.Tasks) },
                onOpenSettings = { push(Screen.Settings) },
                onOpenPreview = { push(Screen.Preview(it)) },
            )

            Screen.Tasks -> TasksScreen(container = container, onBack = pop)
            Screen.Settings -> SettingsScreen(container = container, onBack = pop)
            is Screen.ConnectionEdit -> ConnectionEditScreen(
                container = container,
                connectionId = current.connectionId,
                onBack = pop,
                prefillHost = current.prefillHost,
                prefillPort = current.prefillPort,
            )
            Screen.LanScan -> LanScanScreen(
                container = container,
                onBack = pop,
                onPick = { host, port ->
                    stack = stack.dropLast(1) + Screen.ConnectionEdit(null, host, port)
                },
            )
            is Screen.Preview -> PreviewScreen(
                container = container,
                uri = current.uri,
                onBack = pop,
            )
        }
    }
}
