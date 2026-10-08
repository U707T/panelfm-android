package com.u707t.panelfm.core.common

/**
 * 文件名搜索（目录内过滤与递归搜索共用）。
 *
 * 语法，按这个顺序判断：
 *  - 空白：全部命中
 *  - `!/` 开头：正则否定；`/` 开头：正则。正则写错 = **不命中**
 *    （旧实现是「全命中」，一个打错的括号会把整个目录倒出来）
 *  - `!` 开头：对后面的规则取反
 *  - 含未转义的 `*` / `?`：通配符（MT `0x7f11061c`「欲搜索文件名 (支持通配符*和?)」）。
 *    `*` 任意长度，`?` 恰好一个字符，`\*` / `\?` / `\\` 为字面；大小写不敏感
 *  - 否则：子串包含，大小写不敏感
 *
 * [rank] 给结果排序：0 完全同名、1 以关键字开头、2 其它命中；不命中返回 null。
 * 否定和正则不参与「同名优先」（一律 2）。
 */
object FileSearch {

    /** 内容搜索直接跳过的二进制后缀（扩展名不在已知文本类里、打开也是乱码）。 */
    private val binaryExtensions = setOf(
        "so", "bin", "iso", "img", "exe", "dll", "dylib", "o", "a", "obj",
        "class", "pyc", "pyo", "wasm", "dat", "pak", "obb", "dex", "dmg",
    )

    /**
     * 内容搜索要不要打开这个文件。
     * 图片 / 音视频 / 压缩包 / 办公文档 / 数据库等已知二进制直接跳过，避免把半个盘读一遍。
     */
    fun worthContentScan(extension: String): Boolean {
        if (SqliteFormats.isSqlite(extension)) return false
        val ext = extension.lowercase()
        if (ext in binaryExtensions) return false
        return when (MimeTypes.kindOf(ext)) {
            MimeTypes.Kind.TEXT, MimeTypes.Kind.CODE, MimeTypes.Kind.OTHER -> true
            else -> false
        }
    }

    fun matches(name: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return when {
            q.startsWith("!/") -> !regexMatch(name, q.removePrefix("!/"))
            q.startsWith("/") -> regexMatch(name, q.removePrefix("/"))
            q.startsWith("!") -> !positiveMatch(name, q.removePrefix("!"))
            else -> positiveMatch(name, q)
        }
    }

    /** 搜索类型选了「正则」时用：非法正则不命中。大小写按用户写的正则来（敏感）。 */
    fun matchesRegex(name: String, pattern: String): Boolean = regexMatch(name, pattern)

    fun rank(name: String, query: String): Int? {
        if (!matches(name, query)) return null
        val q = query.trim()
        if (q.isEmpty() || q.startsWith("/") || q.startsWith("!/") || q.startsWith("!")) return 2
        if (name.equals(q, ignoreCase = true)) return 0
        if (!containsWildcard(q) && name.startsWith(q, ignoreCase = true)) return 1
        return 2
    }

    /** 父路径相对搜索根的展示。就在搜索起点时显示「当前目录」。 */
    fun relativeParent(rootPath: String, parentPath: String?): String {
        fun norm(p: String): String {
            val t = p.trim().ifEmpty { "/" }
            return if (t == "/") "/" else t.trimEnd('/')
        }
        val root = norm(rootPath)
        val parent = norm(parentPath ?: "/")
        return when {
            parent == root -> "当前目录"
            root != "/" && parent.startsWith("$root/") -> parent.removePrefix("$root/")
            else -> parent
        }
    }

    /** 过滤语法里的正则是否写错（不是正则语法则返回 false）。 */
    fun regexError(query: String): Boolean {
        val q = query.trim()
        val body = when {
            q.startsWith("!/") -> q.removePrefix("!/")
            q.startsWith("/") -> q.removePrefix("/")
            else -> return false
        }
        return runCatching { Regex(body) }.isFailure
    }

    private fun positiveMatch(name: String, raw: String): Boolean {
        if (raw.isEmpty()) return true
        return if (containsWildcard(raw)) globMatch(name, raw) else name.contains(raw, ignoreCase = true)
    }

    private fun regexMatch(name: String, pattern: String): Boolean =
        runCatching { Regex(pattern).containsMatchIn(name) }.getOrDefault(false)

    /** 未转义的 `*` 或 `?`。`\*` 不算通配。 */
    internal fun containsWildcard(pattern: String): Boolean {
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] == '\\' && i + 1 < pattern.length) {
                i += 2
                continue
            }
            if (pattern[i] == '*' || pattern[i] == '?') return true
            i++
        }
        return false
    }

    private fun globMatch(name: String, pattern: String): Boolean {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\' && i + 1 < pattern.length) {
                sb.append(Regex.escape(pattern[i + 1].toString()))
                i += 2
                continue
            }
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return runCatching { Regex("^$sb$", RegexOption.IGNORE_CASE).matches(name) }.getOrDefault(false)
    }
}
