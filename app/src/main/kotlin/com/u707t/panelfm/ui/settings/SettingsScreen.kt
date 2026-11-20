package com.u707t.panelfm.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.BuildConfig
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.data.ThemeMode
import com.u707t.panelfm.core.model.VerifyMode
import com.u707t.panelfm.core.ui.DividerPx
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.ThemePreset
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.ui.browser.BrowseMode
import kotlinx.coroutines.launch

/**
 * 设置页（Clean 版）。
 *
 * 只做三件事：
 *  1. **分组**：外观 / 浏览 / 手势 / 文件 / 媒体 / 传输 / 回收站 / 网络 / 关于；
 *  2. **值右置**：每行 = 标签 + 当前值 + ›（或开关），多选进底部 sheet；
 *  3. **零说明段**：解释文字不进主列表；User-Agent 的编辑收进对话框，主列表只留标签和当前值。
 *
 * 所有开关仍然接原来那 22 个 `prefs.set*`，一个不少。
 */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.collectAsState()
    val browserState by container.browser.state.collectAsState()
    val scope = rememberCoroutineScope()
    val dark = LocalPanelDarkTheme.current

    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var editUserAgent by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(title = "设置", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 2.dp, bottom = 32.dp),
        ) {
            SettingsGroup("外观") {
                SettingsRow(
                    title = "主题",
                    value = settings.themeMode.label(),
                    onClick = { sheet = SettingsSheet.THEME_MODE },
                )
                SettingsRow(
                    title = "配色",
                    value = if (settings.dynamicColor) "动态取色"
                    else ThemePreset.byId(settings.themePreset).label,
                    onClick = { sheet = SettingsSheet.PALETTE },
                )
                SettingsRow(
                    title = "字体大小",
                    value = fontScaleLabel(settings.fontScaleLevel),
                    onClick = { sheet = SettingsSheet.FONT_SCALE },
                )
                SettingsSwitchRow(
                    title = "动态取色",
                    checked = settings.dynamicColor,
                    onCheckedChange = { on -> scope.launch { container.prefs.setDynamicColor(on) } },
                    divider = false,
                )
            }

            SettingsGroup("浏览") {
                SettingsRow(
                    title = "默认视图",
                    value = browserState.browseMode.label,
                    onClick = { sheet = SettingsSheet.BROWSE_MODE },
                )
                SettingsSwitchRow(
                    title = "显示隐藏文件",
                    checked = settings.showHidden,
                    onCheckedChange = { on -> scope.launch { container.prefs.setShowHidden(on) } },
                )
                SettingsSwitchRow(
                    title = "时间显示到秒",
                    checked = settings.showSeconds,
                    onCheckedChange = { on -> scope.launch { container.prefs.setShowSeconds(on) } },
                )
                SettingsRow(
                    title = "列表副标题",
                    value = listSubtitleLabel(settings.listDisplayMode),
                    onClick = { sheet = SettingsSheet.LIST_SUBTITLE },
                    divider = false,
                )
            }

            SettingsGroup("手势") {
                SettingsSwitchRow(
                    title = "点击连选",
                    checked = settings.tapRangeSelect,
                    onCheckedChange = { on -> scope.launch { container.prefs.setTapRangeSelect(on) } },
                )
                SettingsSwitchRow(
                    title = "底栏上滑书签",
                    checked = settings.bookmarkSwipe,
                    onCheckedChange = { on -> scope.launch { container.prefs.setBookmarkSwipe(on) } },
                    divider = false,
                )
            }

            SettingsGroup("文件") {
                SettingsSwitchRow(
                    title = "启动时进入首页",
                    checked = settings.startAtHome,
                    onCheckedChange = { on -> scope.launch { container.prefs.setStartAtHome(on) } },
                )
                SettingsSwitchRow(
                    title = "保留修改时间",
                    checked = settings.preserveModifiedTime,
                    onCheckedChange = { on -> scope.launch { container.prefs.setPreserveModifiedTime(on) } },
                )
                SettingsSwitchRow(
                    title = "保存时生成 .bak",
                    checked = settings.backupOnSave,
                    onCheckedChange = { on -> scope.launch { container.prefs.setBackupOnSave(on) } },
                )
                SettingsSwitchRow(
                    title = "退出前二次确认",
                    checked = settings.confirmExit,
                    onCheckedChange = { on -> scope.launch { container.prefs.setConfirmExit(on) } },
                    divider = false,
                )
            }

            SettingsGroup("媒体") {
                SettingsSwitchRow(
                    title = "移动数据下加载缩略图",
                    checked = settings.thumbnailsOnMobile,
                    onCheckedChange = { on -> scope.launch { container.prefs.setThumbsOnMobile(on) } },
                )
                SettingsRow(
                    title = "缩略图上限",
                    value = if (settings.thumbnailMaxBytes <= 0L) "不限"
                    else Fmt.size(settings.thumbnailMaxBytes),
                    onClick = { sheet = SettingsSheet.THUMB_SIZE },
                )
                SettingsSwitchRow(
                    title = "自动后台播放",
                    checked = settings.backgroundPlay,
                    onCheckedChange = { on -> scope.launch { container.prefs.setBackgroundPlay(on) } },
                    divider = false,
                )
            }

            SettingsGroup("传输") {
                SettingsSwitchRow(
                    title = "信任自签证书",
                    checked = settings.trustSelfSigned,
                    onCheckedChange = { on -> scope.launch { container.prefs.setTrustSelfSigned(on) } },
                )
                SettingsRow(
                    title = "传输校验",
                    value = settings.verifyMode.label(),
                    onClick = { sheet = SettingsSheet.VERIFY },
                )
                SettingsRow(
                    title = "并发任务",
                    value = settings.maxConcurrentTasks.toString(),
                    onClick = { sheet = SettingsSheet.CONCURRENCY },
                    divider = false,
                )
            }

            SettingsGroup("回收站") {
                SettingsRow(
                    title = "保留天数",
                    value = if (settings.trashRetentionDays == 0) "不清理"
                    else "${settings.trashRetentionDays} 天",
                    onClick = { sheet = SettingsSheet.TRASH_DAYS },
                )
                SettingsRow(
                    title = "容量上限",
                    value = if (settings.trashMaxBytes <= 0L) "不限" else Fmt.size(settings.trashMaxBytes),
                    onClick = { sheet = SettingsSheet.TRASH_SIZE },
                    divider = false,
                )
            }

            SettingsGroup("网络") {
                SettingsRow(
                    title = "全局 User-Agent",
                    value = settings.userAgent,
                    onClick = { editUserAgent = true },
                    divider = false,
                )
            }

            SettingsGroup("关于") {
                SettingsRow(
                    title = "版本",
                    value = BuildConfig.VERSION_NAME,
                    onClick = { showAbout = true },
                    divider = false,
                )
            }
        }
    }

    when (sheet) {
        SettingsSheet.THEME_MODE -> ChoiceSheet(
            title = "主题",
            options = listOf(
                ChoiceOption(ThemeMode.SYSTEM, "跟随系统"),
                ChoiceOption(ThemeMode.LIGHT, "浅色"),
                ChoiceOption(ThemeMode.DARK, "深色"),
            ),
            selected = settings.themeMode,
            onSelect = { mode ->
                scope.launch { container.prefs.setTheme(mode) }
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        SettingsSheet.PALETTE -> PaletteSheet(
            selectedId = settings.themePreset,
            dynamic = settings.dynamicColor,
            dark = dark,
            onSelect = { preset ->
                scope.launch {
                    container.prefs.setDynamicColor(false)
                    container.prefs.setThemePreset(preset.id)
                }
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        SettingsSheet.FONT_SCALE -> ChoiceSheet(
            title = "字体大小",
            options = listOf(
                ChoiceOption(0, "紧凑"),
                ChoiceOption(1, "适中"),
                ChoiceOption(2, "标准"),
            ),
            selected = settings.fontScaleLevel,
            onSelect = { level ->
                scope.launch { container.prefs.setFontScaleLevel(level) }
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        SettingsSheet.BROWSE_MODE -> ChoiceSheet(
            title = "默认视图",
            options = BrowseMode.entries.map { ChoiceOption(it, it.label) },
            selected = browserState.browseMode,
            onSelect = { mode ->
                container.browser.setBrowseMode(mode)
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        SettingsSheet.LIST_SUBTITLE -> ChoiceSheet(
            title = "列表副标题",
            options = listOf(
                ChoiceOption(0, "时间"),
                ChoiceOption(1, "权限"),
            ),
            selected = settings.listDisplayMode,
            onSelect = { mode ->
                scope.launch { container.prefs.setListDisplayMode(mode) }
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        SettingsSheet.THUMB_SIZE -> ChoiceSheet(
            title = "缩略图上限",
            options = listOf(
                ChoiceOption(1L shl 20, "1 MB"),
                ChoiceOption(3L shl 20, "3 MB"),
                ChoiceOption(10L shl 20, "10 MB"),
                ChoiceOption(0L, "不限"),
            ),
            selected = settings.thumbnailMaxBytes,
            onSelect = { bytes ->
                scope.launch { container.prefs.setThumbnailMaxBytes(bytes) }
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        SettingsSheet.VERIFY -> ChoiceSheet(
            title = "传输校验",
            options = listOf(
                ChoiceOption(VerifyMode.NONE, "不校验"),
                ChoiceOption(VerifyMode.SIZE, "仅大小"),
                ChoiceOption(VerifyMode.HASH, "SHA-256"),
            ),
            selected = settings.verifyMode,
            onSelect = { mode ->
                scope.launch { container.prefs.setVerifyMode(mode) }
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        SettingsSheet.CONCURRENCY -> ChoiceSheet(
            title = "并发任务",
            options = (1..4).map { ChoiceOption(it, it.toString()) },
            selected = settings.maxConcurrentTasks,
            onSelect = { n ->
                scope.launch {
                    container.prefs.setMaxConcurrent(n)
                    container.engine.updateConcurrency(n)
                }
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        SettingsSheet.TRASH_DAYS -> ChoiceSheet(
            title = "回收站保留",
            options = listOf(
                ChoiceOption(0, "不清理"),
                ChoiceOption(7, "7 天"),
                ChoiceOption(30, "30 天"),
                ChoiceOption(90, "90 天"),
            ),
            selected = settings.trashRetentionDays,
            onSelect = { days ->
                scope.launch { container.prefs.setTrashRetentionDays(days) }
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        SettingsSheet.TRASH_SIZE -> ChoiceSheet(
            title = "回收站容量",
            options = listOf(
                ChoiceOption(0L, "不限"),
                ChoiceOption(512L shl 20, "512 MB"),
                ChoiceOption(1024L shl 20, "1 GB"),
                ChoiceOption(4096L shl 20, "4 GB"),
            ),
            selected = settings.trashMaxBytes,
            onSelect = { bytes ->
                scope.launch { container.prefs.setTrashMaxBytes(bytes) }
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        null -> Unit
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于 PanelFM") },
            text = {
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}\n" +
                        "本地 / SFTP / FTP · FTPS / WebDAV / SMB / S3 / 压缩包\n" +
                        "预览：图片 · 音视频 · PDF · Office · EPUB · SQLite · APK · 文本",
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("关闭") }
            },
        )
    }

    if (editUserAgent) {
        var text by remember(settings.userAgent) { mutableStateOf(settings.userAgent) }
        val uaFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(60)
            runCatching { uaFocus.requestFocus() }
            keyboard?.show()
        }
        AlertDialog(
            onDismissRequest = { editUserAgent = false },
            title = { Text("User-Agent") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("留空恢复默认") },
                    modifier = Modifier.fillMaxWidth().focusRequester(uaFocus),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ua = text.trim().ifBlank { "PanelFM/${BuildConfig.VERSION_NAME} (Android)" }
                    scope.launch { container.prefs.setUserAgent(ua) }
                    editUserAgent = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editUserAgent = false }) { Text("取消") }
            },
        )
    }
}

// ----------------------------------------------------------------------------- 组件

private enum class SettingsSheet {
    THEME_MODE, PALETTE, FONT_SCALE, BROWSE_MODE, LIST_SUBTITLE,
    THUMB_SIZE, VERIFY, CONCURRENCY, TRASH_DAYS, TRASH_SIZE,
}

private data class ChoiceOption<T>(val value: T, val label: String)

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 22.dp, bottom = 2.dp),
        )
        content()
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else Modifier
                )
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp).padding(start = 12.dp),
                )
            }
            if (onClick != null) {
                MtVectorIcon(
                    icon = MtIcon.CHEVRON_R,
                    size = 16.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (divider) DividerPx(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    divider: Boolean = true,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                // 整行是一个 Switch 语义节点；Switch 自己不接点击，避免 TalkBack 出现两个开关
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = null)
        }
        if (divider) DividerPx(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceSheet(
    title: String,
    options: List<ChoiceOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        ) {
            SheetTitle(title)
            options.forEach { option ->
                val isSelected = option.value == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = {
                                // 先播完收起动画，再让父层把 sheet 置空；否则会「啪」地消失
                                sheetScope.launch {
                                    sheetState.hide()
                                    onSelect(option.value)
                                }
                            },
                        )
                        .height(52.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        MtVectorIcon(
                            icon = MtIcon.CHECK,
                            size = 18.dp,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaletteSheet(
    selectedId: String,
    dynamic: Boolean,
    dark: Boolean,
    onSelect: (ThemePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        ) {
            SheetTitle("配色")
            ThemePreset.entries.forEach { preset ->
                val isSelected = !dynamic && preset.id == selectedId
                val scheme = preset.scheme(dark)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = {
                                sheetScope.launch {
                                    sheetState.hide()
                                    onSelect(preset)
                                }
                            },
                        )
                        .height(56.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Swatch(scheme.surface, scheme.outlineVariant)
                        Swatch(scheme.primary, scheme.primary)
                        // 顶栏色卡：Linear 浅色是白顶栏，边框必须用 outlineVariant，否则白底上看不见
                        Swatch(preset.topBar(dark), scheme.outlineVariant)
                    }
                    Text(
                        preset.label,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        modifier = Modifier.weight(1f).padding(start = 14.dp),
                    )
                    if (isSelected) {
                        MtVectorIcon(
                            icon = MtIcon.CHECK,
                            size = 18.dp,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
    )
}

@Composable
private fun Swatch(color: Color, borderColor: Color) {
    Canvas(Modifier.size(16.dp)) {
        drawCircle(color, radius = size.minDimension / 2f)
        drawCircle(
            color = borderColor,
            radius = size.minDimension / 2f - 0.5f,
            style = Stroke(width = 1f),
        )
    }
}

// ----------------------------------------------------------------------------- 文案

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun fontScaleLabel(level: Int): String = when (level) {
    0 -> "紧凑"
    2 -> "标准"
    else -> "适中"
}

private fun listSubtitleLabel(mode: Int): String =
    if (mode == 1) "权限" else "时间"

private fun VerifyMode.label(): String = when (this) {
    VerifyMode.NONE -> "不校验"
    VerifyMode.SIZE -> "仅大小"
    VerifyMode.HASH -> "SHA-256"
}