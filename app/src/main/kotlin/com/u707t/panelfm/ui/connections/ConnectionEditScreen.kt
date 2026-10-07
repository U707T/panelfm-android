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
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.MtTextField
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

    // 原始 secret 串（未解码）：S3 是「纯 SK 或旧 AK:SK」，SFTP 是 JSON，其余是裸密码
    val rawSecret = remember(connectionId) {
        existing?.let { runCatching { container.loadSecret(it.id) }.getOrNull() }
    }
    val storedSecrets = remember(connectionId) { SftpSecrets.parse(rawSecret) }

    var type by remember { mutableStateOf(existing?.type ?: initialType ?: ConnectionType.SFTP) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    // 网络分组（MT：连接可归组，侧边栏按组展示；DB 有 group_name 字段但此前无 UI）
    var group by remember { mutableStateOf(existing?.group ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: prefillHost ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: prefillPort ?: type.defaultPort).toString()) }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    // 口令回填按协议解码（S3 的旧拼接串要取 SK 部分，否则保存时会被再拼一层；见 ConnectionSecrets）
    var password by remember { mutableStateOf(ConnectionSecrets.passwordForEdit(existing?.type, rawSecret)) }
    var showPassword by remember { mutableStateOf(false) }
    var basePath by remember { mutableStateOf(existing?.basePath ?: "/") }
    // 与 DavConfig / S3Config 的同款 fallback 对齐（键缺失按端口推：443 = HTTPS）——
    // 否则编辑老连接时显示 false、保存又写回，会把「无法关闭 HTTPS」的现状再固化一轮
    var secure by remember { mutableStateOf(existing?.option("secure")?.toBoolean() ?: (port.toIntOrNull() == 443)) }
    var trustSelfSigned by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_TRUST_SELF_SIGNED)?.toBoolean() ?: false) }
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
                    WebDavUrl.build(existingDav.host, existingDav.port, secure, existingDav.basePath)
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

    // 端口跟随协议默认值：仅当当前端口仍是「上一个协议的默认值」（用户没手改过）才更新——
    // 编辑既有连接切协议（FTP→SMB 后端口仍 21）也一并修掉；prefill（局域网扫描）不覆盖。
    var prevType by remember { mutableStateOf(type) }
    LaunchedEffect(type) {
        if (prefillPort == null && port == prevType.defaultPort.toString()) {
            port = type.defaultPort.toString()
        }
        prevType = type
    }

    // 保存 / 测试进行中拦截系统返回：中途退出会留下「secret 已更新、配置未更新」的半态
    androidx.activity.compose.BackHandler(enabled = busy) { status = "正在与服务器交互，请稍候…" }

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
        // `secure` 键只有 WebDAV / S3 消费，**无条件写布尔值**：旧实现只在 true 时写，
        // 导致 443 端口上「关闭 HTTPS / 显式 http://」被 Config 的 `port == 443` fallback 覆盖
        if (type == ConnectionType.WEBDAV || type == ConnectionType.S3) put("secure", secure.toString())
        if (trustSelfSigned) put(ConnectionConfig.OPT_TRUST_SELF_SIGNED, "true")
        if (implicitTls) put(ConnectionConfig.OPT_IMPLICIT_TLS, "true")
        if (!passive) put(ConnectionConfig.OPT_PASSIVE, "false")
        if (userAgent.isNotBlank()) put(ConnectionConfig.OPT_USER_AGENT, userAgent.trim())
        if (initialPath.isNotBlank()) put(ConnectionConfig.OPT_INITIAL_PATH, initialPath.trim())
        if (hiddenInDrawer) put(ConnectionConfig.OPT_HIDDEN_IN_DRAWER, "true")
        if (!loadThumbs) put(ConnectionConfig.OPT_LOAD_THUMBS, "false")
        // SFTP 固定 UTF-8（协议层），没有编码消费方——写侧只对 FTP / FTPS 生效，与 UI 显示范围一致
        if ((type == ConnectionType.FTP || type == ConnectionType.FTPS) &&
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

    /** 口令编解码已收拢到 [ConnectionSecrets]（纯函数 + 单测；S3 落库纯 SK，修复编辑保存层层加前缀） */
    fun buildSecret(): String? = ConnectionSecrets.build(type, password, keyPassphrase, jumpPassword)

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
                // 端口范围校验：旧实现 filter 掉非数字后就存，`99999` 这类值会一路写进 DB，
                // 直到连接时才以「连接被拒绝」的形式暴露，用户很难联想到是配置问题。
                val portNumber = port.toIntOrNull() ?: type.defaultPort
                if (portNumber !in 1..65535) {
                    status = "端口必须在 1–65535 之间（当前：$port）"
                    busy = false
                    return@launch
                }
                val config = buildConfig()
                val secret = buildSecret()
                if (existing != null) {
                    val oldSecret = runCatching { container.loadSecret(existing.id) }.getOrNull()
                    val secretSaved = container.saveSecret(config.id, secret)
                    if (!secretSaved) {
                        status = "配置未保存：口令写入失败（系统密钥库不可用）。请稍后重试。"
                        busy = false
                        return@launch
                    }
                    container.connectionDao.update(config)
                    // 会话键或口令变化 → 断掉旧会话，下次打开用新配置
                    if (existing.sessionKey != config.sessionKey || oldSecret != secret) {
                        container.disconnectConnection(existing)
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
                val portNumber = port.toIntOrNull() ?: type.defaultPort
                if (portNumber !in 1..65535) {
                    hintJob.cancel()
                    status = "端口必须在 1–65535 之间（当前：$port）"
                    busy = false
                    return@launch
                }
                val config = buildConfig()
                val count = container.testConnection(config, secretOverride = buildSecret())
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
        // ---------------- 顶部
        MtScreenTopBar(
            title = (if (existing == null) "添加 " else "编辑 ") + type.label,
            onBack = onBack,
        )

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
                    TextButton(
                        onClick = {
                            type = t
                            if (t == ConnectionType.WEBDAV) syncUrlFromFields()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Text(
                            // MT 的协议名：`对象存储(S3)` 带括号
                            if (t == ConnectionType.S3) "对象存储(S3)" else t.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (t == type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------------- 连接字段（复刻 MT 的 `TextInputLayout`：label 在上、下划线、hint 在框内）
            //   MT 原文（`0x7f0c0082` WebDAV）：URL / 用户名 / 密码 / 初始路径 / 备注
            //   非 WebDAV 协议用「主机 + 端口 + 用户名 + 密码 + 根路径 + 初始路径 + 备注 + 分组」
            if (type == ConnectionType.WEBDAV) {
                // MT 的 URL 单行输入：hint 示例就是 `https://dav.xxx.com:443/dav`
                MtTextField(
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
                    label = "URL",
                    placeholder = "https://dav.xxx.com:443/dav",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                )
            } else {
                MtTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = "主机 / IP",
                    placeholder = "192.168.1.9",
                )
                MtTextField(
                    value = port,
                    onValueChange = { text -> port = text.filter { ch -> ch.isDigit() }.take(5) },
                    label = "端口",
                    placeholder = "0",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                )
            }

            MtTextField(
                value = user,
                onValueChange = { user = it },
                label = if (type == ConnectionType.S3) "用户名 / Access Key（AK）" else "用户名",
                placeholder = "可空",
            )
            MtTextField(
                value = if (type == ConnectionType.SFTP && sftpAuth == SftpAuth.KEY) "" else password,
                onValueChange = { password = it },
                label = when {
                    type == ConnectionType.SFTP && sftpAuth == SftpAuth.KEY -> "密码（使用私钥时忽略）"
                    type == ConnectionType.S3 -> "密码 / Secret Key（SK）"
                    else -> "密码"
                },
                placeholder = "可空",
                enabled = !(type == ConnectionType.SFTP && sftpAuth == SftpAuth.KEY),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                // MT 的密码框带 👁 可见性切换（`app:passwordToggleEnabled=true`）
                trailing = {
                    Box(
                        Modifier
                            .clickable { showPassword = !showPassword }
                            .padding(start = 10.dp),
                    ) {
                        MtVectorIcon(
                            icon = if (showPassword) MtIcon.EYE_OFF else MtIcon.EYE,
                            size = 22.dp,
                            tint = if (showPassword) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            if (type == ConnectionType.WEBDAV) {
                MtTextField(
                    value = userAgent,
                    onValueChange = { userAgent = it },
                    label = "自定义 UA",
                    placeholder = "可空",
                )
            } else if (type == ConnectionType.SMB) {
                // SMB 的地址路径以「/共享名」开头（SmbVfs.splitSmbPath，首段即共享）——
                // 旧文案（/home/user）与模型不符：用户填子目录名会顶替「共享名」去找错共享
                MtTextField(
                    value = basePath,
                    onValueChange = { basePath = it },
                    label = "起始路径（含共享名，如 /public/docs）",
                    placeholder = "/public",
                )
            } else if (type != ConnectionType.S3) {
                MtTextField(
                    value = basePath,
                    onValueChange = { basePath = it },
                    label = "根路径",
                    placeholder = "/home/user",
                )
            }
            // S3 不显示「根路径」：没有消费方；bucket 内的起始前缀请用下方「初始路径」
            MtTextField(
                value = initialPath,
                onValueChange = { initialPath = it },
                label = "初始路径",
                placeholder = "可空",
            )
            MtTextField(
                value = name,
                onValueChange = { name = it },
                label = "备注",
                placeholder = "可空",
            )
            MtTextField(
                value = group,
                onValueChange = { group = it },
                label = "网络分组",
                placeholder = "可空",
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
            // 只对**真正读取该选项**的协议显示开关。
            // 旧实现对所有协议都渲染，导致 SFTP / SMB 用户打开后毫无效果（静默无效的开关
            // 比没有开关更糟：用户会以为自己已经放开了校验）。
            if (type == ConnectionType.WEBDAV || type == ConnectionType.FTP ||
                type == ConnectionType.FTPS || type == ConnectionType.S3
            ) {
                SwitchRow(
                    if (type == ConnectionType.WEBDAV) "信任所有 HTTPS 证书" else "信任自签证书",
                    trustSelfSigned,
                ) { trustSelfSigned = it }
            }
            if (type == ConnectionType.S3) {
                SwitchRow("使用 HTTPS/TLS", secure) { secure = it }
            }
            if (type == ConnectionType.FTPS) {
                // 端口联动：隐式 TLS 标准端口是 990——打开时从默认 21 切到 990、关闭时切回 21
                //（用户手改过的其他端口不动；FtpConfig 的 990 兜底对 UI 默认 21 不可达，联动必须在这里做）
                SwitchRow("隐式 TLS（990 端口）", implicitTls) { on ->
                    implicitTls = on
                    if (on && port == "21") port = "990"
                    if (!on && port == "990") port = "21"
                }
            }
            if (type == ConnectionType.FTP || type == ConnectionType.FTPS) {
                SwitchRow("被动模式（PASV/EPSV，推荐）", passive) { passive = it }
            }
            // MT：FTP/FTPS 的「编码」（文件名编码；中文 FTP 服务器常用 GBK）。
            // SFTP 不显示：SSH 协议固定 UTF-8、无消费方（静默无效的开关比没有开关更糟）
            if (type == ConnectionType.FTP || type == ConnectionType.FTPS) {
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
            TextButton(enabled = !busy, onClick = onBack) { Text("取消") }
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
