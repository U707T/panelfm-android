package com.u707t.panelfm.open

/**
 * 把外部 App 传来的 `content://` 反查成本地绝对路径时用到的**纯规则**（无 Android 依赖，可 JVM 单测）。
 *
 * 为什么单独一层：DocumentsProvider 的 document id 格式各厂商不一致，解析出来的是不是
 * 「能定位的真实路径」还要再判一次可见性。这两步都是字符串运算，抽出来才能在没有
 * Android 环境的机器上验证；真正查 ContentResolver / MediaStore 的部分在
 * [ContentPathResolver]。
 */

/** 反查结果：能直接浏览 / 路径存在但我们读不了 / 完全定位不了。 */
internal enum class LocalPathVisibility {
    /** 落在用户可浏览的卷里，可以直接定位原文件。 */
    BROWSABLE,

    /** 是真实路径，但属于受限目录（`Android/data`、`Android/obb` 等），只能定位副本。 */
    PROTECTED,

    /** 拿不到可用路径（provider 只给数字 id、或 id 结构不认识）。 */
    UNKNOWN,
}

internal const val EMULATED_ROOT: String = "/storage/emulated/0"

private val VOLUME_ID = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

/**
 * 规范化绝对路径：合并 `//`、消掉 `.`、按段处理 `..`。
 *
 * 返回 null 表示**不可接受**：不是绝对路径、空串，或 `..` 试图爬出根目录。
 * 这一点很重要 —— document id 来自外部应用，不能让它凭 `../../../data` 指到别处。
 */
internal fun normalizeAbsolutePath(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || !value.startsWith("/")) return null
    val out = ArrayDeque<String>()
    for (segment in value.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (out.isEmpty()) return null else out.removeLast()
            else -> out.addLast(segment)
        }
    }
    return "/" + out.joinToString("/")
}

/**
 * DocumentsProvider 的 document id → 绝对路径。
 *
 * 支持 Android 上实际会遇到的几类：
 *  - `raw:/storage/emulated/0/Download/a.pdf`（Downloads / 部分第三方）
 *  - `primary:Download/a.pdf`（内置存储，DocumentsUI）
 *  - `home:Documents/a.pdf`（DocumentsUI 的「文档」根，实际落在内置存储根）
 *  - `1234-ABCD:foo/a.pdf`（外置卡的 volume id）
 *
 * 返回 null = 不认识（例如 Downloads 的数字 id `msf:1000000123`），交给上层降级。
 *
 * ⚠️ 相对段必须**留在所属卷内**：`primary:../../data/x` 规范化后会变成
 * `/storage/data/x` —— 它没有爬出 `/`，却爬出了内置存储根，等于借 document id
 * 跳到别的卷。因此 primary/home/卷 id 解析完都要再确认一次前缀。
 */
internal fun documentIdToAbsolutePath(documentId: String?): String? {
    val id = documentId?.trim().orEmpty()
    if (id.isEmpty()) return null
    val colon = id.indexOf(':')
    if (colon <= 0) return null
    val kind = id.substring(0, colon)
    val rest = id.substring(colon + 1)
    return when {
        kind.equals("raw", ignoreCase = true) -> normalizeAbsolutePath(rest)
        kind.equals("primary", ignoreCase = true) -> confined("$EMULATED_ROOT/$rest", EMULATED_ROOT)
        kind.equals("home", ignoreCase = true) -> confined("$EMULATED_ROOT/$rest", EMULATED_ROOT)
        VOLUME_ID.matches(kind) -> confined("/storage/$kind/$rest", "/storage/$kind")
        else -> null
    }
}

/** 规范化 [candidate]，并要求它仍在 [volumeRoot] 之下（含根本身）。 */
private fun confined(candidate: String, volumeRoot: String): String? {
    val path = normalizeAbsolutePath(candidate) ?: return null
    return if (isUnderAbsolute(path, volumeRoot)) path else null
}

/** 卷根 + 相对路径，用于把绝对路径还原成 VfsUri。 */
internal data class VolumeMapping(val authority: String, val relativePath: String)

/**
 * 绝对路径 → 可浏览卷的 authority + 卷内相对路径。
 *
 * 与 `LocalVfs.absolutePath` 互为反函数（`emulated` / `vol:` / `root` 三种形态）。
 * 返回 null = 该路径不属于任何可浏览卷（调用方应降级为复制副本）。
 */
internal fun absolutePathToVolume(absolutePath: String?): VolumeMapping? {
    val path = normalizeAbsolutePath(absolutePath) ?: return null
    if (isUnderAbsolute(path, EMULATED_ROOT)) {
        val rel = path.removePrefix(EMULATED_ROOT).ifEmpty { "/" }
        return VolumeMapping("emulated", rel)
    }
    if (path.startsWith("/storage/")) {
        val rest = path.removePrefix("/storage/")
        val slash = rest.indexOf('/')
        val volumeName = if (slash < 0) rest else rest.substring(0, slash)
        // 卷名形如 1234-ABCD。与 LocalVolumes 一致：authority = "vol:" + 去首斜杠后把 '/' 换成 '_'，
        // 也就是 vol:storage_1234-ABCD（连字符保留，只有 '/' 变 '_'）。
        if (VOLUME_ID.matches(volumeName)) {
            val rel = if (slash < 0) "/" else rest.substring(slash)
            return VolumeMapping("vol:storage_$volumeName", rel)
        }
        return null
    }
    if (path.startsWith("/data/") || path.startsWith("/system/") ||
        path.startsWith("/vendor/") || path.startsWith("/product/")
    ) {
        return null
    }
    return VolumeMapping("root", path)
}

/** [child] 是否等于 [parent] 或在其下（都应是规范化后的绝对路径）。 */
internal fun isUnderAbsolute(child: String, parent: String): Boolean {
    val a = normalizeAbsolutePath(child) ?: return false
    val b = normalizeAbsolutePath(parent) ?: return false
    return a == b || a.startsWith("$b/")
}

/**
 * 判断这个绝对路径能不能在应用里直接定位。
 *
 * `Android/data`、`Android/obb` 在 Android 11+ 即使拿到「所有文件访问」也读不到，
 * 系统层面就不存在可浏览的真实路径 —— 这类只当 PROTECTED，让上层退回「定位接收副本」。
 */
internal fun localPathVisibility(absolutePath: String?): LocalPathVisibility {
    val path = normalizeAbsolutePath(absolutePath) ?: return LocalPathVisibility.UNKNOWN
    if (path == "/") return LocalPathVisibility.UNKNOWN
    if (isUnderAbsolute(path, "$EMULATED_ROOT/Android/data") ||
        isUnderAbsolute(path, "$EMULATED_ROOT/Android/obb")
    ) {
        return LocalPathVisibility.PROTECTED
    }
    if (isUnderAbsolute(path, EMULATED_ROOT)) return LocalPathVisibility.BROWSABLE
    // 外置卡：/storage/XXXX-XXXX/...，同样要排除应用私有目录。
    if (path.startsWith("/storage/")) {
        val parts = path.split('/')
        if (parts.size >= 5 && parts[3] == "Android" && (parts[4] == "data" || parts[4] == "obb")) {
            return LocalPathVisibility.PROTECTED
        }
        return LocalPathVisibility.BROWSABLE
    }
    // /data、/system 等系统区：是真实路径，但普通应用不可浏览。
    if (isUnderAbsolute(path, "/data") || isUnderAbsolute(path, "/system") ||
        isUnderAbsolute(path, "/vendor") || isUnderAbsolute(path, "/product")
    ) {
        return LocalPathVisibility.PROTECTED
    }
    return LocalPathVisibility.UNKNOWN
}
