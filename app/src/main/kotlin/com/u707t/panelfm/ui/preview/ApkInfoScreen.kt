package com.u707t.panelfm.ui.preview

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.toHex
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.FileMetadata
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.u707t.panelfm.ui.browser.checksumNow

/**
 * APK 信息查看（**只读，非逆向**；复刻 MT 的「APK 信息」入口）：
 *  - 通过系统 PackageManager 解析 APK 归档（getPackageArchiveInfo），不自己解 dex/arsc；
 *  - 展示：图标 / 应用名 / 包名 / 版本 / SDK 要求 / 权限 / 大小 / 签名摘要 / MD5·SHA-256；
 *  - 远程 APK 先缓存到本地再解析（PackageManager 只接受文件路径）。
 */
@Composable
fun ApkInfoScreen(container: AppContainer, item: FileMetadata, onBack: () -> Unit) {
    val context = LocalContext.current
    var info by remember(item.uri) { mutableStateOf<ApkSummary?>(null) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    var checksums by remember(item.uri) { mutableStateOf<Pair<String?, String?>?>(null) }

    LaunchedEffect(item.uri) {
        var materialized: File? = null
        runCatching {
            withContext(Dispatchers.IO) {
                val file = com.u707t.panelfm.ui.browser.materializeApk(container, item)
                materialized = file
                // .apks / .xapk / .apkm：整页详情同样要解析 base.apk，
                // 否则 PackageManager 拿到一个 zip 会直接「不是有效的 APK」
                val apk = if (com.u707t.panelfm.ui.browser.isApkBundleName(item.name)) {
                    com.u707t.panelfm.ui.browser.extractBundleBaseApk(container, file)
                } else {
                    file
                }
                val pm = context.packageManager
                val flags = PackageManager.GET_PERMISSIONS or
                    (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
                @Suppress("DEPRECATION")
                val pkg = pm.getPackageArchiveInfo(apk.absolutePath, flags)
                    ?: throw IllegalStateException("不是有效的 APK 文件（解析失败）")
                pkg.applicationInfo?.let { ai ->
                    ai.sourceDir = apk.absolutePath
                    ai.publicSourceDir = apk.absolutePath
                }
                ApkSummary(
                    label = pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: "（未知）",
                    icon = runCatching { pkg.applicationInfo?.let { pm.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() } }.getOrNull(),
                    packageName = pkg.packageName.ifBlank { "—" },
                    versionName = pkg.versionName ?: "—",
                    versionCode = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong(),
                    minSdk = pkg.applicationInfo?.minSdkVersion ?: 0,
                    targetSdk = pkg.applicationInfo?.targetSdkVersion ?: 0,
                    compileSdk = if (Build.VERSION.SDK_INT >= 31) pkg.applicationInfo?.compileSdkVersion ?: 0 else 0,
                    permissions = pkg.requestedPermissions?.toList() ?: emptyList(),
                    size = file.length(),
                    signer = signerSummary(pkg),
                    abis = runCatching {
                        java.util.zip.ZipFile(apk).use { z ->
                            z.entries().asSequence().mapNotNull { e ->
                                val parts = e.name.split('/')
                                if (parts.size >= 3 && parts[0] == "lib") parts[1] else null
                            }.distinct().sorted().toList()
                        }
                    }.getOrDefault(emptyList()),
                    installLocation = pkg.installLocation,
                    debuggable = (pkg.applicationInfo?.flags ?: 0) and
                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
                )
            }
        }.onSuccess { info = it }.onFailure { error = it.message ?: "读取 APK 失败" }
        // 校验值：直接对**已落地的文件**算一次（一次读取同时求出 MD5 / SHA-256）。
        // 旧的 checksumNow(item.uri) 会从源头再读两遍 —— 远程 APK 等于下载 3 次
        // （2026-10-08 重审 §2）；算法与输出格式与「工具 → 校验值」保持一致。
        val local = materialized
        runCatching {
            checksums = if (local != null) {
                fileDigests(local)
            } else {
                container.browser.checksumNow(item.uri, "MD5") to
                    container.browser.checksumNow(item.uri, "SHA-256")
            }
        }.onFailure { checksums = null }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = item.name,
            subtitle = if (com.u707t.panelfm.ui.browser.isApkBundleName(item.name)) {
                "APK 详细信息（取自 base.apk）"
            } else {
                "APK 详细信息"
            },
            onBack = onBack,
        )

        when {
            error != null -> ErrorState("APK 信息读取失败：$error")
            info == null -> LoadingState("正在解析 APK…")
            else -> {
                val s = info!!
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item { Header(s) }
                    item { ApkDetailRow("包名", s.packageName) }
                    item { ApkDetailRow("版本", "${s.versionName}（versionCode ${s.versionCode}）") }
                    item { ApkDetailRow("SDK", "最低 ${s.minSdk} · 目标 ${s.targetSdk}" + if (s.compileSdk > 0) " · 编译 ${s.compileSdk}" else "") }
                    item { ApkDetailRow("大小", Fmt.size(s.size)) }
                    item {
                        ApkDetailRow(
                            "ABI",
                            if (s.abis.isEmpty()) "无 native 库" else s.abis.joinToString(" / "),
                        )
                    }
                    item {
                        ApkDetailRow(
                            "安装位置",
                            when (s.installLocation) {
                                1 -> "内部存储"
                                2 -> "外部存储"
                                else -> "自动"
                            } + if (s.debuggable) " · 可调试" else "",
                        )
                    }
                    item { ApkDetailRow("签名", s.signer) }
                    checksums?.let { (md5, sha) ->
                        md5?.let { item { ApkDetailRow("MD5", it) } }
                        sha?.let { item { ApkDetailRow("SHA-256", it) } }
                    }
                    item {
                        Text(
                            "权限（${s.permissions.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    if (s.permissions.isEmpty()) {
                        item {
                            Text(
                                "该 APK 未申请任何权限",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            )
                        }
                    } else {
                        items(s.permissions.size) { index ->
                            val perm = s.permissions[index]
                            Text(
                                "· ${perm.substringAfterLast('.')}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
                            )
                        }
                    }
                    item { Box(Modifier.padding(bottom = 20.dp)) }
                }
            }
        }
    }
}

private data class ApkSummary(
    val label: String,
    val icon: androidx.compose.ui.graphics.ImageBitmap?,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int,
    val permissions: List<String>,
    val size: Long,
    val signer: String,
    /** APK 内含的 ABI（`lib/<abi>/` 目录）；空 = 纯 Java / 未打包 native */
    val abis: List<String>,
    /** 0 自动 / 1 内部存储 / 2 外部存储（PackageInfo.installLocation） */
    val installLocation: Int,
    val debuggable: Boolean,
)

@Composable
private fun Header(s: ApkSummary) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.icon != null) {
            Image(
                bitmap = s.icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text(s.label.take(1), style = MaterialTheme.typography.titleLarge) }
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(s.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                s.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HSeparator()
}

/**
 * 详情页的一行：只是给共享的 [com.u707t.panelfm.ui.browser.InfoRow] 绑上「整页档」参数
 * （等宽值、3 行上限）—— 实现只有一份，见 DialogsInput.kt。
 */
@Composable
private fun ApkDetailRow(label: String, value: String) = com.u707t.panelfm.ui.browser.InfoRow(
    label = label,
    value = value,
    labelWidth = 76.dp,
    labelStyle = MaterialTheme.typography.labelMedium,
    valueStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    maxLines = 3,
    verticalPadding = 6.dp,
)

/** 签名证书摘要（SHA-256 前 16 位 + 主题），非逆向：直接读 PackageManager 的签名信息 */
private fun signerSummary(pkg: PackageInfo): String = runCatching {
    val infos = if (Build.VERSION.SDK_INT >= 28) {
        pkg.signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION") pkg.signatures
    } ?: return "未签名"
    infos.firstOrNull()?.let { sig ->
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(sig.toByteArray())
        "SHA-256: " + digest.toHex().take(16) + "…"
    } ?: "未签名"
}.getOrDefault("读取失败")

/**
 * 对本地文件一次性读完，同时计算 MD5 与 SHA-256（小写十六进制）。
 *
 * 与「工具 → 校验值」（BrowserController.checksumNow）的算法与输出格式一致；
 * 区别只是数据源是**已落地的本地文件**，避免远程包被重复下载（2026-10-08 重审 §2）。
 */
private suspend fun fileDigests(file: File): Pair<String, String> = withContext(Dispatchers.IO) {
    val md5 = java.security.MessageDigest.getInstance("MD5")
    val sha = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md5.update(buf, 0, n)
            sha.update(buf, 0, n)
        }
    }
    md5.digest().toHex() to sha.digest().toHex()
}
