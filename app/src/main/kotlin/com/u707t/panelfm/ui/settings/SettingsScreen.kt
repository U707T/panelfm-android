package com.u707t.panelfm.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
import com.u707t.panelfm.core.ui.ThemePreset
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.data.ThemeMode
import com.u707t.panelfm.ui.browser.BrowseMode
import kotlinx.coroutines.launch

/**
 * 设置页（对齐 MT 的「设置」观感）。
 *
 * 只保留**真的会改变行为**的开关：主题 / 动态取色 / 显示 / 手势 / 文件 / 缩略图 /
 * 视频播放 / 界面 / 传输 / 网络。
 *
 * v2.1.4 删掉的项（早期 MT 复刻遗留，见 CHANGELOG）：记忆上次的双列路径（与「启动时进入首页」
 * 重复）、对话框图标背景（只覆盖 2 个对话框）、底部工具栏下边距（安全区已由 insets 处理）、
 * 缩略图的两个纯实现细节（滚动跳过 / 超时秒数）。
 */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.collectAsState()
    val browserState by container.browser.state.collectAsState()
    val scope = rememberCoroutineScope()
    val dark = LocalPanelDarkTheme.current
    var editUserAgent by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        // 顶栏固定（不随内容滚动），与其它二级页面观感一致
        MtScreenTopBar(title = "设置", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {

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

        SectionLabel("配色主题")
        Text(
            "参考 cfbrowser 的纸墨配色。选择预设会自动关闭「动态取色」；想跟随壁纸取色请在上方重新打开。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        ThemePreset.entries.forEach { preset ->
            ThemePresetRow(
                preset = preset,
                dark = dark,
                selected = !settings.dynamicColor && settings.themePreset == preset.id,
                onClick = {
                    scope.launch {
                        container.prefs.setDynamicColor(false)
                        container.prefs.setThemePreset(preset.id)
                    }
                },
            )
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
        SettingSwitch("底栏上滑调出书签", settings.bookmarkSwipe) {
            scope.launch { container.prefs.setBookmarkSwipe(it) }
        }

        // MT 0x7f110630：点击两次 = 区间选择（与长按连选互补，默认关 = MT 默认）
        SectionLabel("手势（复刻 MT 交互）")
        SettingSwitch("点击连选：开启后点击列表中任意两个项，将会自动选择它们中间所有的项", settings.tapRangeSelect) {
            scope.launch { container.prefs.setTapRangeSelect(it) }
        }
        Text(
            "· 长按 = 弹出该项二级菜单（不改选择、不进多选）\n" +
                "· 左右滑动 ≥24dp = 选中该行进入多选；再滑动另一项 = 连选两项之间的所有项\n" +
                "· 滑动后点击另一项 = 只加选这两项（点击会清掉滑动锚点，不再连出大区间）\n" +
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
            // MT 0x7f110200/201/202「文件列表显示策略」三档（按钮用短名，完整语义看上一行）
            "文件列表显示：" + com.u707t.panelfm.core.common.MtListSubtitle.MODE_LABELS[settings.listDisplayMode],
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // ⚠️ 按钮文案必须短：三档全名加起来 40+ 汉字，一行放不下（旧实现直接塞 MODE_LABELS，
            //    真机上三枚按钮互相挤压换行）
            listOf("时间", "权限+大小", "时间+大小").forEachIndexed { mode, short ->
                TextButton(onClick = { scope.launch { container.prefs.setListDisplayMode(mode) } }) {
                    Text(
                        short,
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
        // 视频 / 音频播放（v2.1.3）：目前只有「自动后台播放」一项。
        // 放在「缩略图」之后 —— 与「界面」「传输」这类全局偏好区分开，按媒体相关归组。
        SectionLabel("视频播放")
        SettingSwitch("自动后台播放（默认关闭）", settings.backgroundPlay) {
            scope.launch { container.prefs.setBackgroundPlay(it) }
        }
        Text(
            "视频与音频共用同一个播放器：开启后切到后台 / 熄屏继续出声（音量与播放列表照常）；\n" +
                "关闭则离开应用即暂停，不会有声音留在后台。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )

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
                        color = if (browserState.browseMode == mode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionLabel("传输")
        SettingSwitch("默认信任自签证书（新建的 WebDAV / FTP / FTPS / S3 连接）", settings.trustSelfSigned) {
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
                "PanelFM ${com.u707t.panelfm.BuildConfig.VERSION_NAME}（原生 Kotlin / Compose 双窗格文件管理器）\n" +
                    "协议：本地 / SFTP / FTP·FTPS / WebDAV / SMB / S3；压缩包挂载与读写（zip）\n" +
                    "预览：图片、音视频、PDF、Office / Markdown / CSV、EPUB、SQLite、APK、字体、文本（sora-editor）\n" +
                    "工具：网络工具箱（Ping / DNS / HTTP）、局域网扫描（网段 + 端口）、文本对比、回收站\n" +
                    "不含任何逆向工程功能（不做 DEX / Arsc / APK 编辑）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.padding(bottom = 30.dp)) {}
        }
    }

    if (editUserAgent) {
        var text by remember { mutableStateOf(settings.userAgent) }
        // F16：打开即聚焦
        val uaFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(60)
            runCatching { uaFocus.requestFocus() }
            keyboard?.show()
        }
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(uaFocus),
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

/** 配色预设行：三枚色卡（表面 / 强调 / 顶栏）+ 名称说明 + 单选。 */
@Composable
private fun ThemePresetRow(
    preset: ThemePreset,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = preset.scheme(dark)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemeSwatch(scheme.surface, scheme.outlineVariant)
            ThemeSwatch(scheme.primary, scheme.primary)
            ThemeSwatch(preset.topBar(dark), preset.topBar(dark))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(preset.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                preset.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun ThemeSwatch(color: Color, borderColor: Color) {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, borderColor, CircleShape),
    )
}
