package com.u707t.panelfm.ui.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtTextField
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.vfs.sftp.SftpAuth
import java.io.File

/**
 * 连接编辑表单的分段渲染（每个协议一段）。
 *
 * 之前这些分支全在 `ConnectionEditScreen` 的单个 Composable 里（740+ 行、28 处 `type ==`），
 * 加一个协议字段要跨 4 个区块改；现在每段只读写 [ConnectionEditState] 里自己那部分。
 */

/** 地址（WebDAV 用 URL 单行）、账号口令、根路径、初始路径、备注、分组。 */
@Composable
internal fun ConnectionCommonFields(
    state: ConnectionEditState,
    busy: Boolean,
    onFillGateway: () -> Unit,
) {
    if (state.type == ConnectionType.WEBDAV) {
        MtTextField(
            value = state.url,
            onValueChange = { text ->
                state.url = text
                WebDavUrl.parse(text)?.let { p ->
                    state.host = p.host
                    state.port = p.port.toString()
                    state.secure = p.secure
                    state.basePath = p.path
                    state.davDerivedBasePath = p.path
                }
            },
            label = "URL",
            placeholder = "https://dav.xxx.com:443/dav",
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
            trailing = { GatewayFillButton(enabled = !busy, onClick = onFillGateway) },
        )
    } else {
        MtTextField(
            value = state.host,
            onValueChange = { state.host = it },
            label = "主机 / IP",
            placeholder = "192.168.1.9",
            trailing = { GatewayFillButton(enabled = !busy, onClick = onFillGateway) },
        )
        MtTextField(
            value = state.port,
            onValueChange = { text -> state.port = text.filter { ch -> ch.isDigit() }.take(5) },
            label = "端口",
            placeholder = "0",
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        )
    }

    MtTextField(
        value = state.user,
        onValueChange = { state.user = it },
        label = if (state.type == ConnectionType.S3) "用户名 / Access Key（AK）" else "用户名",
        placeholder = "可空",
    )
    MtTextField(
        value = if (state.type == ConnectionType.SFTP && state.sftpAuth == SftpAuth.KEY) "" else state.password,
        onValueChange = { state.password = it },
        label = when {
            state.type == ConnectionType.SFTP && state.sftpAuth == SftpAuth.KEY -> "密码（使用私钥时忽略）"
            state.type == ConnectionType.S3 -> "密码 / Secret Key（SK）"
            else -> "密码"
        },
        placeholder = "可空",
        enabled = !(state.type == ConnectionType.SFTP && state.sftpAuth == SftpAuth.KEY),
        visualTransformation = if (state.showPassword) VisualTransformation.None
        else PasswordVisualTransformation(),
        trailing = { PasswordEye(state.showPassword) { state.showPassword = !state.showPassword } },
    )

    when {
        state.type == ConnectionType.WEBDAV -> MtTextField(
            value = state.userAgent,
            onValueChange = { state.userAgent = it },
            label = "自定义 UA",
            placeholder = "可空",
        )

        state.type == ConnectionType.SMB -> {
            // SMB 的地址路径以「/共享名」开头（SmbVfs.splitSmbPath，首段即共享）——
            // 旧文案（/home/user）与模型不符：用户填子目录名会顶替「共享名」去找错共享
            MtTextField(
                value = state.basePath,
                onValueChange = {
                    state.basePath = it
                    state.davDerivedBasePath = null
                },
                label = "起始路径（含共享名，如 /public/docs）",
                placeholder = "/public",
            )
            state.smbProblem?.let { problem ->
                Text(
                    problem,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        state.type != ConnectionType.S3 -> MtTextField(
            value = state.basePath,
            onValueChange = {
                state.basePath = it
                state.davDerivedBasePath = null
            },
            label = "根路径",
            placeholder = "/home/user",
        )
    }
    // S3 不显示「根路径」：没有消费方；bucket 内的起始前缀请用下方「初始路径」

    MtTextField(
        value = state.initialPath,
        onValueChange = { state.initialPath = it },
        label = "初始路径",
        placeholder = "可空",
    )
    MtTextField(
        value = state.name,
        onValueChange = { state.name = it },
        label = "备注",
        placeholder = "可空",
    )
    MtTextField(
        value = state.group,
        onValueChange = { state.group = it },
        label = "网络分组",
        placeholder = "可空",
    )
}

/** SFTP：认证方式、私钥、口令、跳板机、TOFU 提示。 */
@Composable
internal fun SftpSection(
    state: ConnectionEditState,
    onPickKey: () -> Unit,
    onShowPublicKey: () -> Unit,
) {
    Text("认证方式", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { state.sftpAuth = SftpAuth.PASSWORD }) {
            Text(
                "密码",
                color = if (state.sftpAuth == SftpAuth.PASSWORD) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { state.sftpAuth = SftpAuth.KEY }) {
            Text(
                "私钥",
                color = if (state.sftpAuth == SftpAuth.KEY) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (state.sftpAuth == SftpAuth.KEY) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPickKey) { Text("选择私钥文件…") }
            if (state.keyPath.isNotBlank()) {
                Text(File(state.keyPath).name, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onShowPublicKey) { Text("查看公钥") }
            }
        }
        OutlinedTextField(
            value = state.keyPassphrase,
            onValueChange = { state.keyPassphrase = it },
            label = { Text("私钥口令（无口令留空）") },
            singleLine = true,
            visualTransformation = if (state.showKeyPassphrase) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                PasswordEye(state.showKeyPassphrase) { state.showKeyPassphrase = !state.showKeyPassphrase }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "支持 OpenSSH / PEM 私钥（ed25519 / ecdsa / rsa）；公钥贴到服务器 ~/.ssh/authorized_keys 即可。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SwitchRow("使用跳板机（ProxyJump）", state.jumpEnabled) { state.jumpEnabled = it }
    if (state.jumpEnabled) {
        OutlinedTextField(
            value = state.jumpHost,
            onValueChange = { state.jumpHost = it },
            label = { Text("跳板机主机") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.jumpPort,
            onValueChange = { state.jumpPort = it.filter { ch -> ch.isDigit() } },
            label = { Text("跳板机端口") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.jumpUser,
            onValueChange = { state.jumpUser = it },
            label = { Text("跳板机用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.jumpPassword,
            onValueChange = { state.jumpPassword = it },
            label = { Text("跳板机密码") },
            singleLine = true,
            visualTransformation = if (state.showJumpPassword) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                PasswordEye(state.showJumpPassword) { state.showJumpPassword = !state.showJumpPassword }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        "安全提示：首次连接会自动记录主机指纹（TOFU）；指纹变化时会拒绝连接并提示。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** SMB：共享名、域。起始路径在通用字段里。 */
@Composable
internal fun SmbSection(state: ConnectionEditState) {
    OutlinedTextField(
        value = state.smbShare,
        onValueChange = { state.smbShare = it },
        label = { Text("共享名（如 public / media）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.smbDomain,
        onValueChange = { state.smbDomain = it },
        label = { Text("域 / 工作组（可留空）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "支持 SMB2/3（自动协商 3.1.1 → 3.0.2 → 2.1），NTLMv2 认证；\"所有文件访问\"外的路径无需额外授权。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** S3：Region、Bucket、下载域名、path-style。 */
@Composable
internal fun S3Section(state: ConnectionEditState) {
    OutlinedTextField(
        value = state.s3Region,
        onValueChange = { state.s3Region = it },
        label = { Text("Region（AWS 必填；R2 填 auto）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.s3Bucket,
        onValueChange = { state.s3Bucket = it },
        label = { Text("默认 Bucket（可留空，进目录后选）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.s3Domain,
        onValueChange = { state.s3Domain = it },
        label = { Text("自定义下载域名（可选，如 cdn.example.com）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SwitchRow("路径样式（path-style，自建 MinIO/Alist 建议开启）", state.s3PathStyle) {
        state.s3PathStyle = it
    }
    Text(
        "新建连接时主机填端点（如 s3.amazonaws.com / xxx.r2.cloudflarestorage.com / 192.168.1.9:9000），协议选 http/https 由上面「使用 HTTPS/TLS」决定。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 协议开关与 FTP 编码：只渲染真正读取该选项的协议。 */
@Composable
internal fun ProtocolSwitches(state: ConnectionEditState) {
    // 旧实现对所有协议都渲染，导致 SFTP / SMB 用户打开后毫无效果（静默无效的开关
    // 比没有开关更糟：用户会以为自己已经放开了校验）。
    if (state.type == ConnectionType.WEBDAV || state.type == ConnectionType.FTP ||
        state.type == ConnectionType.FTPS || state.type == ConnectionType.S3
    ) {
        SwitchRow(
            if (state.type == ConnectionType.WEBDAV) "信任所有 HTTPS 证书" else "信任自签证书",
            state.trustSelfSigned,
        ) { state.trustSelfSigned = it }
    }
    if (state.type == ConnectionType.S3) {
        SwitchRow("使用 HTTPS/TLS", state.secure) { state.secure = it }
    }
    if (state.type == ConnectionType.FTPS) {
        // 端口联动：隐式 TLS 标准端口是 990——打开时从默认 21 切到 990、关闭时切回 21
        //（用户手改过的其他端口不动；FtpConfig 的 990 兜底对 UI 默认 21 不可达，联动必须在这里做）
        SwitchRow("隐式 TLS（990 端口）", state.implicitTls) { on ->
            state.implicitTls = on
            if (on && state.port == "21") state.port = "990"
            if (!on && state.port == "990") state.port = "21"
        }
    }
    if (state.type == ConnectionType.FTP || state.type == ConnectionType.FTPS) {
        SwitchRow("被动模式（PASV/EPSV，推荐）", state.passive) { state.passive = it }
    }
    if (state.type == ConnectionType.FTP || state.type == ConnectionType.FTPS) {
        Text("编码", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("UTF-8", "GBK", "GB18030", "Big5").forEach { enc ->
                TextButton(onClick = { state.encoding = enc }) {
                    Text(
                        enc,
                        color = if (state.encoding == enc) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
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
    SwitchRow("在侧拉栏隐藏地址", state.hiddenInDrawer) { state.hiddenInDrawer = it }
}

/** 缩略图选项（可展开，MT 的「缩略图选项 >>」）。 */
@Composable
internal fun ThumbnailOptions(state: ConnectionEditState, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("缩略图选项", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (expanded) "⌃" else "≫",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            SwitchRow("加载缩略图", state.loadThumbs) { state.loadThumbs = it }
            Text(
                "关闭后该连接中的网络图片不再加载列表缩略图（点开仍可正常预览）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「主机 / IP」/ WebDAV URL 右侧的小闪电按钮：点一下读取默认网关并填入。
 *
 * 纯图标（18dp 图标 + 36dp 点击区）：不再挤占主机名 / 域名 / URL；无障碍描述写明真实作用。
 */
@Composable
internal fun GatewayFillButton(enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(36.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "读取默认网关" },
        contentAlignment = Alignment.Center,
    ) {
        MtVectorIcon(icon = MtIcon.BOLT, size = 18.dp, tint = color)
    }
}

/** MT 样式开关行：左文字、右 Switch。 */
@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
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

/** 密码框右侧的 👁 可见性切换（与主密码框同款）。 */
@Composable
internal fun PasswordEye(visible: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        MtVectorIcon(
            icon = if (visible) MtIcon.EYE_OFF else MtIcon.EYE,
            size = 22.dp,
            tint = if (visible) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
