package com.u707t.panelfm.core.apk

import java.io.File
import java.util.zip.ZipFile

enum class ConvertStage { EXTRACT, MERGE, SIGN }

data class ConvertProgress(val stage: ConvertStage, val done: Long, val total: Long) {
    val fraction: Float
        get() = if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

data class ConvertResult(
    val output: File,
    val outputBytes: Long,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    /** true = 压缩包里本来就有完整单 APK，直接取出（签名仍是官方的） */
    val direct: Boolean,
    val mergedSplits: Int,
    val skippedResSplits: Int,
    val skippedFeatureSplits: Int,
    val skippedAbiSplits: Int,
    val manifestChanges: List<String>,
)

/**
 * 「APKS / XAPK / APKM → APK」的总入口（纯 JVM，可直接单测）。
 *
 * 两条路径：
 *  1. **直接取出**：压缩包里已有 `universal.apk`，或 `standalones/` 里有跟本机 ABI 匹配的完整包
 *     → 原样拷出来。签名是官方的，能直接覆盖安装，也不用合并；
 *  2. **合并 + 重签**：base + 选中的分包 → [ApkMerger] → [ApkSignerService]（内置测试证书）。
 *
 * 中间产物全部落在 [workRoot] 下的临时目录，结束即删。
 */
class ApksToApk(private val workRoot: File, signingKeyDir: File) {

    private val keys = ApkSigningKeyStore(signingKeyDir)

    fun inspect(file: File): ApkBundle = ApkBundleReader.inspect(file)

    fun plan(bundle: ApkBundle, options: ConvertOptions): ConvertPlan = ApkPlanner.plan(bundle, options)

    fun convert(
        plan: ConvertPlan,
        output: File,
        onProgress: (ConvertProgress) -> Unit = {},
    ): ConvertResult {
        val work = File(workRoot, "convert-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            output.parentFile?.mkdirs()
            if (output.exists()) output.delete()
            plan.direct?.let { direct ->
                extract(plan.source, direct.path, output) { done, total ->
                    onProgress(ConvertProgress(ConvertStage.EXTRACT, done, total))
                }
                val info = direct.manifest
                return ConvertResult(
                    output = output,
                    outputBytes = output.length(),
                    packageName = info?.packageName,
                    versionName = info?.versionName,
                    versionCode = info?.versionCode,
                    direct = true,
                    mergedSplits = 0,
                    skippedResSplits = 0,
                    skippedFeatureSplits = 0,
                    skippedAbiSplits = 0,
                    manifestChanges = emptyList(),
                )
            }

            val base = plan.base ?: error("找不到基础包（base.apk）")
            val baseFile = File(work, "base.apk")
            extract(plan.source, base.path, baseFile) { done, total ->
                onProgress(ConvertProgress(ConvertStage.EXTRACT, done, total))
            }
            val splitFiles = plan.merging.mapIndexed { index, apk ->
                File(work, "split-$index.apk").also { dest ->
                    extract(plan.source, apk.path, dest) { done, total ->
                        onProgress(ConvertProgress(ConvertStage.EXTRACT, done, total))
                    }
                }
            }

            val merged = File(work, "merged.apk")
            val stats = ApkMerger.merge(baseFile, splitFiles, plan.keptAbis, merged) { done, total ->
                onProgress(ConvertProgress(ConvertStage.MERGE, done, total))
            }

            onProgress(ConvertProgress(ConvertStage.SIGN, 0, 1))
            ApkSignerService.sign(merged, output, keys.loadOrCreate(), base.manifest?.minSdk ?: 26)
            onProgress(ConvertProgress(ConvertStage.SIGN, 1, 1))

            return ConvertResult(
                output = output,
                outputBytes = output.length(),
                packageName = base.manifest?.packageName,
                versionName = base.manifest?.versionName,
                versionCode = base.manifest?.versionCode,
                direct = false,
                mergedSplits = plan.merging.size,
                skippedResSplits = plan.skippedRes.size,
                skippedFeatureSplits = plan.skippedFeature.size,
                skippedAbiSplits = plan.skippedAbi.size,
                manifestChanges = stats.manifestChanges,
            )
        } catch (t: Throwable) {
            // 失败时不要留半个 APK（会出现在列表里，看着像成功）
            runCatching { if (output.isFile) output.delete() }
            throw t
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }

    private fun extract(
        bundleFile: File,
        path: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        ZipFile(bundleFile).use { zip ->
            val entry = zip.getEntry(path) ?: error("压缩包里找不到 $path")
            val total = entry.size
            var done = 0L
            zip.getInputStream(entry).use { input ->
                dest.outputStream().buffered(512 * 1024).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
    }
}
