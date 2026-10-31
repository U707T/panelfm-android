package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.vfs.archive.ArchiveCompressor
import com.u707t.panelfm.core.ui.ClaudeDropdownMenu
import com.u707t.panelfm.core.ui.ClaudeMenuItem

// ---------------------------------------------------------------------------
// 压缩包口令输入（第 5 批 🔴1：加密包读侧接线 —— 打开 / 解压 / 完整性测试前的输入框）
// ---------------------------------------------------------------------------

@Composable
fun ArchivePasswordDialog(
    archiveName: String,
    wrong: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val submit = { if (field.isNotEmpty()) onConfirm(field) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    // 口令错误后重开：清掉旧输入（否则用户要先手动全选删除），并重新聚焦
    LaunchedEffect(wrong) {
        if (wrong && field.isNotEmpty()) {
            field = ""
            show = false
            runCatching { focusRequester.requestFocus() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入压缩包口令") },
        text = {
            Column {
                Text(
                    "「$archiveName」已加密，请输入口令。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (wrong) {
                    Text(
                        "口令不正确或数据已损坏，请重试。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text("口令") },
                    singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { submit() },
                    ),
                    trailingIcon = {
                        Box(Modifier.clickable { show = !show }.padding(horizontal = 10.dp)) {
                            MtVectorIcon(
                                icon = if (show) MtIcon.EYE_OFF else MtIcon.EYE,
                                size = 20.dp,
                                tint = if (show) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = field.isNotEmpty()) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------
// 压缩对话框（复刻 MT 0x7f0c0080「创建压缩文件」）
//
// MT 布局：文件名 / 格式（Spinner）/ 压缩级别（Spinner，取自 0x7f030020 数组
// 「仅存储·极速压缩·快速压缩·标准压缩·最大压缩·极限压缩·APK模式」）/ 密码（不加密请留空，👁）
// （MT 的「同时加密文件名」未复刻：commons-compress 1.27.1 无法加密 7z 文件名，见第 5 批 🟡4）
// ---------------------------------------------------------------------------

@Composable
fun MtCompressDialog(
    itemCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (
        toOther: Boolean,
        format: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format,
        fileName: String?,
        level: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level,
        password: String?,
    ) -> Unit,
) {
    var format by remember { mutableStateOf(com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.ZIP) }
    var level by remember { mutableStateOf(com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level.NORMAL) }
    var fileName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var toOther by remember { mutableStateOf(false) }
    var formatMenu by remember { mutableStateOf(false) }
    var levelMenu by remember { mutableStateOf(false) }
    // F16：打开即聚焦「文件名」（留空自动命名，但想命名可直接打字）
    val fileNameFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { fileNameFocus.requestFocus() }
        keyboard?.show()
    }

    val supportsPassword = format.supportsPassword
    // tar 系（tar / tar.gz / tar.bz2 / tar.xz）没有「压缩级别」概念
    val levelApplies = !format.label.startsWith("tar")
    // 文件名是单段：带路径分隔符或 `.` / `..` 时不能提交（否则会被拼成当前目录之外的路径）
    val trimmedName = fileName.trim()
    val nameBad = trimmedName.isNotEmpty() &&
        (trimmedName.contains('/') || trimmedName.contains('\\') || trimmedName == "." || trimmedName == "..")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建压缩文件") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 保存位置（MT 的「压缩到另一窗口路径」）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("保存到：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { toOther = false }) {
                        Text("当前目录", color = if (!toOther) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = { toOther = true }) {
                        Text("另一窗口", color = if (toOther) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text("文件名", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    placeholder = { Text("留空则按选中项自动命名") },
                    singleLine = true,
                    isError = nameBad,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fileNameFocus),
                )
                if (nameBad) {
                    Text(
                        "文件名不能是 . / .. 或包含路径分隔符（/ \\）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // 格式
                Text("格式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Box {
                    TextButton(onClick = { formatMenu = true }) { Text(format.label + "  ▾") }
                    ClaudeDropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                        com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.entries.forEach { f ->
                            ClaudeMenuItem(
                                label = f.label,
                                checked = f == format,
                            ) {
                                format = f
                                formatMenu = false
                                // 切到不支持加密的格式时，连密码一起清掉（否则确定后必报「不支持加密」，且用户看不到密码字段）
                                if (!f.supportsPassword) {
                                    password = ""
                                }
                            }
                        }
                    }
                }
                // 压缩级别
                if (levelApplies) {
                    Text("压缩级别", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Box {
                        TextButton(onClick = { levelMenu = true }) { Text(level.label + "  ▾") }
                        ClaudeDropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                            com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level.entries.forEach { l ->
                                ClaudeMenuItem(
                                    label = l.label,
                                    checked = l == level,
                                ) { level = l; levelMenu = false }
                            }
                        }
                    }
                }
                // 密码
                Text("密码（不加密请留空）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = supportsPassword,
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Box(Modifier.clickable { showPassword = !showPassword }.padding(horizontal = 10.dp)) {
                        MtVectorIcon(
                                icon = if (showPassword) MtIcon.EYE_OFF else MtIcon.EYE,
                                size = 20.dp,
                                tint = if (showPassword) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!supportsPassword) {
                    Text(
                        "只有 ZIP / 7z 支持加密；tar 系列无加密能力。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 说明行（第 5 批 🟡4：旧的「同时加密文件名」开关从未生效 —— commons-compress
                // 1.27.1 既不写 7z 头加密、ZIP 也无法隐藏文件名；删除开关，改为如实说明。）
                Text(
                    when {
                        password.isNotEmpty() && format == com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.SEVEN_Z ->
                            "7z 带口令：内容加密；文件名不加密（当前实现限制，无法关闭）。"
                        password.isNotEmpty() && format == com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.ZIP ->
                            "ZIP 传统加密无法隐藏文件名，将仅加密内容。"
                        else -> "共 $itemCount 项"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !nameBad,
                onClick = {
                    onConfirm(
                        toOther,
                        format,
                        fileName.trim().takeIf { it.isNotEmpty() },
                        if (levelApplies) level else com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level.NORMAL,
                        password.takeIf { it.isNotEmpty() },
                    )
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------
// 解压对话框（复刻 MT 0x7f0c00ce「解压」）
//
// MT 布局：○ 解压到单独的文件夹 / ○ 解压到当前目录 / ● 解压到文件夹…（默认）+ 路径输入框
// + ☐ 基于另一窗口路径。
// ---------------------------------------------------------------------------

/** 解压目标选项（MT 的三个单选） */
enum class ExtractTarget { OWN_FOLDER, HERE, PICK_FOLDER }

@Composable
fun MtExtractDialog(
    archiveName: String,
    currentDirPath: String,
    otherPanePath: String?,
    onDismiss: () -> Unit,
    onConfirm: (target: ExtractTarget, customPath: String?, useOtherPane: Boolean) -> Unit,
) {
    var target by remember { mutableStateOf(ExtractTarget.PICK_FOLDER) }
    var customPath by remember { mutableStateOf(currentDirPath) }
    // F10：旧实现只写不读（死控件）；现在通过 onConfirm 输出，由调用方决定解压目标
    var useOtherPane by remember { mutableStateOf(false) }
    // F16：打开即聚焦路径框（高频输入不再需要先点一下）
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(target) {
        if (target == ExtractTarget.PICK_FOLDER) {
            kotlinx.coroutines.delay(60)
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解压") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf(
                    ExtractTarget.OWN_FOLDER to "解压到单独的文件夹（$archiveName）",
                    ExtractTarget.HERE to "解压到当前目录",
                    ExtractTarget.PICK_FOLDER to "解压到文件夹…",
                ).forEach { (t, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { target = t }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = target == t, onClick = { target = t })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (target == ExtractTarget.PICK_FOLDER) {
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = { customPath = it },
                        label = { Text("目标路径") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                    Text(
                        "· 路径与当前目录相同 → 进入「选择当前目录」模式；不同 → 直接解压到该路径\n" +
                            "· 相对路径会补全为绝对路径（以 / 开头）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (otherPanePath != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val checked = !useOtherPane
                                useOtherPane = checked
                                // 勾选 = 预填另一窗口路径；取消勾选且未被改过 → 还原默认
                                customPath = when {
                                    checked -> otherPanePath
                                    customPath == otherPanePath -> currentDirPath
                                    else -> customPath
                                }
                            }
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = useOtherPane,
                            onCheckedChange = { checked ->
                                useOtherPane = checked
                                customPath = when {
                                    checked -> otherPanePath
                                    customPath == otherPanePath -> currentDirPath
                                    else -> customPath
                                }
                            },
                        )
                        Text("基于另一窗口路径（$otherPanePath）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    target,
                    if (target == ExtractTarget.PICK_FOLDER) customPath.trim().takeIf { it.isNotEmpty() } else null,
                    useOtherPane,
                )
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
