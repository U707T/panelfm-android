package com.u707t.panelfm.ui.connections

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.s3.S3Config
import com.u707t.panelfm.core.vfs.sftp.SftpAuth
import com.u707t.panelfm.core.vfs.smb.SmbConfig
import com.u707t.panelfm.core.vfs.sftp.SftpConfig
import com.u707t.panelfm.core.vfs.sftp.SftpSecrets
import com.u707t.panelfm.core.vfs.sftp.SshKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 连接编辑器：FTP / FTPS / WebDAV / **SFTP**（含私钥与跳板机）。 */
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
    var host by remember { mutableStateOf(existing?.host ?: prefillHost ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: prefillPort ?: type.defaultPort).toString()) }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var password by remember { mutableStateOf(storedSecrets.password ?: "") }
    var basePath by remember { mutableStateOf(existing?.basePath ?: "/") }
    var secure by remember { mutableStateOf(existing?.option("secure")?.toBoolean() ?: false) }
    var trustSelfSigned by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_TRUST_SELF_SIGNED)?.toBoolean() ?: true) }
    var implicitTls by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_IMPLICIT_TLS)?.toBoolean() ?: false) }
    var passive by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_PASSIVE)?.toBoolean() ?: true) }

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
        if (type == ConnectionType.WEBDAV && !existing?.option("secure").isNullOrBlank()) secure = existing?.option("secure")?.toBoolean() == true
    }

    fun buildOptions(): Map<String, String> = buildMap {
        if (secure) put("secure", "true")
        if (trustSelfSigned) put(ConnectionConfig.OPT_TRUST_SELF_SIGNED, "true")
        if (implicitTls) put(ConnectionConfig.OPT_IMPLICIT_TLS, "true")
        if (!passive) put(ConnectionConfig.OPT_PASSIVE, "false")
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
        options = buildOptions(),
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(if (existing == null) "添加网络存储" else "编辑连接", style = MaterialTheme.typography.titleMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(
                ConnectionType.SFTP, ConnectionType.SMB, ConnectionType.S3,
                ConnectionType.WEBDAV, ConnectionType.FTP, ConnectionType.FTPS,
            ).forEach { t ->
                TextButton(onClick = { type = t }) {
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

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（显示用）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("主机 / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it.filter { ch -> ch.isDigit() } }, label = { Text("端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = if (type == ConnectionType.SFTP && sftpAuth == SftpAuth.KEY) "" else password,
            onValueChange = { password = it },
            label = { Text(if (type == ConnectionType.SFTP && sftpAuth == SftpAuth.KEY) "密码（使用私钥时忽略）" else "密码（Keystore 加密保存）") },
            singleLine = true,
            enabled = !(type == ConnectionType.SFTP && sftpAuth == SftpAuth.KEY),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(value = basePath, onValueChange = { basePath = it }, label = { Text("根路径（如 /dav、/home/user）") }, singleLine = true, modifier = Modifier.fillMaxWidth())

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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = jumpEnabled, onCheckedChange = { jumpEnabled = it })
                Text("使用跳板机（ProxyJump）", style = MaterialTheme.typography.bodySmall)
            }
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
            OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Access Key（AK）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Secret Key（SK，Keystore 加密保存）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = s3Region, onValueChange = { s3Region = it }, label = { Text("Region（AWS 必填；R2 填 auto）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = s3Bucket, onValueChange = { s3Bucket = it }, label = { Text("默认 Bucket（可留空，进目录后选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = s3Domain, onValueChange = { s3Domain = it }, label = { Text("自定义下载域名（可选，如 cdn.example.com）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = s3PathStyle, onCheckedChange = { s3PathStyle = it })
                Text("路径样式（path-style，自建 MinIO/Alist 建议开启）", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "新建连接时主机填端点（如 s3.amazonaws.com / xxx.r2.cloudflarestorage.com / 192.168.1.9:9000），协议选 http/https 由上面「端口 + HTTPS」决定。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------------- WebDAV / FTP 选项
        if (type == ConnectionType.WEBDAV || type == ConnectionType.S3) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = secure, onCheckedChange = { secure = it })
                Text("使用 HTTPS/TLS", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = trustSelfSigned, onCheckedChange = { trustSelfSigned = it })
            Text("信任自签证书", style = MaterialTheme.typography.bodySmall)
        }
        if (type == ConnectionType.FTPS) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = implicitTls, onCheckedChange = { implicitTls = it })
                Text("隐式 TLS（990 端口）", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (type == ConnectionType.FTP || type == ConnectionType.FTPS) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = passive, onCheckedChange = { passive = it })
                Text("被动模式（PASV/EPSV，推荐）", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = host.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val config = buildConfig()
                            if (existing != null) {
                                container.connectionDao.update(config)
                                container.saveSecret(config.id, buildSecret())
                            } else {
                                val id = container.connectionDao.insert(config)
                                container.saveSecret(id, buildSecret())
                            }
                            container.reloadConnections()
                            busy = false
                            onBack()
                        } catch (e: Exception) {
                            busy = false
                            status = "保存失败：${e.message}"
                        }
                    }
                },
            ) { Text(if (busy) "保存中…" else "保存") }

            TextButton(
                enabled = host.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    status = null
                    scope.launch {
                        try {
                            val config = buildConfig()
                            val vfs = container.openConnection(config)
                            val items = vfs.list(
                                VfsUri.of(config.scheme, "${config.host}:${config.port}", config.basePath.ifBlank { "/" })
                            )
                            status = "连接成功：根目录 ${items.size} 项"
                        } catch (e: Exception) {
                            status = "连接失败：" + ((e as? VfsException)?.userMessage ?: e.message)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("测试连接") }
        }

        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
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
