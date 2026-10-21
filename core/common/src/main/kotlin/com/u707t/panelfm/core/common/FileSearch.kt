package com.u707t.panelfm.core.common

/**
 * 文件名搜索（目录内过滤与递归搜索共用）。
 *
 * 语法，按这个顺序判断：
 *  - 空白：全部命中
 *  - `!/` 开头：正则否定；`/` 开头：正则。正则写错 = **不命中**
 *    （旧实现是「全命中」，一个打错的括号会把整个目录倒出来；现在对话框侧会先提示「正则表达式有误」）
 *  - `!` 开头：对后面的规则取反
 *  - 含 `*` / `?`：通配符（MT `0x7f11061c`「欲搜索文件名 (支持通配符*和?)」）。
 *    `*` 任意长度、`?` 恰好一个字符；`\*` / `\?` / `\\` 为对应字面量；大小写不敏感
 *  - 否则：子串包含，大小写不敏感
 *
 * [rank] 给结果排序：0 完全同名、1 以关键字开头、2 其它命中；不命中返回 null。
 * 否定和正则不参与「同名优先」（一律 2）。
 *
 * 性能约定：规则是**每次操作编译一次**的。目录过滤 / 递归搜索请用 [compile] 拿 [Matcher] 复用，
 * 不要逐条调用 [matches] —— 旧实现每个文件名都现编译一次正则，大目录里是数万次编译。
 */
object FileSearch {

    /** 内容搜索直接跳过的二进制后缀（扩展名已知、打开也是乱码的一类）。 */
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

    /**
     * 一次编译、多次匹配（目录过滤与递归搜索复用同一个实例）。
     *
     * 构造时就把正则 / glob 编译好并固定「取反 / 子串」分支；[matches] 与 [rank] 只做匹配，
     * 不再分配或编译。行为与一次性接口 [FileSearch.matches] / [FileSearch.rank] 完全一致。
     */
    class Matcher internal constructor(query: String) {

        private val q: String = query.trim()
        private val negate: Boolean
        private val regex: Regex?
        private val literal: String?

        /** glob 是「整串匹配」（锚定正则），正则语法是「包含匹配」——两者不能共用 containsMatchIn。 */
        private val glob: Boolean

        init {
            var neg = false
            var rx: Regex? = null
            var lit: String? = null
            var isGlob = false
            when {
                q.startsWith("!/") -> {
                    neg = true
                    rx = compileRegex(q.removePrefix("!/"))
                }
                q.startsWith("/") -> {
                    rx = compileRegex(q.removePrefix("/"))
                }
                q.startsWith("!") -> {
                    neg = true
                    val body = q.removePrefix("!")
                    if (FileSearch.containsWildcard(body) || FileSearch.hasEscape(body)) {
                        rx = globRegex(body)
                        isGlob = true
                    } else {
                        lit = body
                    }
                }
                else -> {
                    if (FileSearch.containsWildcard(q) || FileSearch.hasEscape(q)) {
                        rx = globRegex(q)
                        isGlob = true
                    } else {
                        lit = q
                    }
                }
            }
            negate = neg
            regex = rx
            literal = lit
            glob = isGlob
        }

        fun matches(name: String): Boolean {
            if (q.isEmpty()) return true
            val hit = when {
                // glob 必须整串匹配：Regex.containsMatchIn 在 "$" 前允许末尾换行，会漏出 `a\n` 这类文件名
                regex != null -> if (glob) regex.matches(name) else regex.containsMatchIn(name)
                literal != null -> literal.isEmpty() || name.contains(literal, ignoreCase = true)
                else -> false
            }
            return if (negate) !hit else hit
        }

        fun rank(name: String): Int? {
            if (!matches(name)) return null
            if (q.isEmpty() || q.startsWith("/") || q.startsWith("!/") || q.startsWith("!")) return 2
            if (name.equals(q, ignoreCase = true)) return 0
            // 与旧实现同一口径：含未转义通配符的规则不参与「前缀优先」
            if (!FileSearch.containsWildcard(q) && name.startsWith(q, ignoreCase = true)) return 1
            return 2
        }
    }

    /** 编译一次，拿到可复用的匹配器。 */
    fun compile(query: String): Matcher = Matcher(query)

    fun matches(name: String, query: String): Boolean = Matcher(query).matches(name)

    /** 搜索类型选了「正则」时用：非法正则不命中。大小写按用户写的正则来（敏感）。 */
    fun matchesRegex(name: String, pattern: String): Boolean =
        compileRegex(pattern)?.containsMatchIn(name) ?: false

    fun rank(name: String, query: String): Int? = Matcher(query).rank(name)

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

    /**
     * 未转义的 `*` 或 `?`（`\*` 这类转义对不算通配）。
     * 只有 `\` 后面跟 `\` / `*` / `?` 时才构成转义；`\` 后跟其它字符时，`\` 按字面量看。
     */
    internal fun containsWildcard(pattern: String): Boolean {
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\' && i + 1 < pattern.length && pattern[i + 1] in "\\*?") {
                i += 2
                continue
            }
            if (c == '*' || c == '?') return true
            i++
        }
        return false
    }

    /** 是否含转义对（`\\` / `\*` / `\?`）。 */
    internal fun hasEscape(pattern: String): Boolean {
        var i = 0
        while (i < pattern.length - 1) {
            if (pattern[i] == '\\' && pattern[i + 1] in "\\*?") return true
            i++
        }
        return false
    }
}

/** 正则编译失败返回 null（调用方按「不命中」处理）。 */
private fun compileRegex(pattern: String): Regex? = runCatching { Regex(pattern) }.getOrNull()

/** glob → 锚定正则；编译失败返回 null。 */
private fun globRegex(pattern: String): Regex? {
    val sb = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c == '\\' && i + 1 < pattern.length && pattern[i + 1] in "\\*?") {
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
    return runCatching { Regex("^$sb$", RegexOption.IGNORE_CASE) }.getOrNull()
}
