package com.u707t.panelfm.core.vfs.sftp

import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.File
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/** 私钥加载与指纹计算（OpenSSH / PEM，覆盖 ed25519 / ecdsa / rsa）。 */
object SshKeys {

    /** SHA-256 指纹，格式与 OpenSSH 一致：`SHA256:xxxxx` */
    fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun keyType(key: PublicKey): String = key.algorithm

    /** 加载私钥（可带口令）。支持 `-----BEGIN OPENSSH PRIVATE KEY-----` 与 PEM。 */
    fun loadKeyPairs(path: String, passphrase: String?): List<KeyPair> {
        val file = File(path)
        require(file.exists()) { "私钥文件不存在：$path" }
        val provider = if (passphrase.isNullOrEmpty()) FilePasswordProvider.EMPTY else FilePasswordProvider.of(passphrase)
        return file.inputStream().use { input ->
            SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(file.name), input, provider).toList()
        }
    }

    /** 生成 OpenSSH 公钥行（可直接粘贴到服务器 authorized_keys） */
    fun publicKeyLine(key: PublicKey, comment: String = "panelfm"): String =
        PublicKeyEntry.toString(key) + " " + comment
}
