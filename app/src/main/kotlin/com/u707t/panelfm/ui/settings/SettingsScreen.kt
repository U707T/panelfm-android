package com.u707t.panelfm.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.data.ThemeMode
import com.u707t.panelfm.ui.browser.BrowseMode
import kotlinx.coroutines.launch

/**
 * 设置页（对齐 MT 的「设置」观感）：
 * 主题（跟随系统 / 浅色 / 深色 三选一）、动态取色、字体大小、缩略图、任务并发、
 * 底栏下边距、全局 User-Agent（可编辑，此前只读且与协议层不一致）等。
 */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var editUserAgent by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .safeAreaPadding()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("设置", style = MaterialTheme.typography.titleMedium)
        }

        // 主题：三选一（旧实现只有「跟随系统」开关，取消勾选会固定成 DARK —— 浅色主题不可达）
        SectionLabel("主题")
        listOf(
            ThemeMode.SYSTEM to "跟随系统",
            ThemeMode.LIGHT to "浅色",
            ThemeMode.DARK to "深色",
        ).forEach { (mode, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { scope.launch { container.prefs.setTheme(mode) } }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.themeMode == mode, onClick = { scope.launch { container.prefs.setTheme(mode) } })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SettingSwitch("动态取色（Android 12+，默认关闭保持 MT 观感）", settings.dynamicColor) {
            scope.launch { container.prefs.setDynamicColor(it) }
        }

        SectionLabel("显示")
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
        SettingSwitch("默认显示隐藏文件", settings.showHidden) {
            scope.launch { container.prefs.setShowHidden(it) }
        }
        SettingSwitch("默认单列显示（手机窄屏）", settings.useSingleColumn) {
            scope.launch {
                container.prefs.setSingleColumn(it)
                // 同步到运行期的浏览模式（否则要重启才生效）
                container.browser.setBrowseMode(if (it) BrowseMode.SINGLE else BrowseMode.DUAL)
            }
        }
        SettingSwitch("记忆上次的双列路径", settings.rememberLastPath) {
            scope.launch { container.prefs.setRememberLastPath(it) }
        }
        SettingSwitch("底栏上滑调出书签", settings.bookmarkSwipe) {
            scope.launch { container.prefs.setBookmarkSwipe(it) }
        }

        // MT 0x7f110630：点击两次 = 区间选择（与长按连选互补，默认关 = MT 默认）
        SectionLabel("手势（复刻 MT 交互）")
        SettingSwitch("点击连选：开启后点击列表中任意两个项，将会自动选择它们中间所有的项", settings.tapRangeSelect) {
            scope.launch { container.prefs.setTapRangeSelect(it) }
        }
        Text(
            "· 长按 = 锚点 + 进入多选；**长按第二项 = 连选区间**（MT 0x7f110631）\n" +
                "· 左右滑动 ≥24dp = 进入多选；滑动跨行 = 区间选择（MT 0x7f1106f3 / 62f）\n" +
                "· 已多选态右滑 ≥48dp = 滑出该项的更多操作（MT 0x7f110697）\n" +
                "· 底栏长按「同步」= 过滤；底栏上滑 = 书签（MT 0x7f11028f / 7ca）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )

        SectionLabel("文件")
        SettingSwitch("保留文件时间（复制/解压/下载后保留原修改时间）", settings.preserveModifiedTime) {
            scope.launch { container.prefs.setPreserveModifiedTime(it) }
        }
        SettingSwitch("启动时进入首页（关闭 = 恢复上次双列路径）", settings.startAtHome) {
            scope.launch { container.prefs.setStartAtHome(it) }
        }
        Text(
            // MT 0x7f110200/201/202「文件列表显示策略」三档
            "文件列表显示：" + com.u707t.panelfm.core.common.MtListSubtitle.MODE_LABELS[settings.listDisplayMode],
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 1, 2).forEach { mode ->
                TextButton(onClick = { scope.launch { container.prefs.setListDisplayMode(mode) } }) {
                    Text(
                        listOf("不显示权限", "权限+大小", "时间+大小")[mode],
                        color = if (mode == settings.listDisplayMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SettingSwitch("保存文件时自动生成 .bak 备份", settings.backupOnSave) {
            scope.launch { container.prefs.setBackupOnSave(it) }
        }
        SettingSwitch("退出前双次确认（连按两次返回才退出）", settings.confirmExit) {
            scope.launch { container.prefs.setConfirmExit(it) }
        }

        SectionLabel("缩略图")
        SettingSwitch("移动数据下加载缩略图", settings.thumbnailsOnMobile) {
            scope.launch { container.prefs.setThumbsOnMobile(it) }
        }
        SettingSwitch("快速滚动时跳过缩略图加载（MT 同款手感）", settings.skipThumbsWhileScrolling) {
            scope.launch { container.prefs.setSkipThumbsWhileScrolling(it) }
        }
        Text(
            "超过该大小的图片不加载缩略图：" + com.u707t.panelfm.core.common.Fmt.size(settings.thumbnailMaxBytes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1L, 3L, 10L, 0L).forEach { mb ->
                TextButton(onClick = { scope.launch { container.prefs.setThumbnailMaxBytes(mb * 1024 * 1024) } }) {
                    Text(
                        if (mb == 0L) "不限制" else "${mb}MB",
                        color = if (mb * 1024 * 1024 == settings.thumbnailMaxBytes) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "缩略图加载超时取消：${if (settings.thumbnailTimeoutSec == 0) "不超时" else "${settings.thumbnailTimeoutSec} 秒"}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 3, 5, 10).forEach { sec ->
                TextButton(onClick = { scope.launch { container.prefs.setThumbnailTimeoutSec(sec) } }) {
                    Text(
                        if (sec == 0) "不超时" else "${sec}s",
                        color = if (sec == settings.thumbnailTimeoutSec) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionLabel("界面")
        Text(
            "浏览模式（MT 单列 / 双列 / 自动切换）：",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(BrowseMode.AUTO to "自动切换", BrowseMode.SINGLE to "单列", BrowseMode.DUAL to "双列").forEach { (mode, label) ->
                TextButton(onClick = { container.browser.setBrowseMode(mode) }) {
                    Text(
                        label,
                        color = if (container.browser.state.value.browseMode == mode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "对话框图标背景：" + listOf("深色背景（自适应）", "浅色背景（自适应）", "无背景（受系统影响）")[settings.dialogIconMode],
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 1, 2).forEach { mode ->
                TextButton(onClick = { scope.launch { container.prefs.setDialogIconMode(mode) } }) {
                    Text(
                        listOf("深色", "浅色", "无背景")[mode],
                        color = if (mode == settings.dialogIconMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "底部工具栏下边距（全面屏手势时更舒适）：${settings.bottomBarPaddingDp}dp",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
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

        SectionLabel("传输")
        SettingSwitch("默认信任自签证书（新的 WebDAV 连接）", settings.trustSelfSigned) {
            scope.launch { container.prefs.setTrustSelfSigned(it) }
        }
        Text("任务并发：${settings.maxConcurrentTasks}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
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

        SectionLabel("网络")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("全局 User-Agent", style = MaterialTheme.typography.bodyMedium)
                Text(
                    settings.userAgent,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { editUserAgent = true }) { Text("修改") }
        }

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

    if (editUserAgent) {
        var text by remember { mutableStateOf(settings.userAgent) }
        AlertDialog(
            onDismissRequest = { editUserAgent = false },
            title = { Text("全局 User-Agent") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("User-Agent") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "用于 WebDAV 等协议的请求头；改完立即生效（新请求即用新 UA）。留空恢复默认。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ua = text.trim().ifBlank { "PanelFM/${com.u707t.panelfm.BuildConfig.VERSION_NAME} (Android)" }
                    scope.launch { container.prefs.setUserAgent(ua) }
                    editUserAgent = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editUserAgent = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
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
