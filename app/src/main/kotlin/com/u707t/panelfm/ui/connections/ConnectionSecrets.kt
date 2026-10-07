package com.u707t.panelfm.ui.connections

import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.vfs.sftp.SftpSecrets

/**
 * 连接口令的「表单值 ↔ 落库格式」编解码（纯函数，可单测）。
 *
 * 落库约定（经 SecretStore 加密后存）：
 *  - S3：**纯 secretKey**（accessKey 存在 `ConnectionConfig.user` 列；读取端 `S3Config.from`
 *    同时兼容旧的 `AK:SK` 拼接与裸 SK 两种历史形态）；
 *  - SFTP：`SftpSecrets` JSON（登录密码 / 私钥口令 / 跳板机密码）；
 *  - FTP / FTPS / WebDAV / SMB：裸密码。
 *
 * 为什么必须收拢到一处（第 7 批 🔴1）：旧实现回填按 SFTP 格式解析 S3 的 `AK:SK`（整串塞进
 * 密码框），保存时再拼一次 `"$user:$password"`——每编辑保存一次就多叠一层 `AK:`（`AK:SK`
 * → `AK:AK:SK` → …），连接端 `split(':').last()` 拿到 `AK:SK` 当密钥，认证必挂。
 * 链上三处代码各自「看起来对」，谁也没有错——错在没有单一事实源。
 */
object ConnectionSecrets {

    /** S3：从存储串取 secretKey 部分（兼容旧 `AK:SK` 拼接；无冒号 = 整串是 SK）。 */
    fun s3SecretKeyOf(raw: String?): String {
        val s = raw.orEmpty()
        val parts = s.split(':', limit = 2)
        return if (parts.size == 2) parts[1] else s
    }

    /** 编辑回填：password 输入框应显示的值。 */
    fun passwordForEdit(type: ConnectionType?, raw: String?): String = when (type) {
        ConnectionType.S3 -> s3SecretKeyOf(raw)
        ConnectionType.SFTP -> SftpSecrets.parse(raw).password.orEmpty()
        else -> raw.orEmpty()
    }

    /** 保存：由表单值构建要写入 SecretStore 的字符串（null = 清除）。 */
    fun build(
        type: ConnectionType,
        password: String,
        keyPassphrase: String,
        jumpPassword: String,
    ): String? = when (type) {
        // 纯 SK 落库；读取端 S3Config.from 对「有 AK 前缀 / 无前缀」都能取对 SK
        ConnectionType.S3 -> password.ifEmpty { null }
        ConnectionType.SFTP -> SftpSecrets(
            password = password.ifEmpty { null },
            keyPassphrase = keyPassphrase.ifEmpty { null },
            jumpPassword = jumpPassword.ifEmpty { null },
        ).toJson().takeIf { password.isNotEmpty() || keyPassphrase.isNotEmpty() || jumpPassword.isNotEmpty() }
        else -> password.ifEmpty { null }
    }
}
