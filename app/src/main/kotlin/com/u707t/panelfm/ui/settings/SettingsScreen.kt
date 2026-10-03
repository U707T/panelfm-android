package com.u707t.panelfm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.data.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("设置", style = MaterialTheme.typography.titleMedium)
        }

        SettingSwitch("跟随系统深色", settings.themeMode == ThemeMode.SYSTEM) {
            scope.launch { container.prefs.setTheme(if (it) ThemeMode.SYSTEM else ThemeMode.DARK) }
        }
        SettingSwitch("动态取色（Android 12+）", settings.dynamicColor) {
            scope.launch { container.prefs.setDynamicColor(it) }
        }
        SettingSwitch("默认显示隐藏文件", settings.showHidden) {
            scope.launch { container.prefs.setShowHidden(it) }
        }
        SettingSwitch("默认单列显示（手机窄屏）", settings.useSingleColumn) {
            scope.launch { container.prefs.setSingleColumn(it) }
        }
        SettingSwitch("移动数据下加载缩略图", settings.thumbnailsOnMobile) {
            scope.launch { container.prefs.setThumbsOnMobile(it) }
        }
        SettingSwitch("默认信任自签证书（新的 WebDAV 连接）", settings.trustSelfSigned) {
            scope.launch { container.prefs.setTrustSelfSigned(it) }
        }

        Text("任务并发：${settings.maxConcurrentTasks}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..4).forEach { n ->
                TextButton(onClick = {
                    scope.launch {
                        container.prefs.setMaxConcurrent(n)
                        container.engine.updateConcurrency(n)
                    }
                }) {
                    Text(
                        n.toString(),
                        color = if (n == settings.maxConcurrentTasks) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text("User-Agent", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            settings.userAgent,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(Modifier.padding(top = 16.dp)) {
            Text("关于", style = MaterialTheme.typography.titleSmall)
            Text(
                "PanelFM 0.5.0（原生 Kotlin / Compose）\n" +
                    "已实现：双列浏览（打开即双列）、本地/SFTP(跳板机)/FTP·FTPS/WebDAV/SMB/S3、压缩包挂载解压压缩、\n" +
                    "任务引擎（进度/暂停/冲突/续传）、文本/图片/Hex(只读) 预览、回收站/远程管理/已安装应用/终端。\n" +
                    "计划：文本编辑器增强、字体预览、媒体播放、缩略图、拖拽、目录对比（M7/M9）。\n" +
                    "本项目不含任何逆向工程功能（不做 DEX / Arsc / APK 编辑）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.padding(bottom = 30.dp)) {}
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
