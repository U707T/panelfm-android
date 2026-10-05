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
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.FileMetadata
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        runCatching {
            withContext(Dispatchers.IO) {
                val file = materializeApk(container, item)
                val pm = context.packageManager
                val flags = PackageManager.GET_PERMISSIONS or
                    (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
                @Suppress("DEPRECATION")
                val pkg = pm.getPackageArchiveInfo(file.absolutePath, flags)
                    ?: throw IllegalStateException("不是有效的 APK 文件（解析失败）")
                pkg.applicationInfo?.let { ai ->
                    ai.sourceDir = file.absolutePath
                    ai.publicSourceDir = file.absolutePath
                }
                ApkSummary(
                    label = pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: "（未知）",
                    icon = runCatching { pkg.applicationInfo?.let { pm.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() } }.getOrNull(),
                    packageName = pkg.packageName ?: "—",
                    versionName = pkg.versionName ?: "—",
                    versionCode = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong(),
                    minSdk = pkg.applicationInfo?.minSdkVersion ?: 0,
                    targetSdk = pkg.applicationInfo?.targetSdkVersion ?: 0,
                    compileSdk = if (Build.VERSION.SDK_INT >= 31) pkg.applicationInfo?.compileSdkVersion ?: 0 else 0,
                    permissions = pkg.requestedPermissions?.toList() ?: emptyList(),
                    size = file.length(),
                    signer = signerSummary(pkg),
                )
            }
        }.onSuccess { info = it }.onFailure { error = it.message ?: "读取 APK 失败" }
        // 校验值（与「工具 → 校验值」同一实现）
        runCatching {
            checksums = container.browser.checksumNow(item.uri, "MD5") to
                container.browser.checksumNow(item.uri, "SHA-256")
        }.onFailure { checksums = null }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            Text(
                "APK 信息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

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
                    item { InfoRow("包名", s.packageName) }
                    item { InfoRow("版本", "${s.versionName}（versionCode ${s.versionCode}）") }
                    item { InfoRow("SDK", "最低 ${s.minSdk} · 目标 ${s.targetSdk}" + if (s.compileSdk > 0) " · 编译 ${s.compileSdk}" else "") }
                    item { InfoRow("大小", Fmt.size(s.size)) }
                    item { InfoRow("签名", s.signer) }
                    checksums?.let { (md5, sha) ->
                        md5?.let { item { InfoRow("MD5", it) } }
                        sha?.let { item { InfoRow("SHA-256", it) } }
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

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 76.dp, height = 20.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

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
        "SHA-256: " + digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }.take(16) + "…"
    } ?: "未签名"
}.getOrDefault("读取失败")

/** 远程 APK 先缓存到本地（PackageManager 只接受文件路径） */
private suspend fun materializeApk(container: AppContainer, item: FileMetadata): File {
    if (item.uri.scheme == "local") {
        val path = runCatching { container.localVfs.absolutePath(item.uri) }.getOrNull()
        if (path != null && File(path).exists()) return File(path)
    }
    val dir = File(container.appDirs.cacheDir, "apk")
    runCatching { dir.mkdirs() }
    val target = File(dir, "pkg-${item.uri.toString().hashCode()}-${item.size}.apk")
    if (target.exists() && target.length() > 0) return target
    val tmp = File(dir, "${target.name}.part")
    val vfs = container.resolveSession(item.uri) ?: throw IllegalStateException("会话不可用（存储已断开）")
    vfs.openRead(item.uri).use { reader ->
        tmp.outputStream().use { out ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
    }
    if (!tmp.renameTo(target)) {
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
    }
    return target
}
