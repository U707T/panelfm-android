package com.u707t.panelfm.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.TextEncodings
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 文本编辑器：可编辑 + 保存（保留权限）。
 *  - 编码识别（BOM / UTF-8 / GBK）并回写同编码
 *  - 字号缩放、查找替换、行数统计、未保存提示
 *  - > 2 MB 自动只读（大文件窗口化编辑器在后续版本）
 */
@Composable
fun EditorScreen(container: AppContainer, uri: VfsUri, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf<FileMetadata?>(null) }
    var text by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var charset by remember { mutableStateOf("UTF-8") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var fontSize by remember { mutableStateOf(14) }
    var status by remember { mutableStateOf<String?>(null) }
    var readOnly by remember { mutableStateOf(false) }
    var showReplace by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var originalMode by remember { mutableStateOf<Int?>(null) }

    val dirty = text != original

    LaunchedEffect(uri) {
        try {
            val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
            val info = withContext(Dispatchers.IO) { vfs.stat(uri) }
            meta = info
            originalMode = info.permissions
            if (info.size > MAX_EDIT_SIZE) {
                readOnly = true
                val bytes = readAtMost(vfs, uri, MAX_EDIT_SIZE)
                val decoded = TextEncodings.decode(bytes)
                charset = decoded.charset
                text = decoded.text
                original = text
            } else {
                val bytes = readAtMost(vfs, uri, MAX_EDIT_SIZE)
                val decoded = TextEncodings.decode(bytes)
                charset = decoded.charset
                text = decoded.text
                original = text
            }
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                if (!dirty || readOnly) onBack()
                else scope.launch {
                    saveText(container, uri, text, charset, originalMode, { msg -> status = msg }) { ok ->
                        if (ok) onBack()
                    }
                }
            }) { Text("← 返回") }
            Text(
                (meta?.name ?: uri.name) + if (dirty) " *" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(10) }) { Text("A-") }
            TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(28) }) { Text("A+") }
            TextButton(onClick = { showReplace = !showReplace }) { Text("查找") }
            TextButton(
                enabled = !readOnly && dirty,
                onClick = {
                    scope.launch {
                        saveText(container, uri, text, charset, originalMode, { msg -> status = msg }) { ok ->
                            if (ok) {
                                original = text
                                container.browser.refreshAll()
                            }
                        }
                    }
                },
            ) { Text("保存", color = if (!readOnly && dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        if (showReplace) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        BasicTextField(
                            value = findText,
                            onValueChange = { findText = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                        )
                    }
                    TextButton(onClick = {
                        if (findText.isNotEmpty()) {
                            val idx = text.indexOf(findText)
                            status = if (idx < 0) "未找到" else "已找到（第 ${text.take(idx).count { it == '\n' } + 1} 行）"
                        }
                    }) { Text("查找") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        BasicTextField(
                            value = replaceText,
                            onValueChange = { replaceText = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                        )
                    }
                    TextButton(
                        enabled = !readOnly && findText.isNotEmpty(),
                        onClick = {
                            val count = text.windowed(findText.length, 1).count { it == findText }
                            text = text.replace(findText, replaceText)
                            status = "已替换 $count 处"
                        },
                    ) { Text("全部替换") }
                }
            }
        }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        when {
            loading -> LoadingState()
            error != null -> ErrorState("打开失败：$error")
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                if (readOnly) {
                    Text(
                        "只读：文件大于 ${Fmt.size(MAX_EDIT_SIZE)}（可另存或复制到本地后编辑）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { if (!readOnly) text = it },
                    textStyle = TextStyle(
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.35f).sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${text.count { it == '\n' } + 1} 行 · ${text.length} 字符 · ${charset}" +
                        (meta?.size?.let { " · ${Fmt.size(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

private const val MAX_EDIT_SIZE = 2L * 1024 * 1024

private suspend fun readAtMost(
    vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
    uri: VfsUri,
    max: Long,
): ByteArray = withContext(Dispatchers.IO) {
    val reader = vfs.openRead(uri)
    try {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < max) {
            val n = reader.read(buf, 0, minOf(buf.size.toLong(), max - total).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        out.toByteArray()
    } finally {
        runCatching { reader.close() }
    }
}

private suspend fun saveText(
    container: AppContainer,
    uri: VfsUri,
    text: String,
    charset: String,
    originalMode: Int?,
    onStatus: (String?) -> Unit,
    onDone: (Boolean) -> Unit,
) {
    try {
        val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
        val bytes = when {
            charset.startsWith("UTF-8") -> text.toByteArray(Charsets.UTF_8)
            charset == "GBK" -> text.toByteArray(java.nio.charset.Charset.forName("GBK"))
            charset == "UTF-16LE" -> text.toByteArray(Charsets.UTF_16LE)
            charset == "UTF-16BE" -> text.toByteArray(Charsets.UTF_16BE)
            else -> text.toByteArray(Charsets.UTF_8)
        }
        withContext(Dispatchers.IO) {
            val writer = vfs.openWrite(uri, size = bytes.size.toLong(), offset = 0L)
            writer.write(bytes, 0, bytes.size)
            writer.commit()
            // 保留原有权限（本地/SFTP/FTP 支持 chmod 时）
            if (originalMode != null && vfs.capabilities.permissions) {
                runCatching { vfs.setPermissions(uri, originalMode) }
            }
        }
        onStatus("已保存（$charset，${bytes.size} 字节）")
        onDone(true)
    } catch (e: Exception) {
        onStatus("保存失败：${e.message}")
        onDone(false)
    }
}
