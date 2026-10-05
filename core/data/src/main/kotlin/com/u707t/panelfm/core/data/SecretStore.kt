package com.u707t.panelfm.core.data

import android.content.ContentValues
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.u707t.panelfm.core.common.Logx
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 口令保险箱：Android Keystore 主密钥（AES-256-GCM）+ 每条独立 IV，密文存 SQLite。
 * 不依赖已废弃的 androidx.security-crypto。
 *
 * 失败语义（重要）：
 *  - [put] 返回是否成功；**失败不能被当成保存成功**（旧实现 runCatching 后只记日志，
 *    UI 依然提示「已保存」→ 用户下次连接才发现口令丢了，且无任何线索）。
 *  - [get] 任何异常（密文损坏 / Keystore 被系统重置 / 设备迁移）都返回 null，
 *    绝不向上抛：调用方在组合期读取它，抛异常会直接崩界面。
 */
class SecretStore(private val db: PanelDb) {

    /** @return true = 已写入（或已删除）；false = 写入失败，调用方必须提示用户 */
    fun put(ref: String, plain: String?): Boolean {
        if (plain.isNullOrEmpty()) {
            delete(ref)
            return true
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val values = ContentValues().apply {
                put("ref", ref)
                put("cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                put("updated_at", System.currentTimeMillis())
            }
            db.writableDatabase.insertWithOnConflict("secret", null, values, 5 /* REPLACE */)
            true
        }.onFailure { Logx.e("SecretStore", "put failed: ${it.message}", it) }
            .getOrDefault(false)
    }

    fun get(ref: String): String? = runCatching {
        db.readableDatabase.query("secret", arrayOf("cipher", "iv"), "ref = ?", arrayOf(ref), null, null, null)
            .use { c ->
                if (!c.moveToFirst()) return@runCatching null
                val cipherText = Base64.decode(c.getString(0), Base64.NO_WRAP)
                val iv = Base64.decode(c.getString(1), Base64.NO_WRAP)
                runCatching {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
                    String(cipher.doFinal(cipherText), Charsets.UTF_8)
                }.onFailure { Logx.e("SecretStore", "decrypt failed (key invalidated?): ${it.message}", it) }
                    .getOrNull()
            }
    }.onFailure { Logx.e("SecretStore", "get failed: ${it.message}", it) }.getOrNull()

    fun delete(ref: String) {
        db.writableDatabase.delete("secret", "ref = ?", arrayOf(ref))
    }

    fun has(ref: String): Boolean =
        db.readableDatabase.query("secret", arrayOf("ref"), "ref = ?", arrayOf(ref), null, null, null).use { it.count > 0 }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "panelfm.master.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
