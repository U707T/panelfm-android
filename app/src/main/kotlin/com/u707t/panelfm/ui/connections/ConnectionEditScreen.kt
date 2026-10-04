package com.u707t.panelfm.ui.connections

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.s3.S3Config
import com.u707t.panelfm.core.vfs.sftp.SftpAuth
import com.u707t.panelfm.core.vfs.sftp.SftpConfig
import com.u707t.panelfm.core.vfs.sftp.SftpSecrets
import com.u707t.panelfm.core.vfs.sftp.SshKeys
import com.u707t.panelfm.core.vfs.smb.SmbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 连接编辑器：FTP / FTPS / WebDAV / SMB / S3 / SFTP。
 *
 * WebDAV 复刻 MT 的「编辑 WebDav」样式：**URL 单行输入**（http://host:port/dav）、用户名、密码（可切换可见）、
 * 自定义 UA、初始路径、备注，以及开关项（信任所有 HTTPS 证书 / 在侧拉栏隐藏地址 / 缩略图选项），底部 测试 / 取消 / 保存。
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
    val existing = remember(connectionId) { connectionId?.let { id -> container.connectionDao.all().firstOrNull { it.id == id } } }

    val storedSecrets = remember(connectionId) {
        existing?.let { SftpSecrets.parse(container.loadSecret(it.id)) } ?: SftpSecrets()
    }

    var type by remember { mutableStateOf(existing?.type ?: initialType ?: ConnectionType.SFTP) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    // 网络分组（MT：连接可归组，侧边栏按组展示；DB 有 group_name 字段但此前无 UI）
    var group by remember { mutableStateOf(existing?.group ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: prefillHost ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: prefillPort ?: type.defaultPort).toString()) }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var password by remember { mutableStateOf(storedSecrets.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var basePath by remember { mutableStateOf(existing?.basePath ?: "/") }
    var secure by remember { mutableStateOf(existing?.option("secure")?.toBoolean() ?: false) }
    var trustSelfSigned by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_TRUST_SELF_SIGNED)?.toBoolean() ?: true) }
    var implicitTls by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_IMPLICIT_TLS)?.toBoolean() ?: false) }
    var passive by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_PASSIVE)?.toBoolean() ?: true) }
    // MT「编码」：FTP/FTPS/SFTP 的文件名编码（中文服务器常需 GBK/GB18030）
    var encoding by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_ENCODING).orEmpty().ifBlank { "UTF-8" }) }
    var userAgent by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_USER_AGENT).orEmpty()) }
    var initialPath by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_INITIAL_PATH).orEmpty()) }
    var hiddenInDrawer by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_HIDDEN_IN_DRAWER) == "true") }
    var loadThumbs by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_LOAD_THUMBS) != "false") }
    var showThumbOptions by remember { mutableStateOf(false) }

    // WebDAV：URL 单行（MT 样式）
    val existingDav = existing?.takeIf { it.type == ConnectionType.WEBDAV }
    var url by remember {
        mutableStateOf(
            when {
                existingDav != null ->
                    WebDavUrl.build(existingDav.host, existingDav.port, existingDav.option("secure") == "true", existingDav.basePath)
                initialType == ConnectionType.WEBDAV && !prefillHost.isNullOrBlank() ->
                    WebDavUrl.build(prefillHost, prefillPort ?: 80, false, "/")
                else -> ""
            }
        )
    }

    // SFTP
    var sftpAuth by remember {
        mutableStateOf(
            runCatching { SftpAuth.valueOf(existing?.option(SftpConfig.OPT_AUTH) ?: SftpAuth.PASSWORD.name) }
                .getOrDefault(SftpAuth.PASSWORD)
        )
    }
    var keyPath by remember { mutableStateOf(existing?.option(SftpConfig.OPT_KEY_PATH) ?: "") }
    var keyPassphrase by remember { mutableStateOf(storedSecrets.keyPassphrase ?: "") }
    var jumpEnabled by remember { mutableStateOf(!existing?.option(SftpConfig.OPT_JUMP_HOST).isNullOrBlank()) }
    var jumpHost by remember { mutableStateOf(existing?.option(SftpConfig.OPT_JUMP_HOST).orEmpty()) }
    var jumpPort by remember { mutableStateOf(existing?.option(SftpConfig.OPT_JUMP_PORT) ?: "22") }
    var jumpUser by remember { mutableStateOf(existing?.option(SftpConfig.OPT_JUMP_USER).orEmpty()) }
    var jumpPassword by remember { mutableStateOf(storedSecrets.jumpPassword ?: "") }

    // SMB
    var smbDomain by remember { mutableStateOf(existing?.option(SmbConfig.OPT_DOMAIN).orEmpty()) }
    var smbShare by remember { mutableStateOf(existing?.option(SmbConfig.OPT_SHARE).orEmpty()) }
    // S3
    var s3Region by remember { mutableStateOf(existing?.option(S3Config.OPT_REGION) ?: "us-east-1") }
    var s3PathStyle by remember { mutableStateOf(existing?.option(S3Config.OPT_PATH_STYLE)?.toBoolean() ?: true) }
    var s3Domain by remember { mutableStateOf(existing?.option(S3Config.OPT_DOWNLOAD_DOMAIN).orEmpty()) }
    var s3Bucket by remember { mutableStateOf(existing?.option(S3Config.OPT_BUCKET).orEmpty()) }

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var publicKeyLine by remember { mutableStateOf<String?>(null) }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) {
                    val picked = uri.lastPathSegment?.substringAfterLast('/') ?: "id_key"
                    val dest = File(container.appDirs.keysDir, picked)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    // 私钥仅本应用可读写（相当于 chmod 600）
                    dest.setReadable(false, false)
                    dest.setReadable(true, true)
                    dest.setWritable(false, false)
                    dest.setWritable(true, true)
                    dest.absolutePath
                }
                keyPath = fileName
                status = "已导入私钥：${File(fileName).name}"
            } catch (e: Exception) {
                status = "私钥导入失败：${e.message}"
            }
        }
    }

    LaunchedEffect(type) {
        if (existing == null && prefillPort == null) port = type.defaultPort.toString()
    }

    /** 切到 WebDAV 标签时，用当前字段回填 URL（LAN 扫描预填场景） */
    fun syncUrlFromFields() {
        if (type == ConnectionType.WEBDAV && url.isBlank() && host.isNotBlank()) {
            url = WebDavUrl.build(host.trim(), port.toIntOrNull() ?: 80, secure, basePath)
        }
    }

    /** 复刻 MT：保存 / 测试前把 URL 解析回各字段；返回 false = URL 格式不正确 */
    fun applyUrlIfWebDav(): Boolean {
        if (type != ConnectionType.WEBDAV) return true
        val parsed = WebDavUrl.parse(url) ?: return false
        host = parsed.host
        port = parsed.port.toString()
        secure = parsed.secure
        basePath = parsed.path
        return true
    }

    val badUrlMessage = "URL 格式不正确，示例：http://192.168.1.9:5244/dav"
    val ready = (if (type == ConnectionType.WEBDAV) WebDavUrl.parse(url) != null else host.isNotBlank()) && !busy

    fun buildOptions(): Map<String, String> = buildMap {
        if (secure) put("secure", "true")
        if (trustSelfSigned) put(ConnectionConfig.OPT_TRUST_SELF_SIGNED, "true")
        if (implicitTls) put(ConnectionConfig.OPT_IMPLICIT_TLS, "true")
        if (!passive) put(ConnectionConfig.OPT_PASSIVE, "false")
        if (userAgent.isNotBlank()) put(ConnectionConfig.OPT_USER_AGENT, userAgent.trim())
        if (initialPath.isNotBlank()) put(ConnectionConfig.OPT_INITIAL_PATH, initialPath.trim())
        if (hiddenInDrawer) put(ConnectionConfig.OPT_HIDDEN_IN_DRAWER, "true")
        if (!loadThumbs) put(ConnectionConfig.OPT_LOAD_THUMBS, "false")
        if ((type == ConnectionType.FTP || type == ConnectionType.FTPS || type == ConnectionType.SFTP) &&
            encoding.isNotBlank() && encoding != "UTF-8"
        ) {
            put(ConnectionConfig.OPT_ENCODING, encoding.trim())
        }
        if (type == ConnectionType.SMB) {
            if (smbDomain.isNotBlank()) put(SmbConfig.OPT_DOMAIN, smbDomain.trim())
            if (smbShare.isNotBlank()) put(SmbConfig.OPT_SHARE, smbShare.trim())
        }
        if (type == ConnectionType.S3) {
            put(S3Config.OPT_REGION, s3Region.trim().ifBlank { "us-east-1" })
            put(S3Config.OPT_PATH_STYLE, s3PathStyle.toString())
            if (s3Domain.isNotBlank()) put(S3Config.OPT_DOWNLOAD_DOMAIN, s3Domain.trim())
            if (s3Bucket.isNotBlank()) put(S3Config.OPT_BUCKET, s3Bucket.trim())
        }
        if (type == ConnectionType.SFTP) {
            put(SftpConfig.OPT_AUTH, sftpAuth.name)
            if (sftpAuth == SftpAuth.KEY && keyPath.isNotBlank()) put(SftpConfig.OPT_KEY_PATH, keyPath)
            if (jumpEnabled && jumpHost.isNotBlank()) {
                put(SftpConfig.OPT_JUMP_HOST, jumpHost.trim())
                put(SftpConfig.OPT_JUMP_PORT, jumpPort.trim().ifBlank { "22" })
                put(SftpConfig.OPT_JUMP_USER, jumpUser.trim())
            }
        }
    }

    fun buildSecret(): String? = when (type) {
        ConnectionType.S3 -> if (password.isNotBlank() || user.isNotBlank()) "$user:$password" else null
        ConnectionType.SFTP -> SftpSecrets(
            password = password.ifEmpty { null },
            keyPassphrase = keyPassphrase.ifEmpty { null },
            jumpPassword = jumpPassword.ifEmpty { null },
        ).toJson().takeIf { password.isNotEmpty() || keyPassphrase.isNotEmpty() || jumpPassword.isNotEmpty() }
        else -> password.ifEmpty { null }
    }

    fun buildConfig(id: Long = existing?.id ?: 0L): ConnectionConfig = ConnectionConfig(
        id = id,
        type = type,
        name = name.ifBlank { host },
        host = host.trim(),
        port = port.toIntOrNull() ?: type.defaultPort,
        user = user.trim(),
        basePath = basePath.ifBlank { "/" },
        group = group.trim(),
        options = buildOptions(),
    )

    fun doSave() {
        busy = true
        status = null
        scope.launch {
            try {
                if (!applyUrlIfWebDav()) {
                    status = badUrlMessage
                    busy = false
                    return@launch
                }
                val config = buildConfig()
                val secret = buildSecret()
                if (existing != null) {
                    val oldSecret = runCatching { container.loadSecret(existing.id) }.getOrNull()
                    container.connectionDao.update(config)
                    container.saveSecret(config.id, secret)
                    // 会话键或口令变化 → 断掉旧会话，下次打开用新配置
                    if (existing.sessionKey != config.sessionKey || oldSecret != secret) {
                        container.disconnectConnection(existing)
                    }
                } else {
                    val id = container.connectionDao.insert(config)
                    container.saveSecret(id, secret)
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
            val isFtp = type == ConnectionType.FTP || type == ConnectionType.FTPS
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
                if (!applyUrlIfWebDav()) {
                    hintJob.cancel()
                    status = badUrlMessage
                    busy = false
                    return@launch
                }
                val config = buildConfig()
                val vfs = container.openConnection(config, secretOverride = buildSecret())
                val items = vfs.list(
                    // WebDAV 进入虚拟根（basePath 是挂载点，由协议层拼回）；其余协议进入 openPath
                    VfsUri.of(config.scheme, "${config.host}:${config.port}", config.openPath)
                )
                status = "连接成功：根目录 ${items.size} 项"
            } catch (e: Exception) {
                status = "连接失败：" + ((e as? VfsException)?.userMessage ?: e.message)
            } finally {
                hintJob.cancel()
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        // ---------------- 顶部
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                (if (existing == null) "添加 " else "编辑 ") + type.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }

        // ---------------- 表单
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
                    TextButton(onClick = {
                        type = t
                        if (t == ConnectionType.WEBDAV) syncUrlFromFields()
                    }) {
                        Text(
                            t.label,
                            color = if (t == type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "全部协议免费开放；SFTP 支持私钥与跳板机，S3 支持 R2/COS/OSS/MinIO 等兼容端点。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (type == ConnectionType.WEBDAV) {
                // MT 样式：URL 单行输入（http/https、端口、路径都写在这里）
                OutlinedTextField(
                    value = url,
                    onValueChange = { text ->
                        url = text
                        WebDavUrl.parse(text)?.let { p ->
                            host = p.host
                            port = p.port.toString()
                            secure = p.secure
                            basePath = p.path
                        }
                    },
                    label = { Text("URL") },
                    placeholder = { Text("http://192.168.1.9:5244/dav") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("主机 / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it.filter { ch -> ch.isDigit() } }, label = { Text("端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text(if (type == ConnectionType.S3) "用户名 / Access Key（AK）" else "用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = if (type == ConnectionType.SFTP && sftpAuth == SftpAuth.KEY) "" else password,
                onValueChange = { password = it },
                label = {
                    Text(
                        when {
                            type == ConnectionType.SFTP && sftpAuth == SftpAuth.KEY -> "密码（使用私钥时忽略）"
                            type == ConnectionType.S3 -> "密码 / Secret Key（SK，Keystore 加密保存）"
                            else -> "密码（Keystore 加密保存）"
                        }
                    )
                },
                singleLine = true,
                enabled = !(type == ConnectionType.SFTP && sftpAuth == SftpAuth.KEY),
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
            if (type == ConnectionType.WEBDAV) {
                OutlinedTextField(value = userAgent, onValueChange = { userAgent = it }, label = { Text("自定义 UA（可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(value = basePath, onValueChange = { basePath = it }, label = { Text("根路径（如 /home/user）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(
                value = initialPath,
                onValueChange = { initialPath = it },
                label = { Text("初始路径（可留空；相对根路径 / 挂载点）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("备注（显示用）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = group,
                onValueChange = { group = it },
                label = { Text("网络分组（可留空；侧边栏按组展示）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // ---------------- SFTP 专属
            if (type == ConnectionType.SFTP) {
                Text("认证方式", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { sftpAuth = SftpAuth.PASSWORD }) {
                        Text("密码", color = if (sftpAuth == SftpAuth.PASSWORD) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { sftpAuth = SftpAuth.KEY }) {
                        Text("私钥", color = if (sftpAuth == SftpAuth.KEY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (sftpAuth == SftpAuth.KEY) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) { Text("选择私钥文件…") }
                        if (keyPath.isNotBlank()) {
                            Text(File(keyPath).name, style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = {
                                scope.launch {
                                    val line = runCatching {
                                        withContext(Dispatchers.IO) {
                                            SshKeys.loadKeyPairs(keyPath, keyPassphrase.ifEmpty { null }).firstOrNull()?.let { pair ->
                                                SshKeys.publicKeyLine(pair.public)
                                            }
                                        }
                                    }.getOrNull()
                                    if (line == null) {
                                        status = "读取公钥失败（口令是否正确？）"
                                    } else {
                                        publicKeyLine = line
                                    }
                                }
                            }) { Text("查看公钥") }
                        }
                    }
                    OutlinedTextField(
                        value = keyPassphrase,
                        onValueChange = { keyPassphrase = it },
                        label = { Text("私钥口令（无口令留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "支持 OpenSSH / PEM 私钥（ed25519 / ecdsa / rsa）；公钥贴到服务器 ~/.ssh/authorized_keys 即可。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SwitchRow("使用跳板机（ProxyJump）", jumpEnabled) { jumpEnabled = it }
                if (jumpEnabled) {
                    OutlinedTextField(value = jumpHost, onValueChange = { jumpHost = it }, label = { Text("跳板机主机") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = jumpPort, onValueChange = { jumpPort = it.filter { ch -> ch.isDigit() } }, label = { Text("跳板机端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = jumpUser, onValueChange = { jumpUser = it }, label = { Text("跳板机用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = jumpPassword, onValueChange = { jumpPassword = it }, label = { Text("跳板机密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "安全提示：首次连接会自动记录主机指纹（TOFU）；指纹变化时会拒绝连接并提示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------- SMB 专属
            if (type == ConnectionType.SMB) {
                OutlinedTextField(value = smbShare, onValueChange = { smbShare = it }, label = { Text("共享名（如 public / media）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = smbDomain, onValueChange = { smbDomain = it }, label = { Text("域 / 工作组（可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "支持 SMB2/3（自动协商 3.1.1 → 3.0.2 → 2.1），NTLMv2 认证；\"所有文件访问\"外的路径无需额外授权。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------- S3 专属
            if (type == ConnectionType.S3) {
                OutlinedTextField(value = s3Region, onValueChange = { s3Region = it }, label = { Text("Region（AWS 必填；R2 填 auto）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = s3Bucket, onValueChange = { s3Bucket = it }, label = { Text("默认 Bucket（可留空，进目录后选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = s3Domain, onValueChange = { s3Domain = it }, label = { Text("自定义下载域名（可选，如 cdn.example.com）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SwitchRow("路径样式（path-style，自建 MinIO/Alist 建议开启）", s3PathStyle) { s3PathStyle = it }
                Text(
                    "新建连接时主机填端点（如 s3.amazonaws.com / xxx.r2.cloudflarestorage.com / 192.168.1.9:9000），协议选 http/https 由上面「使用 HTTPS/TLS」决定。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------- 开关项（MT 样式：右侧 Switch）
            SwitchRow(
                if (type == ConnectionType.WEBDAV) "信任所有 HTTPS 证书" else "信任自签证书",
                trustSelfSigned,
            ) { trustSelfSigned = it }
            if (type == ConnectionType.S3) {
                SwitchRow("使用 HTTPS/TLS", secure) { secure = it }
            }
            if (type == ConnectionType.FTPS) {
                SwitchRow("隐式 TLS（990 端口）", implicitTls) { implicitTls = it }
            }
            if (type == ConnectionType.FTP || type == ConnectionType.FTPS) {
                SwitchRow("被动模式（PASV/EPSV，推荐）", passive) { passive = it }
            }
            // MT：FTP/FTPS/SFTP 的「编码」（文件名编码；中文 FTP 服务器常用 GBK）
            if (type == ConnectionType.FTP || type == ConnectionType.FTPS || type == ConnectionType.SFTP) {
                Text("编码", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("UTF-8", "GBK", "GB18030", "Big5").forEach { enc ->
                        TextButton(onClick = { encoding = enc }) {
                            Text(
                                enc,
                                color = if (encoding == enc) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "文件名编码。中文 FTP 服务器列表乱码时改成 GBK / GB18030。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SwitchRow("在侧拉栏隐藏地址", hiddenInDrawer) { hiddenInDrawer = it }

            // ---------------- 缩略图选项（可展开，MT 的「缩略图选项 >>」）
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showThumbOptions = !showThumbOptions }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("缩略图选项", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        if (showThumbOptions) "⌃" else "≫",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showThumbOptions) {
                    SwitchRow("加载缩略图", loadThumbs) { loadThumbs = it }
                    Text(
                        "关闭后该连接中的网络图片不再加载列表缩略图（点开仍可正常预览）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.padding(bottom = 4.dp))
        }

        // ---------------- 状态
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    it.startsWith("连接成功") -> MaterialTheme.colorScheme.primary
                    it.startsWith("连接失败") || it.startsWith("保存失败") || it.startsWith("URL") -> MaterialTheme.colorScheme.error
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
            TextButton(enabled = ready, onClick = { doTest() }) { Text(if (busy) "测试中…" else "测试") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("取消") }
            Button(enabled = ready, onClick = { doSave() }) { Text(if (busy) "保存中…" else "保存") }
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

/** MT 样式开关行：左文字、右 Switch */
@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
