package com.u707t.panelfm.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.vfs.VfsException
import kotlinx.coroutines.launch

/** 连接编辑器：FTP / FTPS / WebDAV（后续协议复用同一表单骨架）。 */
@Composable
fun ConnectionEditScreen(container: AppContainer, connectionId: Long?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val existing = remember(connectionId) { connectionId?.let { id -> container.connectionDao.all().firstOrNull { it.id == id } } }

    var type by remember { mutableStateOf(existing?.type ?: ConnectionType.WEBDAV) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: type.defaultPort).toString()) }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var password by remember { mutableStateOf(existing?.let { container.loadSecret(it.id) } ?: "") }
    var basePath by remember { mutableStateOf(existing?.basePath ?: "/") }
    var secure by remember { mutableStateOf(existing?.option("secure")?.toBoolean() ?: false) }
    var trustSelfSigned by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_TRUST_SELF_SIGNED)?.toBoolean() ?: true) }
    var implicitTls by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_IMPLICIT_TLS)?.toBoolean() ?: false) }
    var passive by remember { mutableStateOf(existing?.option(ConnectionConfig.OPT_PASSIVE)?.toBoolean() ?: true) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(type) {
        if (existing == null) {
            port = type.defaultPort.toString()
            secure = type == ConnectionType.S3
        }
    }

    fun buildConfig(): ConnectionConfig {
        val options = buildMap {
            if (secure) put("secure", "true")
            if (trustSelfSigned) put(ConnectionConfig.OPT_TRUST_SELF_SIGNED, "true")
            if (implicitTls) put(ConnectionConfig.OPT_IMPLICIT_TLS, "true")
            if (!passive) put(ConnectionConfig.OPT_PASSIVE, "false")
        }
        return ConnectionConfig(
            id = existing?.id ?: 0L,
            type = type,
            name = name.ifBlank { host },
            host = host.trim(),
            port = port.toIntOrNull() ?: type.defaultPort,
            user = user.trim(),
            basePath = basePath.ifBlank { "/" },
            options = options,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                if (existing == null) "添加网络存储" else "编辑连接",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConnectionType.entries.filter { it != ConnectionType.LOCAL && it != ConnectionType.SFTP && it != ConnectionType.SMB && it != ConnectionType.S3 }
                .forEach { t ->
                    TextButton(
                        onClick = { type = t },
                        modifier = Modifier.padding(end = 2.dp),
                    ) {
                        Text(
                            t.label,
                            color = if (t == type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
        }
        Text(
            "SFTP / SMB / S3 在 M4–M6 接入（接口与引擎已就绪，接入即用）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（显示用）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("主机 / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it.filter { ch -> ch.isDigit() } }, label = { Text("端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码（Keystore 加密保存）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = basePath, onValueChange = { basePath = it }, label = { Text("根路径（如 /dav）") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = secure, onCheckedChange = { secure = it })
            Text("使用 HTTPS/TLS（http 明文常被自建 Alist 使用）", style = MaterialTheme.typography.bodySmall)
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
                enabled = host.isNotBlank() && !testing,
                onClick = {
                    testing = true
                    scope.launch {
                        try {
                            val config = buildConfig()
                            if (existing != null) {
                                container.connectionDao.update(config)
                                container.saveSecret(config.id, password)
                            } else {
                                val id = container.connectionDao.insert(config)
                                container.saveSecret(id, password)
                            }
                            container.reloadConnections()
                            status = "已保存"
                            onBack()
                        } catch (e: Exception) {
                            status = "保存失败：${e.message}"
                        } finally {
                            testing = false
                        }
                    }
                },
            ) { Text(if (testing) "保存中…" else "保存") }

            TextButton(
                enabled = host.isNotBlank(),
                onClick = {
                    testing = true
                    status = null
                    scope.launch {
                        try {
                            val config = buildConfig()
                            val vfs = container.openConnection(config)
                            val items = vfs.list(com.u707t.panelfm.core.vfs.VfsUri.of(config.scheme, "${config.host}:${config.port}", config.basePath.ifBlank { "/" }))
                            status = "连接成功：根目录 ${items.size} 项"
                        } catch (e: Exception) {
                            status = "连接失败：" + ((e as? VfsException)?.userMessage ?: e.message)
                        } finally {
                            testing = false
                        }
                    }
                },
            ) { Text("测试连接") }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "提示：Android 17 需授予「局域网访问」权限，否则连接局域网 NAS 会直接超时。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
