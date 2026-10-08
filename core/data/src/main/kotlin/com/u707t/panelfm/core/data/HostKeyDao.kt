package com.u707t.panelfm.core.data

import android.content.ContentValues
import com.u707t.panelfm.core.vfs.HostKeyStore

/** SFTP 主机指纹（known_hosts 语义）：首次信任即记录，指纹变化时由协议层拒绝。 */
class HostKeyDao(private val db: PanelDb) : HostKeyStore {

    override fun fingerprint(host: String, port: Int): String? =
        db.readableDatabase.query(
            "known_host", arrayOf("fingerprint_sha256"), "host = ? AND port = ?",
            arrayOf(host, port.toString()), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    override fun trust(host: String, port: Int, fingerprint: String, keyType: String) {
        db.writableDatabase.insertWithOnConflict(
            "known_host", null,
            ContentValues().apply {
                put("host", host)
                put("port", port)
                put("kind", "ssh")
                put("key_type", keyType)
                put("fingerprint_sha256", fingerprint)
                put("added_at", System.currentTimeMillis())
            },
            5,
        )
    }

    override fun forget(host: String, port: Int) {
        db.writableDatabase.delete("known_host", "host = ? AND port = ?", arrayOf(host, port.toString()))
    }
}
