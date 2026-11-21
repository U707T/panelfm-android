package com.u707t.panelfm.ui.tools

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 「已安装应用」列表的磁盘缓存。
 *
 * ## 为什么需要
 *
 * 每次进页面都要问 `PackageManager` 要 600+ 个包、逐个取标签 / 版本 / 大小，
 * 冷启动实测 1–2 秒，页面白屏 +「读取中…」。缓存后**先出上次的列表**（毫秒级），
 * 再在后台刷新并写回 —— 用户看到的是「秒开」，数据仍然是新的。
 *
 * ## 格式与失效
 *
 * 紧凑 JSON（字段名 1–2 字符，640 条约 80 KB），带 `v`（格式版本）与 `at`（写入时间）。
 * 不设 TTL：每次打开都会后台重扫一遍并覆盖，所以缓存只影响「首帧」，不影响正确性。
 * 真正要失效时（格式改了）把 [VERSION] 加一即可，旧文件会被当成损坏丢弃。
 *
 * 读 / 写都只碰 [File]，便于单测（不需要 Context）。
 */
internal object AppListCache {

    const val FILE_NAME = "apps-cache.json"
    const val VERSION = 1

    fun fileOf(cacheDir: File): File = File(cacheDir, FILE_NAME)

    fun encode(apps: List<InstalledApp>, now: Long = System.currentTimeMillis()): String =
        buildJsonObject {
            put("v", VERSION)
            put("at", now)
            putJsonArray("apps") {
                apps.forEach { a ->
                    addJsonObject {
                        put("p", a.packageName)
                        put("l", a.label)
                        put("vn", a.versionName)
                        put("vc", a.versionCode)
                        put("b", a.bytes)
                        put("s", a.isSystem)
                        put("d", a.sourceDir)
                        put("n", a.splitCount)
                        a.className?.let { put("c", it) }
                    }
                }
            }
        }.toString()

    /** 解析失败 / 版本不符 / 结构缺字段都返回 null（当作「没有缓存」，绝不半信半疑地用） */
    fun decode(text: String): List<InstalledApp>? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["v"]?.jsonPrimitive?.intOrNull != VERSION) return null
        val array = root["apps"]?.jsonArray ?: return null
        array.map { element ->
            val o = element.jsonObject
            InstalledApp(
                packageName = o["p"]?.jsonPrimitive?.contentOrNull ?: return null,
                label = o["l"]?.jsonPrimitive?.contentOrNull ?: return null,
                versionName = o["vn"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                versionCode = o["vc"]?.jsonPrimitive?.longOrNull ?: 0L,
                bytes = o["b"]?.jsonPrimitive?.longOrNull ?: 0L,
                isSystem = o["s"]?.jsonPrimitive?.booleanOrNull ?: false,
                sourceDir = o["d"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                splitCount = o["n"]?.jsonPrimitive?.intOrNull ?: 1,
                className = o["c"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }.getOrNull()

    fun read(file: File): List<InstalledApp>? =
        runCatching { if (file.isFile) decode(file.readText()) else null }.getOrNull()

    /** 先写 `.tmp` 再 rename：中途被杀不会留下半截 JSON（下次会被当成损坏丢弃） */
    fun write(file: File, apps: List<InstalledApp>): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(encode(apps))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        true
    }.getOrDefault(false)
}
