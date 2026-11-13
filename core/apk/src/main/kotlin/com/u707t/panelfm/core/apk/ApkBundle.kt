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
            // 清单读不出来（结构异常）时也能靠名字兜底。
            val base = candidates.firstOrNull {
                it.path.substringAfterLast('/') == "base-master.apk" || it.path.substringAfterLast('/') == "base.apk"
            } ?: candidates.firstOrNull { it.manifest?.isBase == true }
                ?: candidates.firstOrNull { it.manifest == null }
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
                    if (next.name == "AndroidManifest.xml") {
                        return@runCatching ApkManifestReader.parse(nested.readBytes())
                    }
                    budget -= if (next.size > 0) next.size else 0
                    if (budget <= 0) return@runCatching null
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        }
    }.getOrNull()

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
        if (standalones.size == 1) {
            val only = standalones.first()
            if (requireAbiMatch && primaryAbi != null && primaryAbi.replace('-', '_') !in only.path) return null
            return only
        }
        fun name(apk: BundleApk): String = apk.path.substringAfterLast('/')
        val byAbi = primaryAbi?.let { abi -> standalones.filter { abi.replace('-', '_') in name(it) } }
            .orEmpty()
        if (requireAbiMatch && byAbi.isEmpty()) return null
        val pool = byAbi.ifEmpty { standalones }
        val wanted = densityToken(deviceDensity)
        return pool.firstOrNull { wanted != null && wanted in name(it) } ?: pool.first()
    }

    private fun densityToken(dpi: Int): String? = when {
        dpi >= 560 -> "xxxhdpi"
        dpi >= 400 -> "xxhdpi"
        dpi >= 280 -> "xhdpi"
        dpi >= 200 -> "hdpi"
        dpi >= 140 -> "tvdpi"
        dpi >= 120 -> "mdpi"
        dpi > 0 -> "ldpi"
        else -> null
    }
}
