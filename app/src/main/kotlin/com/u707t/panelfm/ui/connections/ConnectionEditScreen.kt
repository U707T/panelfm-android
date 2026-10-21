package com.u707t.panelfm.ui.connections

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.DefaultGateway
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.sftp.SshKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 连接编辑器：FTP / FTPS / WebDAV / SMB / S3 / SFTP。
 *
 * 结构（v2.0.24 拆分）：
 *  - [ConnectionEditState]：全部表单字段 + 配置/口令构造 + 协议切换迁移规则；
 *  - [ConnectionCommonFields] / [SftpSection] / [SmbSection] / [S3Section] / [ProtocolSwitches]：
 *    按协议分段的渲染（`ConnectionFormSections.kt`）；
 *  - 本文件只负责：编辑态加载（失败/已删除分开处理）、私钥选择、保存/测试流程、页面骨架。
 */
@Composable
fun ConnectionEditScreen(
    container: AppContainer,
    connectionId: Long?,
    onBack: () -> Unit,
    prefillHost: String? = null,
    prefillPort: Int? = null,
    /** 从侧边栏「添加网络存储 ▶」进入时预选协议 */
    initialType: ConnectionType? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var loadingExisting by remember(connectionId) { mutableStateOf(connectionId != null) }
    var existing by remember(connectionId) { mutableStateOf<ConnectionConfig?>(null) }
    var rawSecret by remember(connectionId) { mutableStateOf<String?>(null) }
    // 读库失败 ≠ 记录不存在：前者必须给错误 + 重试，后者提示已删除。
    // 旧实现把两者都当 null，编辑页会静默变成「添加」，保存时插一条重复连接。
    var loadError by remember(connectionId) { mutableStateOf<String?>(null) }
    var missingExisting by remember(connectionId) { mutableStateOf(false) }
    var loadAttempt by remember(connectionId) { mutableStateOf(0) }

    LaunchedEffect(connectionId, loadAttempt) {
        if (connectionId == null) {
            loadingExisting = false
            return@LaunchedEffect
        }
        loadingExisting = true
        loadError = null
        missingExisting = false
        existing = null
        val result = withContext(Dispatchers.IO) {
            runCatching { container.connectionDao.all().firstOrNull { it.id == connectionId } }
        }
        result.onFailure { e ->
            loadError = e.message ?: e.javaClass.simpleName
        }.onSuccess { loaded ->
            if (loaded == null) {
                missingExisting = true
            } else {
                // loadSecret 内部已切 IO（查 secret_ref + Keystore 解密）
                rawSecret = runCatching { container.loadSecret(loaded.id) }.getOrNull()
                existing = loaded
            }
        }
        loadingExisting = false
    }

    if (loadingExisting) {
        Column(Modifier.fillMaxSize().safeAreaPadding()) {
            MtScreenTopBar(title = if (connectionId == null) "添加连接" else "编辑连接", onBack = onBack)
            LoadingState("正在读取连接配置…")
        }
        return
    }
    loadError?.let { message ->
        Column(Modifier.fillMaxSize().safeAreaPadding()) {
            MtScreenTopBar(title = "编辑连接", onBack = onBack)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("读取连接配置失败", style = MaterialTheme.typography.titleMedium)
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { loadAttempt++ }) { Text("重试") }
            }
        }
        return
    }
    if (missingExisting) {
        Column(Modifier.fillMaxSize().safeAreaPadding()) {
            MtScreenTopBar(title = "编辑连接", onBack = onBack)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("连接不存在或已被删除", style = MaterialTheme.typography.titleMedium)
                Text(
                    "它可能刚在侧拉栏里被删除。返回列表刷新后再试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onBack) { Text("返回列表") }
            }
        }
        return
    }

    val state = remember(connectionId) {
        ConnectionEditState(existing, rawSecret, prefillHost, prefillPort, initialType)
    }

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var publicKeyLine by remember { mutableStateOf<String?>(null) }
    var showThumbOptions by remember { mutableStateOf(false) }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) {
                    // lastPathSegment 来自外部 URI，可能带 `/` 或 `..`：只取 basename，防止写到 keysDir 之外
                    val picked = File(uri.lastPathSegment ?: "id_key").name
                        .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "id_key"
                    val dest = File(container.appDirs.keysDir, picked)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    // 私钥仅本应用可读写（相当于 chmod 600）
                    dest.setReadable(false, false)
                    dest.setReadable(true, true)
                    dest.setWritable(false, false)
                    dest.setWritable(true, true)
                    dest.absolutePath
                }
                state.keyPath = fileName
                status = "已导入私钥：${File(fileName).name}"
            } catch (e: Exception) {
                status = "私钥导入失败：${e.message}"
            }
        }
    }

    // 端口跟随协议默认值：仅当当前端口仍是「上一个协议的默认值」（用户没手改过）才更新——
    // 编辑既有连接切协议（FTP→SMB 后端口仍 21）也一并修掉；prefill（局域网扫描）不覆盖。
    var prevType by remember { mutableStateOf(state.type) }
    LaunchedEffect(state.type) {
        if (prefillPort == null && state.port == prevType.defaultPort.toString()) {
            state.port = state.type.defaultPort.toString()
        }
        prevType = state.type
    }

    // 保存 / 测试进行中拦截系统返回：中途退出会留下「secret 已更新、配置未更新」的半态
    BackHandler(enabled = busy) { status = "正在与服务器交互，请稍候…" }

    /**
     * 「小闪电」快捷按钮：读取当前默认网关并填入主机 / IP（WebDAV 则拼成 URL）。
     *
     * 读网关要碰系统路由表（/proc 兜底还要读文件），放 IO 上；读不到就明确提示，
     * **不做「猜一个 .1 填进去」**——网段改过网关时那会把用户引到错误的主机上排查。
     */
    fun fillDefaultGateway() {
        if (busy) return
        scope.launch {
            val gateway = withContext(Dispatchers.IO) {
                runCatching { DefaultGateway.ipv4(context) }.getOrNull()
            }
            if (gateway.isNullOrBlank()) {
                status = "未读取到默认网关（当前网络可能没有默认路由，如仅移动数据 / VPN）"
                return@launch
            }
            if (state.type == ConnectionType.WEBDAV) {
                // WebDAV 只有 URL 一行：按当前端口 / HTTPS / 路径把网关拼成完整 URL；
                // 已经是该网关时只提示，不覆盖用户手填的路径
                if (WebDavUrl.parse(state.url)?.host == gateway) {
                    status = "当前 URL 已指向默认网关：$gateway"
                    return@launch
                }
                val davPort = state.port.toIntOrNull() ?: state.type.defaultPort
                state.host = gateway
                state.port = davPort.toString()
                state.url = WebDavUrl.build(gateway, davPort, state.secure, state.basePath)
                status = "已填入默认网关：$gateway"
                return@launch
            }
            status = if (gateway == state.host.trim()) {
                "当前主机就是默认网关：$gateway"
            } else {
                state.host = gateway
                "已填入默认网关：$gateway"
            }
        }
    }

    fun doSave() {
        busy = true
        status = null
        scope.launch {
            try {
                if (!state.applyUrlIfWebDav()) {
                    status = "URL 格式不正确，示例：http://192.168.1.9:5244/dav"
                    busy = false
                    return@launch
                }
                // 端口范围校验：旧实现 filter 掉非数字后就存，`99999` 这类值会一路写进 DB，
                // 直到连接时才以「连接被拒绝」的形式暴露，用户很难联想到是配置问题。
                val portNumber = state.port.toIntOrNull() ?: state.type.defaultPort
                if (portNumber !in 1..65535) {
                    status = "端口必须在 1–65535 之间（当前：${state.port}）"
                    busy = false
                    return@launch
                }
                state.smbProblem?.let { problem ->
                    status = problem
                    busy = false
                    return@launch
                }
                val config = state.buildConfig()
                val secret = state.buildSecret()
                // 局部快照：existing 是 delegated property，无法智能转换（且异步写入后可能变）
                val editing = existing
                if (editing != null) {
                    val oldSecret = runCatching { container.loadSecret(editing.id) }.getOrNull()
                    val secretSaved = container.saveSecret(config.id, secret)
                    if (!secretSaved) {
                        status = "配置未保存：口令写入失败（系统密钥库不可用）。请稍后重试。"
                        busy = false
                        return@launch
                    }
                    // update 返回受影响行数：记录在别处被删时不能报成功（secret 已写，回滚成旧值）
                    val updated = container.connectionDao.update(config)
                    if (updated <= 0) {
                        runCatching { container.saveSecret(config.id, oldSecret) }
                        status = "保存失败：连接已被删除，请返回列表刷新后重试"
                        busy = false
                        return@launch
                    }
                    // 会话键或口令变化 → 断掉旧会话，下次打开用新配置
                    if (editing.sessionKey != config.sessionKey || oldSecret != secret) {
                        container.disconnectConnection(editing)
                    }
                } else {
                    val id = container.connectionDao.insert(config)
                    if (id <= 0) {
                        // SQLite insert 失败返回 -1：不检查会给 -1 写一条孤儿 secret，还当保存成功返回
                        status = "创建失败：无法写入数据库（存储空间不足？）"
                        busy = false
                        return@launch
                    }
                    if (!container.saveSecret(id, secret)) {
                        // 新连接的 secret 写失败时不留下半成品配置。
                        container.connectionDao.delete(id)
                        status = "连接未创建：口令写入失败（系统密钥库不可用）。请重试。"
                        busy = false
                        return@launch
                    }
                }
                container.reloadConnections()
                busy = false
                onBack()
            } catch (e: Exception) {
                busy = false
                status = "保存失败：${e.message}"
            }
        }
    }

    fun doTest() {
        busy = true
        status = "正在测试读取文件列表…"
        scope.launch {
            // MT（0x7f1102b7）：卡住时给出可操作提示——主/被动模式是最常见的元凶
            val isFtp = state.type == ConnectionType.FTP || state.type == ConnectionType.FTPS
            val stuckHint = if (isFtp) {
                "正在测试读取文件列表… (如果卡在这一步，请尝试切换主/被动模式)"
            } else {
                "正在测试读取文件列表…"
            }
            val hintJob = launch {
                kotlinx.coroutines.delay(6_000)
                if (busy) status = stuckHint
            }
            try {
                if (!state.applyUrlIfWebDav()) {
                    hintJob.cancel()
                    status = "URL 格式不正确，示例：http://192.168.1.9:5244/dav"
                    busy = false
                    return@launch
                }
                val portNumber = state.port.toIntOrNull() ?: state.type.defaultPort
                if (portNumber !in 1..65535) {
                    hintJob.cancel()
                    status = "端口必须在 1–65535 之间（当前：${state.port}）"
                    busy = false
                    return@launch
                }
                state.smbProblem?.let { problem ->
                    hintJob.cancel()
                    status = problem
                    busy = false
                    return@launch
                }
                val config = state.buildConfig()
                val count = container.testConnection(config, secretOverride = state.buildSecret())
                status = "连接成功：根目录 $count 项"
            } catch (e: Exception) {
                status = "连接失败：" + ((e as? VfsException)?.userMessage ?: e.message)
            } finally {
                hintJob.cancel()
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = (if (existing == null) "添加 " else "编辑 ") + state.type.label,
            onBack = onBack,
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(
                    ConnectionType.SFTP, ConnectionType.SMB, ConnectionType.S3,
                    ConnectionType.WEBDAV, ConnectionType.FTP, ConnectionType.FTPS,
                ).forEach { t ->
                    TextButton(
                        onClick = { state.selectType(t) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 10.dp,
                            vertical = 2.dp,
                        ),
                    ) {
                        Text(
                            // MT 的协议名：`对象存储(S3)` 带括号
                            if (t == ConnectionType.S3) "对象存储(S3)" else t.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (t == state.type) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------------- 连接字段（复刻 MT 的 `TextInputLayout`：label 在上、下划线、hint 在框内）
            ConnectionCommonFields(state, busy, ::fillDefaultGateway)

            // ---------------- 协议专属
            if (state.type == ConnectionType.SFTP) {
                SftpSection(
                    state = state,
                    onPickKey = { keyPicker.launch(arrayOf("*/*")) },
                    onShowPublicKey = {
                        scope.launch {
                            val line = runCatching {
                                withContext(Dispatchers.IO) {
                                    SshKeys.loadKeyPairs(state.keyPath, state.keyPassphrase.ifEmpty { null })
                                        .firstOrNull()?.let { pair -> SshKeys.publicKeyLine(pair.public) }
                                }
                            }.getOrNull()
                            if (line == null) {
                                status = "读取公钥失败（口令是否正确？）"
                            } else {
                                publicKeyLine = line
                            }
                        }
                    },
                )
            }
            if (state.type == ConnectionType.SMB) SmbSection(state)
            if (state.type == ConnectionType.S3) S3Section(state)

            // ---------------- 开关项 / 缩略图选项
            ProtocolSwitches(state)
            ThumbnailOptions(state, showThumbOptions) { showThumbOptions = !showThumbOptions }

            Spacer(Modifier.padding(bottom = 4.dp))
        }

        // ---------------- 状态
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    it.startsWith("连接成功") -> MaterialTheme.colorScheme.primary
                    it.startsWith("连接失败") || it.startsWith("保存失败") ||
                        it.startsWith("URL") || it.startsWith("未读取到") -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        HSeparator()

        // ---------------- 底部：测试 / 取消 / 保存（MT 布局）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(enabled = state.ready(busy), onClick = { doTest() }) {
                Text(if (busy) "测试中…" else "测试")
            }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = onBack) { Text("取消") }
            Button(enabled = state.ready(busy), onClick = { doSave() }) {
                Text(if (busy) "保存中…" else "保存")
            }
        }
    }

    publicKeyLine?.let { line ->
        AlertDialog(
            onDismissRequest = { publicKeyLine = null },
            title = { Text("公钥（粘贴到 authorized_keys）") },
            text = { Text(line, style = MaterialTheme.typography.labelSmall) },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(line))
                    publicKeyLine = null
                    status = "公钥已复制到剪贴板"
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { publicKeyLine = null }) { Text("关闭") } },
        )
    }
}
