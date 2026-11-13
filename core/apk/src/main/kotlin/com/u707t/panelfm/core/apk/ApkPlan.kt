package com.u707t.panelfm.core.apk

import java.io.File

/**
 * 转换选项。
 *
 * @param abis ABI 白名单（`arm64-v8a` 这种目录名）；null = 全部保留
 * @param includeFeatureSplits 是否连动态功能模块一起并（默认不并：模块清单里的组件没法并进 base 清单）
 * @param deviceAbi 本机主 ABI（挑 `standalones/` 用）
 * @param deviceDensity 本机密度（挑 `standalones/` 用，0 = 未知）
 */
data class ConvertOptions(
    val abis: Set<String>? = null,
    val includeFeatureSplits: Boolean = false,
    val deviceAbi: String? = null,
    val deviceDensity: Int = 0,
)

/**
 * 转换计划。
 *
 * @param direct 非 null = 压缩包里本来就有完整单 APK（universal / 匹配本机的 standalone），
 *               直接取出来，不合并也不重签 —— 签名还是官方的，最省事也最安全
 * @param merging 要并进 base 的分包（ABI + 可选的功能模块）
 * @param skippedRes 密度 / 语言分包：内容是 `res/` + `resources.arsc`，没有资源表合并能力，并进去也用不上
 * @param skippedFeature 功能模块分包
 * @param skippedAbi 被 ABI 白名单过滤掉的原生库分包
 */
data class ConvertPlan(
    val source: File,
    val direct: BundleApk?,
    val base: BundleApk?,
    val merging: List<BundleApk>,
    val skippedRes: List<BundleApk>,
    val skippedFeature: List<BundleApk>,
    val skippedAbi: List<BundleApk>,
    val keptAbis: Set<String>?,
) {
    val needsMerge: Boolean get() = direct == null
}

object ApkPlanner {

    /**
     * @throws IllegalStateException 没有基础包也没有可直接取出的单 APK
     */
    fun plan(bundle: ApkBundle, options: ConvertOptions): ConvertPlan {
        val standalone = ApkBundleReader.pickStandalone(
            bundle.standalones,
            options.deviceAbi,
            options.deviceDensity,
            requireAbiMatch = true,
        )
        val direct = bundle.universal ?: standalone
        if (direct != null) {
            return ConvertPlan(
                source = bundle.file,
                direct = direct,
                base = null,
                merging = emptyList(),
                skippedRes = emptyList(),
                skippedFeature = emptyList(),
                skippedAbi = emptyList(),
                keptAbis = null,
            )
        }
        val base = bundle.base ?: error("找不到基础包（base.apk）")
        val merging = ArrayList<BundleApk>()
        val skippedRes = ArrayList<BundleApk>()
        val skippedFeature = ArrayList<BundleApk>()
        val skippedAbi = ArrayList<BundleApk>()
        for (split in bundle.splits) {
            when (split.kind) {
                SplitKind.ABI -> {
                    val folder = split.abiFolder
                    if (options.abis == null || folder == null || folder in options.abis) {
                        merging += split
                    } else {
                        skippedAbi += split
                    }
                }
                SplitKind.DENSITY, SplitKind.LANGUAGE -> skippedRes += split
                SplitKind.FEATURE, SplitKind.OTHER ->
                    if (options.includeFeatureSplits) merging += split else skippedFeature += split
            }
        }
        return ConvertPlan(
            source = bundle.file,
            direct = null,
            base = base,
            merging = merging,
            skippedRes = skippedRes,
            skippedFeature = skippedFeature,
            skippedAbi = skippedAbi,
            keptAbis = options.abis,
        )
    }
}
