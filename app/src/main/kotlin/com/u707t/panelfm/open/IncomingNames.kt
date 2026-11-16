package com.u707t.panelfm.open

import java.io.File

/**
 * 「用其他应用打开」落盘时的文件名处理（纯函数，可单测）。
 *
 * 外部 App 给的 `DISPLAY_NAME` 不可信：可能带路径分隔符、控制字符、Windows 非法字符，
 * 甚至就是 `..`。这里统一清洗，并保证：
 *  - 不逃出 `cache/incoming`；
 *  - 长度可控（保留扩展名）；
 *  - 同名不覆盖（追加 ` (1)`、` (2)`）。
 */

private const val MAX_NAME_LENGTH = 120
private const val MAX_EXTENSION_LENGTH = 16

internal fun sanitizeIncomingName(raw: String): String {
    // 只取最后一段：既处理 `/` 也处理 Windows 的 `\`，再清洗非法字符。
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base
        .replace(Regex("[\\x00-\\x1F<>:\"|?*]"), "_")
        .trim()
        .trimEnd('.')
    if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
        return "incoming-${System.currentTimeMillis()}"
    }
    // 截断时保留扩展名：`very-long-name.pdf` 不能变成 `very-long-na`。
    val dot = cleaned.lastIndexOf('.')
    if (dot <= 0) return cleaned.take(MAX_NAME_LENGTH)
    val ext = cleaned.substring(dot).take(MAX_EXTENSION_LENGTH)
    val stem = cleaned.substring(0, dot)
    val keep = (MAX_NAME_LENGTH - ext.length).coerceAtLeast(1)
    return (stem.take(keep) + ext).ifBlank { "incoming-${System.currentTimeMillis()}" }
}

internal fun uniqueIncomingTarget(dir: File, name: String): File {
    val first = File(dir, name)
    if (!first.exists()) return first
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (true) {
        val candidate = File(dir, "$stem ($i)$ext")
        if (!candidate.exists()) return candidate
        i++
    }
}
