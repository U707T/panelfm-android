package com.u707t.panelfm.core.apk

import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** 分包类型（按清单里的 `split` 属性判，不靠文件名） */
enum class SplitKind {
    /** `config.arm64_v8a` 这类原生库分包 */
    ABI,

    /** `config.xxhdpi` 这类密度分包（内容是 res + resources.arsc） */
    DENSITY,

    /** `config.zh` 这类语言分包（同上） */
    LANGUAGE,

    /** 动态功能模块（`split="feature"` + `isFeatureSplit=true`） */
    FEATURE,

    /** 认不出来的分包 */
    OTHER,
    ;

    companion object {
        // bundletool 的 ABI 目录名（split 里是下划线，APK 内 lib/ 目录是连字符）
        private val ABI_TOKENS = setOf(
            "armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64", "riscv64",
        )
        private val DENSITY_TOKENS = setOf(
            "ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "anydpi",
        )

        fun of(splitName: String?, isFeatureSplit: Boolean): SplitKind {
            val split = splitName?.trim().orEmpty()
            if (split.isEmpty()) return OTHER
            if (isFeatureSplit) return FEATURE
            if (!split.startsWith("config.")) return FEATURE
            val token = split.removePrefix("config.")
            return when {
                token in ABI_TOKENS -> ABI
                token in DENSITY_TOKENS -> DENSITY
                else -> LANGUAGE
            }
        }

        /** `config.arm64_v8a` → APK 里的 `lib/arm64-v8a` */
        fun abiFolder(splitName: String?): String? {
            val token = splitName?.trim()?.removePrefix("config.") ?: return null
            if (token !in ABI_TOKENS) return null
            return when (token) {
                "armeabi_v7a" -> "armeabi-v7a"
                "arm64_v8a" -> "arm64-v8a"
                else -> token
            }
        }
    }
}

/** 外层安装包（.apks / .xapk / .apkm，本质都是 zip）里的一个 APK */
data class BundleApk(
    val path: String,
    val size: Long,
    val manifest: ApkManifestInfo?,
) {
    val splitName: String? get() = manifest?.splitName?.trim()?.takeIf { it.isNotEmpty() }

    val kind: SplitKind get() = SplitKind.of(splitName, manifest?.isFeatureSplit == true)

    /** 该分包对应的 `lib/` 目录名（仅 ABI 分包） */
    val abiFolder: String? get() = if (kind == SplitKind.ABI) SplitKind.abiFolder(splitName) else null

    val displayName: String get() = path.substringAfterLast('/')
}

/**
 * 解析结果。
 *
 * @param base        基础包（`split` 为空的 APK）
 * @param splits      其余 APK（ABI / 密度 / 语言 / 功能模块）
 * @param universal   已经是一个完整单 APK 的条目（bundletool `--mode=universal` 的 `universal.apk`）——
 *                    直接取出来即可，签名还是官方的
 * @param standalones bundletool 给的 `standalones/standalone-*.apk`（按 ABI + 密度预先合并好、官方签名）——
 *                    也能直接取，但要挑一个跟本机匹配的
 */
data class ApkBundle(
    val file: File,
    val base: BundleApk?,
    val splits: List<BundleApk>,
    val universal: BundleApk?,
    val standalones: List<BundleApk>,
) {
    /** 有没有现成的单 APK 可以直接取出（不用合并、不用重签） */
    val hasReadyMade: Boolean get() = universal != null || standalones.isNotEmpty()
}

object ApkBundleReader {

    /** 扫嵌套 APK 清单时的解压上限：清单永远在 APK 的第一个条目，超过这个量说明结构异常，直接放弃 */
    private const val NESTED_SCAN_BUDGET = 8L * 1024 * 1024

    /** 单个嵌套清单本身的上限：正常 < 100 KB，超过就是结构异常（也挡住「清单条目自己就是炸弹」） */
    private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024

    /** standby 文件名里的密度分词（用于确认 ABI 后面跟的是密度而不是另一个 ABI 的后半截） */
    private val DENSITY_SEGMENTS = setOf(
        "ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "anydpi",
    )

    fun inspect(file: File): ApkBundle {
        require(file.isFile) { "文件不存在：${file.name}" }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .toList()
            require(entries.isNotEmpty()) { "压缩包里没有 APK（不是标准 .apks / .xapk / .apkm）" }
            val apks = entries.map { entry ->
                BundleApk(entry.name, entry.size, readManifest(zip, entry))
            }
            val universal = apks.firstOrNull { it.path.substringAfterLast('/') == "universal.apk" }
            val standalones = apks.filter { it.path.contains("standalones/") && it != universal }
            val candidates = apks.filter { it != universal && it !in standalones }
            // 基础包 = 清单里没有 split 的那个。名字优先（base.apk / base-master.apk），
            // 清单读不出来（结构异常）时只按名字兜底 —— 不能把「任何读不出清单的分包」当 base，
            // 那会让真正的 base 被当成「没有 split 属性的副本」过滤掉，合并结果直接缺 base。
            val base = candidates.firstOrNull {
                it.path.substringAfterLast('/') == "base-master.apk" || it.path.substringAfterLast('/') == "base.apk"
            } ?: candidates.firstOrNull { it.manifest?.isBase == true }
                ?: candidates.firstOrNull { it.manifest == null && it.displayName.startsWith("base") }
            // bundletool 的 .apks 会按「ABI 变体」把同一份语言/密度分包复制多份
            // （base-af.apk / base-af_2.apk / base-af_3.apk）—— 内容相同，按 split 名去重，
            // 否则界面上会列出几百个「资源分包」，转换时还会重复解压。
            val seenSplits = HashSet<String>()
            val splits = if (base == null) {
                emptyList()
            } else {
                candidates.filter { it != base }
                    // 没有 split 属性的其它 APK = base 在别的 ABI 变体里的副本（base-master_2/_3），
                    // 不是分包；当成分包会显示成「跳过 N 个功能模块」，误导用户。
                    .filter { it.splitName != null }
                    .filter { apk -> seenSplits.add(apk.splitName ?: apk.path) }
            }
            return ApkBundle(file, base, splits, universal, standalones)
        }
    }

    private fun readManifest(zip: ZipFile, entry: ZipEntry): ApkManifestInfo? = runCatching {
        zip.getInputStream(entry).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { nested ->
                var budget = NESTED_SCAN_BUDGET
                while (true) {
                    val next = nested.nextEntry ?: return@runCatching null
                    // 先判后读：ZipInputStream.getNextEntry() 内部会把**上一条目完整解压**掉才能
                    // 读到下一个 header，所以「读进来再扣预算」等于没拦。已知大小的条目在这里直接放弃。
                    if (next.size > budget) return@runCatching null
                    if (next.name == "AndroidManifest.xml") {
                        if (next.size > MAX_MANIFEST_BYTES) return@runCatching null
                        val bytes = readAtMost(nested, MAX_MANIFEST_BYTES) ?: return@runCatching null
                        return@runCatching ApkManifestReader.parse(bytes)
                    }
                    // size < 0（data descriptor / 流式 zip）无法预估：只能照读，由 8 MB 总预算兜底
                    if (next.size > 0) budget -= next.size
                    if (budget <= 0) return@runCatching null
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        }
    }.getOrNull()

    /** 读至多 [maxBytes]；超限返回 null（不把内存在畸形条目上押掉） */
    private fun readAtMost(input: java.io.InputStream, maxBytes: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 从 `standalones/` 里挑一个跟本机最匹配的（先 ABI，再密度）。
     * 都匹配不上时退回第一个 —— 至少能装，只是可能多带一份别的 ABI 的库。
     */
    fun pickStandalone(
        standalones: List<BundleApk>,
        primaryAbi: String?,
        deviceDensity: Int,
        requireAbiMatch: Boolean = false,
    ): BundleApk? {
        if (standalones.isEmpty()) return null
        val abi = primaryAbi?.let { normalizeAbi(it) }
        fun name(apk: BundleApk): String = apk.path.substringAfterLast('/')
        if (standalones.size == 1) {
            val only = standalones.first()
            if (requireAbiMatch && abi != null && !pathMatchesAbi(name(only), abi)) return null
            return only
        }
        val byAbi = if (abi == null) emptyList() else standalones.filter { pathMatchesAbi(name(it), abi) }
        if (requireAbiMatch && abi != null && byAbi.isEmpty()) return null
        val pool = byAbi.ifEmpty { standalones }
        val wanted = densityToken(deviceDensity)
        return pool.firstOrNull { wanted != null && pathHasToken(name(it), wanted) } ?: pool.first()
    }

    /** `arm64-v8a` → `arm64_v8a`（bundletool 的 standalone 文件名用下划线） */
    private fun normalizeAbi(abi: String): String = abi.trim().lowercase().replace('-', '_')

    /**
     * ABI 必须按**完整分词**匹配，不能用子串：`x86` 是 `x86_64` 的前缀，
     * 子串匹配会让 32 位 x86 设备挑到 64 位包（反过来 `x86_64` 永远匹配不到 `x86`）。
     *
     * 规则：ABI 的规范化分词（`arm64-v8a` → [arm64, v8a]）要在文件名里连续出现，
     * 且后面紧跟文件名结尾或一个密度分词 —— `standalone-x86_64_xxhdpi.apk` 里的
     * `x86` 后面跟的是 `64`（不是密度），所以不会命中 `x86`。
     */
    internal fun pathMatchesAbi(path: String, normalizedAbi: String): Boolean {
        val want = normalizedAbi.split('_').filter { it.isNotEmpty() }
        if (want.isEmpty()) return false
        val segments = path.substringBeforeLast('.').lowercase().split('_', '-').filter { it.isNotEmpty() }
        for (i in 0..segments.size - want.size) {
            if (segments.subList(i, i + want.size) != want) continue
            val next = segments.getOrNull(i + want.size)
            if (next == null || next in DENSITY_SEGMENTS) return true
        }
        return false
    }

    private fun pathHasToken(path: String, token: String): Boolean = token in pathTokens(path)

    private fun pathTokens(path: String): List<String> =
        path.substringBeforeLast('.').lowercase().split('_', '-', '.').filter { it.isNotEmpty() }

    /** 密度只用于在多个 standalone 里挑一份；挑不到就退回第一个，所以按标准桶取整即可 */
    private fun densityToken(dpi: Int): String? = when {
        dpi <= 0 -> null
        dpi >= 560 -> "xxxhdpi"
        dpi >= 400 -> "xxhdpi"
        dpi >= 280 -> "xhdpi"
        dpi >= 200 -> "hdpi"
        dpi >= 120 -> "mdpi"
        else -> "ldpi"
    }
}
