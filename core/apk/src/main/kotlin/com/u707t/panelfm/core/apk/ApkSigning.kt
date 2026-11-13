package com.u707t.panelfm.core.apk

import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.TBSCertificate
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.Validity
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/** 一对签名材料（私钥 + 自签证书） */
class ApkSigningKey(val privateKey: PrivateKey, val certificate: X509Certificate)

/**
 * 内置「测试证书」：第一次转换时生成 RSA 2048 + 自签 X.509，存成 PKCS12 放在应用私有目录。
 *
 * 为什么要它：分包合并后原签名必然失效（改了 zip 内容），不重签的 APK 装不上。
 * 为什么不引 bcpkix：证书只需要一张自签的，用 bcprov 的 ASN.1 直出 DER 就够（省 1 MB 依赖）。
 *
 * ⚠️ 与 MT 的内置测试 key 同一性质：**只用于本地自用**，签名跟官方包不同，
 * 覆盖安装前必须先卸载官方版本；换设备 / 清数据的签名也不同。
 */
class ApkSigningKeyStore(private val dir: File) {

    private val lock = Any()
    private var warning: String? = null
    private val file: File get() = File(dir, "panelfm-test.p12")

    /**
     * 最近一次 [loadOrCreate] 的告警（null = 一切正常）。
     *
     * 两条路径都必须让用户看见：① p12 存在但读不出来 → 会**换一张新证书**（已装过的转换包
     * 从此「签名不一致」）；② 新证书落盘失败 → 下次转换又是另一张，等于每次都换签名。
     * 旧实现把两者都吞在 runCatching 里，用户只会看到「装不上」。
     */
    val lastWarning: String? get() = synchronized(lock) { warning }

    fun loadOrCreate(): ApkSigningKey = synchronized(lock) {
        warning = null
        dir.mkdirs()
        read()?.let { return it }
        if (file.isFile) {
            warning = "内置签名材料读取失败，已重新生成：之前转换出的 APK 需要先卸载才能覆盖安装"
        }
        val fresh = generate()
        if (!write(fresh)) {
            warning = "签名材料未能保存（磁盘空间不足？）：本次签名只对这一个文件有效，下次转换会换签名"
        }
        fresh
    }

    private fun read(): ApkSigningKey? = runCatching {
        if (!file.isFile) return@runCatching null
        val store = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { store.load(it, PASSWORD) }
        val key = store.getKey(ALIAS, PASSWORD) as? PrivateKey ?: return@runCatching null
        val cert = store.getCertificate(ALIAS) as? X509Certificate ?: return@runCatching null
        ApkSigningKey(key, cert)
    }.getOrNull()

    /** @return false = 落盘失败（调用方要把告警带给用户） */
    private fun write(key: ApkSigningKey): Boolean = runCatching {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry(ALIAS, key.privateKey, PASSWORD, arrayOf(key.certificate))
        FileOutputStream(file).use { store.store(it, PASSWORD) }
        true
    }.getOrDefault(false)

    private fun generate(): ApkSigningKey {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()

        val now = System.currentTimeMillis()
        val subject = X500Name("CN=PanelFM Test, O=PanelFM, C=CN")
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val tbs = TBSCertificate(
            ASN1Integer.TWO, // v3
            ASN1Integer(BigInteger.valueOf(now)),
            algorithm,
            subject,
            Validity(Time(Date(now - DAY_MILLIS)), Time(Date(now + 30L * 365 * DAY_MILLIS))),
            subject,
            SubjectPublicKeyInfo.getInstance(pair.public.encoded),
            null,
            null,
            null,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(tbs.encoded)
            sign()
        }
        val der = Certificate(tbs, algorithm, DERBitString(signature)).encoded
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        return ApkSigningKey(pair.private, certificate)
    }

    private companion object {
        const val ALIAS = "panelfm"
        val PASSWORD = "panelfm-test".toCharArray()
        const val DAY_MILLIS = 24L * 3600 * 1000
    }
}

object ApkSignerService {

    /**
     * 用 apksig 重签：v2 + v3（Android 7+ 覆盖 v2，9+ 覆盖 v3），minSdk < 24 时补 v1。
     * `.so` 按 16 KB 页对齐 —— Android 15 的 16 KB 页设备要求库在 APK 里按 16 KB 对齐。
     */
    fun sign(input: File, output: File, key: ApkSigningKey, minSdk: Int) {
        val sdk = minSdk.coerceIn(21, 35)
        val config = ApkSigner.SignerConfig.Builder(
            "PANELFM",
            key.privateKey,
            listOf(key.certificate),
        ).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(sdk)
            .setV1SigningEnabled(sdk < 24)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .setAlignFileSize(false)
            .setLibraryPageAlignmentBytes(16 * 1024)
            .build()
            .sign()
    }
}
