package com.u707t.panelfm.core.vfs

/** 主机密钥指纹（SFTP known_hosts / 自签证书）持久化。 */
interface HostKeyStore {
    fun fingerprint(host: String, port: Int): String?
    fun trust(host: String, port: Int, fingerprint: String, keyType: String)
    fun forget(host: String, port: Int)
}
