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
        SettingSwitch("时间显示到秒", settings.showSeconds) {
            scope.launch { container.prefs.setShowSeconds(it) }
        }

        // 字体大小（全局缩放：紧凑 / 适中 / 标准）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字体大小", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            listOf("紧凑", "适中", "标准").forEachIndexed { idx, label ->
                TextButton(onClick = { scope.launch { container.prefs.setFontScaleLevel(idx) } }) {
                    Text(
                        label,
                        color = if (settings.fontScaleLevel == idx) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SettingSwitch("记忆上次的双列路径", settings.rememberLastPath) {
            scope.launch { container.prefs.setRememberLastPath(it) }
        }
        SettingSwitch("底栏上滑调出书签", settings.bookmarkSwipe) {
            scope.launch { container.prefs.setBookmarkSwipe(it) }
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

        Text(
            "底部工具栏下边距（全面屏手势时更舒适）：${settings.bottomBarPaddingDp}dp",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 6, 10, 14, 20, 28).forEach { dp ->
                TextButton(onClick = { scope.launch { container.prefs.setBottomBarPadding(dp) } }) {
                    Text(
                        dp.toString(),
                        color = if (dp == settings.bottomBarPaddingDp) MaterialTheme.colorScheme.primary
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
                "PanelFM ${com.u707t.panelfm.BuildConfig.VERSION_NAME}（原生 Kotlin / Compose）\n" +
                    "已实现：双列浏览（打开即双列）、MT 侧边栏抽屉、本地/SFTP(跳板机)/FTP·FTPS/WebDAV/SMB/S3、\n" +
                    "压缩包挂载解压压缩、任务引擎（进度/暂停/冲突/续传）、文本编辑器（语法高亮/查找替换/大文件分段浏览）、\n" +
                    "图片/音视频/Hex(只读) 预览、回收站/远程管理/已安装应用；MT 式搜索（正则/内容/历史）、排序按文件夹记忆、打开方式网格、属性统计。\n" +
                    "交互按 MT 管理器官方手册 + 截图复刻（v0.13.0 起：滑动多选 / 长按菜单 / 箭头跟随目标窗口；\n" +
                    "无障碍朗读覆盖顶栏 / 底栏 / 文件列表）。\n" +
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
