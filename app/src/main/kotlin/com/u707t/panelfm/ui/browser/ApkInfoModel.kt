package com.u707t.panelfm.ui.browser

import android.content.Context
import android.os.Build
import android.os.Environment
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.materializeToCache
import com.u707t.panelfm.writeAtomically
import com.u707t.panelfm.core.apk.ApkBundleReader
import com.u707t.panelfm.core.apk.ApkHardening
import com.u707t.panelfm.core.apk.ApkSignatures
import com.u707t.panelfm.core.apk.SignatureSchemes
import com.u707t.panelfm.core.common.AndroidApi
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.copyStreamTo
import com.u707t.panelfm.core.common.sha256Hex
import com.u707t.panelfm.core.common.sweepStaleFiles
import com.u707t.panelfm.core.vfs.FileMetadata
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ================================================================================================
// 「APK 信息」对话框的数据与解析（对齐 MT：单击 APK / .apks 弹卡片，而不是直接进页面）。
//
// 分工：
//  - [ApkInfo] 是**纯数据**（不含 Android 类型），[ApkInfoRows] 是纯函数 → 可单测；
//  - [loadApkInfo] 才是碰 PackageManager / zip 的那一半，只在 IO 上跑；
//  - 界面在 DialogsApkInfo.kt。
//
// 为什么不复用 ApkInfoScreen（整页那个）：MT 的单击行为就是弹卡片，卡片里 11 行是「包的身份证」；
// 整页那套（权限清单 / ABI / MD5 / SHA-256）留作「功能 → APK 详细信息」。
// ================================================================================================

/** `.apks` / `.xapk` / `.apkm`：本质是装了若干 APK 的 zip，信息要从里面的 base.apk 读 */
internal fun isApkBundleName(name: String): Boolean = TypeActions.isApkBundle(name)

/** APK 信息对话框里显示的全部字段（顺序与文案见 [ApkInfoRows]） */
internal data class ApkInfo(
    val label: String,
    val versionName: String?,
    val packageName: String,
    val versionCode: Long,
    val size: Long,
    val signatures: String,
    val hardening: String,
    val targetSdk: Int,
    val minSdk: Int,
    /** 本机已安装的版本（`41.1.0 (410101)`）；null = 未安装 */
    val installedVersion: String?,
    val dataDir: String,
    val externalDataDir: String,
    val apkPath: String,
    /** 已安装包在本机的实际路径（PackageManager 的 sourceDir）；未安装为 null */
    val installedPath: String?,
    val firstInstall: Long?,
    val lastUpdate: Long?,
    val uid: Int?,
    /** 外层是 .apks/.xapk/.apkm 时的说明（信息取自 base.apk） */
    val bundleNote: String? = null,
    /** 已落到本地的可安装 APK（远端文件解析时缓存下来的那份；本地文件即它自身） */
    val localApkPath: String? = null,
)

/**
 * 对话框里的行（MT 的顺序：包名 / 版本号 / 安装包大小 / 签名状态 / 加固状态 / 目标系统 / 最低系统 /
 * 已安装 / 数据目录 1 / 数据目录 2 / APK 路径 / 首次安装 / 最后更新 / UID）。
 *
 * 未安装时**不显示**首次安装 / 最后更新 / UID / 已安装路径 —— 那几项没有值，
 * 显示成 `—` 只会让人以为读失败。
 */
internal object ApkInfoRows {

    fun of(info: ApkInfo): List<Pair<String, String>> = buildList {
        add("包名" to info.packageName)
        add("版本号" to info.versionCode.toString())
        add("安装包大小" to Fmt.sizeCompact(info.size))
        add("签名状态" to info.signatures)
        add("加固状态" to info.hardening)
        add("目标系统" to AndroidApi.label(info.targetSdk))
        add("最低系统" to AndroidApi.label(info.minSdk))
        add("已安装" to (info.installedVersion ?: "未安装"))
        add("数据目录 1" to info.dataDir)
        add("数据目录 2" to info.externalDataDir)
        add("APK 路径" to info.apkPath)
        info.installedPath?.let { add("已安装路径" to it) }
        info.firstInstall?.let { add("首次安装" to Fmt.fullTime(it)) }
        info.lastUpdate?.let { add("最后更新" to Fmt.fullTime(it)) }
        info.uid?.let { add("UID" to it.toString()) }
    }
}

/**
 * 解析 APK / 分包安装包的信息。
 *
 * 远端文件先缓存到本地（PackageManager 只接受文件路径）；`.apks` 这类分包包还要把
 * base.apk 取出来 —— 两件事都进 `cache/apk`，按「大小 + 修改时间」做键，3 天没用过就清。
 *
 * @param onStage 进度文案（对话框里显示在转圈下面）
 */
internal suspend fun loadApkInfo(
    container: AppContainer,
    context: Context,
    item: FileMetadata,
    onStage: (String) -> Unit = {},
): ApkInfo {
    val bundle = isApkBundleName(item.name)
    onStage(if (item.uri.scheme == "local") "正在解析安装包…" else "正在缓存远端安装包…")
    val outer = materializeApk(container, item, onStage)

    val apkFile: File
    val bundleNote: String?
    if (bundle) {
        onStage("正在从分包安装包里取出 base.apk…")
        apkFile = extractBundleBaseApk(container, outer, onStage)
        bundleNote = "分包安装包（${item.name.substringAfterLast('.').uppercase(Locale.ROOT)}）：" +
            "信息取自 base.apk，安装请用「转为 APK」或系统安装器"
    } else {
        apkFile = outer
        bundleNote = null
    }

    return withContext(Dispatchers.IO) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: throw IllegalStateException("不是有效的 APK（系统解析失败）")
        val appInfo = archive.applicationInfo
        // loadIcon / getApplicationLabel 按 sourceDir 去包里读资源；不设这两个字段拿到的是系统默认图标
        appInfo?.sourceDir = apkFile.absolutePath
        appInfo?.publicSourceDir = apkFile.absolutePath
        val packageName = archive.packageName
        if (packageName.isBlank()) throw IllegalStateException("包里没有包名")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        val label = appInfo?.let { ai ->
            runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: item.name

        // 已安装：QUERY_ALL_PACKAGES 已声明（Manifest），这里能查到任意包
        val installed = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
        val installedVersion = installed?.let {
            val code = if (Build.VERSION.SDK_INT >= 28) {
                it.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                it.versionCode.toLong()
            }
            "${it.versionName ?: "—"} ($code)"
        }

        val schemes = runCatching { ApkSignatures.detect(apkFile) }.getOrDefault(SignatureSchemes.NONE)
        val hardening = ApkHardening.detect(apkFile, appInfo?.className)

        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        ApkInfo(
            label = label,
            versionName = archive.versionName,
            packageName = packageName,
            versionCode = versionCode,
            size = if (item.size >= 0) item.size else outer.length(),
            signatures = schemes.label(),
            hardening = hardening?.label() ?: ApkHardening.NONE_LABEL,
            targetSdk = appInfo?.targetSdkVersion ?: 0,
            minSdk = appInfo?.minSdkVersion ?: 0,
            installedVersion = installedVersion,
            dataDir = "/data/user/0/$packageName",
            externalDataDir = "$externalRoot/Android/data/$packageName",
            apkPath = if (item.uri.scheme == "local") {
                runCatching { container.localVfs.absolutePath(item.uri) }.getOrNull() ?: item.uri.toString()
            } else {
                item.uri.toString()
            },
            installedPath = installed?.applicationInfo?.sourceDir,
            firstInstall = installed?.firstInstallTime?.takeIf { it > 0 },
            lastUpdate = installed?.lastUpdateTime?.takeIf { it > 0 },
            uid = installed?.applicationInfo?.uid,
            bundleNote = bundleNote,
            localApkPath = apkFile.absolutePath,
        )
    }
}

/**
 * 把「要解析的 APK」落到本地缓存（PackageManager 只认文件路径）。
 *
 * 具体策略（不复制本地文件、SHA-256 键、`.part` 中转、3 天清理）统一在
 * [com.u707t.panelfm.materializeToCache] —— 与 PDF 渲染共用一套。
 */
internal suspend fun materializeApk(
    container: AppContainer,
    item: FileMetadata,
    onStage: (String) -> Unit = {},
): File = materializeToCache(
    container = container,
    uri = item.uri,
    size = item.size,
    subdir = "apk",
    prefix = "pkg",
    extension = "apk",
    onStage = onStage,
)

/**
 * 从 `.apks` / `.xapk` / `.apkm` 里取出 base.apk（或 universal / standalone）落到缓存。
 *
 * 为什么要取出来：包名 / 图标 / 目标系统 / 签名方案都只有解析 APK 本体才拿得到，
 * 而 PackageManager 不接受「zip 里的一段字节」。取出来的那份按外层文件的
 * 「路径 + 大小 + 修改时间」做键，同一个包第二次点开是命中缓存。
 */
internal suspend fun extractBundleBaseApk(
    container: AppContainer,
    bundleFile: File,
    onStage: (String) -> Unit = {},
): File = withContext(Dispatchers.IO) {
    val bundle = runCatching { ApkBundleReader.inspect(bundleFile) }.getOrElse { e ->
        throw IllegalStateException(e.message ?: "不是有效的分包安装包")
    }
    val primary = bundle.base
        ?: bundle.universal
        ?: bundle.standalones.firstOrNull()
        ?: throw IllegalStateException("分包安装包里找不到 base.apk")
    val dir = File(container.appDirs.cacheDir, "apk").apply { runCatching { mkdirs() } }
    val key = sha256Hex(
        "${bundleFile.absolutePath}:${bundleFile.length()}:${bundleFile.lastModified()}:${primary.path}".toByteArray(),
        takeBytes = 8,
    )
    val target = File(dir, "base-$key.apk")
    if (target.isFile && target.length() > 0) return@withContext target
    sweepStaleFiles(dir, keep = target)

    onStage("正在解出 base.apk（${Fmt.sizeCompact(primary.size)}）…")
    writeAtomically(target) { out ->
        ZipFile(bundleFile).use { zip ->
            val entry = zip.getEntry(primary.path)
                ?: throw IllegalStateException("包内条目丢失：${primary.path}")
            zip.getInputStream(entry).use { it.copyStreamTo(out) }
        }
    }
    target
}
