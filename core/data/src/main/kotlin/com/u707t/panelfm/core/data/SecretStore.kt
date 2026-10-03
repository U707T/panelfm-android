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
 */
class SecretStore(private val db: PanelDb) {

    fun put(ref: String, plain: String?) {
        if (plain.isNullOrEmpty()) {
            delete(ref)
            return
        }
        runCatching {
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
        }.onFailure { Logx.e("SecretStore", "put failed: ${it.message}", it) }
    }

    fun get(ref: String): String? {
        db.readableDatabase.query("secret", arrayOf("cipher", "iv"), "ref = ?", arrayOf(ref), null, null, null)
            .use { c ->
                if (!c.moveToFirst()) return null
                val cipherText = Base64.decode(c.getString(0), Base64.NO_WRAP)
                val iv = Base64.decode(c.getString(1), Base64.NO_WRAP)
                return runCatching {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
                    String(cipher.doFinal(cipherText), Charsets.UTF_8)
                }.onFailure { Logx.e("SecretStore", "decrypt failed (key invalidated?): ${it.message}", it) }
                    .getOrNull()
            }
    }

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
