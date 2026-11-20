package com.u707t.panelfm.core.common

import java.security.MessageDigest
import java.util.Locale

/**
 * 十六进制与摘要的**唯一实现**（缓存键 / 校验值 / 签名指纹 / S3 签名共用）。
 *
 * ## 为什么收成一处
 *
 * `joinToString("") { "%02x".format(Locale.ROOT, it) }` 这行在本仓被手写了 9 次
 * （缩略图缓存键、PDF 物化键、APK 物化键、校验值、签名摘要、S3 SigV4、会话指纹…）。
 * 每次抄一遍就多一次漏写 `Locale.ROOT` 的机会 —— 阿拉伯语等 locale 会把 `%x` 的数字
 * 本地化成非 ASCII 字符，于是缓存键随系统语言漂移、校验值跟别的工具对不上。
 */

/** 小写十六进制（固定 ASCII，与 locale 无关） */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

/**
 * 摘要 → 小写十六进制。
 *
 * @param takeBytes >0 时只保留前 N 字节（缓存键 / 指纹够用，文件名也更短）；
 *                  超出摘要长度时按全长输出，不报错
 */
fun digestHex(algorithm: String, data: ByteArray, takeBytes: Int = 0): String {
    val digest = MessageDigest.getInstance(algorithm).digest(data)
    val slice = if (takeBytes in 1..digest.size) digest.copyOf(takeBytes) else digest
    return slice.toHex()
}

/** SHA-256 → 小写十六进制（[takeBytes] 见 [digestHex]） */
fun sha256Hex(data: ByteArray, takeBytes: Int = 0): String = digestHex("SHA-256", data, takeBytes)

/** SHA-1 → 小写十六进制（缩略图磁盘缓存文件名用） */
fun sha1Hex(data: ByteArray): String = digestHex("SHA-1", data)
