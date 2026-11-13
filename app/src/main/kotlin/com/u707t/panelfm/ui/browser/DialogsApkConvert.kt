package com.u707t.panelfm.ui.browser

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.apk.ApkBundle
import com.u707t.panelfm.core.apk.ConvertOptions
import com.u707t.panelfm.core.apk.ConvertPlan
import com.u707t.panelfm.core.apk.ConvertStage
import com.u707t.panelfm.core.apk.SplitKind
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.vfs.FileMetadata
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ================================================================================================
// 「转为 APK」：.apks / .xapk / .apkm → 单个可安装 APK（对齐 MT）。
//
// 合并 / 清单补丁 / 重签全在 core:apk（纯 JVM，可单测）；这里只负责：
//  ① 解析压缩包并把「会合并什么、会跳过什么」摊开给用户看；
//  ② ABI 二选一（仅本机 / 全部）；
//  ③ 跑转换 + 进度 + 把产物按 VFS 写入目标目录（本地 / 网络目录都走同一条路）。
// ================================================================================================

private sealed interface ConvertPhase {
    data object Loading : ConvertPhase
    data class Ready(val bundle: ApkBundle) : ConvertPhase
    data class Failed(val message: String) : ConvertPhase
}

@Composable
internal fun ApkConvertDialog(
    container: AppContainer,
    controller: BrowserController,
    item: FileMetadata,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceAbi = remember { Build.SUPPORTED_ABIS.firstOrNull() }
    val densityDpi = remember { context.resources.displayMetrics.densityDpi }
    val localFile = remember(item) {
        runCatching {
            if (item.uri.scheme == "local") File(container.localVfs.absolutePath(item.uri)) else null
        }.getOrNull()
    }

    var phase by remember(item) { mutableStateOf<ConvertPhase>(ConvertPhase.Loading) }
    var allAbis by remember(item) { mutableStateOf(false) }
    var running by remember(item) { mutableStateOf(false) }
    var job by remember(item) { mutableStateOf<Job?>(null) }
    var stageText by remember(item) { mutableStateOf("") }
    var fraction by remember(item) { mutableStateOf(0f) }
    var errorText by remember(item) { mutableStateOf<String?>(null) }

    LaunchedEffect(item) {
        if (localFile == null || !localFile.isFile) {
            phase = ConvertPhase.Failed("只有本地文件能转换：网络 / 压缩包内的安装包请先复制到本地")
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { container.apkConvert.inspect(localFile) }
        }
        phase = loaded.fold(
            onSuccess = { ConvertPhase.Ready(it) },
            onFailure = { ConvertPhase.Failed(it.message ?: it::class.java.simpleName) },
        )
    }

    val bundle = (phase as? ConvertPhase.Ready)?.bundle
    val abiFolders = remember(bundle) {
        bundle?.splits?.filter { it.kind == SplitKind.ABI }?.mapNotNull { it.abiFolder }?.distinct().orEmpty()
    }
    val deviceAbiUsable = deviceAbi != null && abiFolders.contains(deviceAbi)
    val planAll = remember(bundle, deviceAbi, densityDpi) {
        bundle?.let {
            runCatching {
                container.apkConvert.plan(it, ConvertOptions(null, false, deviceAbi, densityDpi))
            }.getOrNull()
        }
    }
    val planDevice = remember(bundle, deviceAbi, densityDpi) {
        if (!deviceAbiUsable || deviceAbi == null || bundle == null) {
            planAll
        } else {
            runCatching {
                container.apkConvert.plan(bundle, ConvertOptions(setOf(deviceAbi), false, deviceAbi, densityDpi))
            }.getOrNull()
        }
    }
    val selectedPlan: ConvertPlan? = if (allAbis || !deviceAbiUsable) planAll else planDevice

    fun start(plan: ConvertPlan) {
        if (running) return
        running = true
        errorText = null
        val merging = plan.needsMerge
        stageText = if (merging) "正在解压分包…" else "正在取出完整 APK…"
        fraction = 0f
        job = scope.launch {
            val self = coroutineContext[Job]
            val tmp = File(container.appDirs.tmpDir, "apk-convert-${System.currentTimeMillis()}.apk")
            try {
                var lastPercent = -1
                val result = withContext(Dispatchers.IO) {
                    container.apkConvert.convert(plan, tmp) { progress ->
                        if (self?.isActive == false) throw CancellationException("已取消")
                        val overall = overallOf(progress.stage, progress.fraction, merging)
                        val percent = (overall * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            fraction = overall
                            stageText = when (progress.stage) {
                                ConvertStage.EXTRACT -> if (merging) "正在解压分包…" else "正在取出完整 APK…"
                                ConvertStage.MERGE -> "正在合并（原生库 / assets / dex）…"
                                ConvertStage.SIGN -> "正在签名（内置测试证书）…"
                            }
                        }
                    }
                }
                stageText = "正在写入目标目录…"
                val parent = item.uri.parent ?: item.uri
                val target = container.uniqueChild(parent, outputNameOf(item.name))
                val vfs = container.locator.find(target) ?: error("目标目录不可写")
                withContext(Dispatchers.IO) {
                    val writer = vfs.openWrite(target, result.outputBytes, 0L)
                    try {
                        tmp.inputStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                writer.write(buffer, 0, n)
                            }
                        }
                        writer.commit()
                    } catch (t: Throwable) {
                        runCatching { writer.abort() }
                        throw t
                    }
                }
                controller.showStatus("已生成 ${target.name}（${Fmt.size(result.outputBytes)}）")
                container.browser.refreshAll()
                onDismiss()
            } catch (c: CancellationException) {
                controller.showStatus("已取消转换")
                onDismiss()
            } catch (t: Throwable) {
                errorText = "转换失败：${t.message ?: t::class.java.simpleName}"
            } finally {
                running = false
                job = null
                runCatching { if (tmp.exists()) tmp.delete() }
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (running) {
                job?.cancel()
            } else {
                onDismiss()
            }
        },
        title = { Text("转为 APK") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (item.size >= 0) Fmt.size(item.size) else "大小未知",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(10.dp))

                when (val current = phase) {
                    ConvertPhase.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "正在解析安装包…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    is ConvertPhase.Failed -> Text(
                        current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is ConvertPhase.Ready -> {
                        val plan = selectedPlan
                        val info = current.bundle.base?.manifest ?: current.bundle.universal?.manifest
                        if (info?.packageName != null) {
                            Text(
                                "应用：${info.packageName} ${info.versionName.orEmpty()}" +
                                    (info.versionCode?.let { " ($it)" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (plan == null) {
                            Text(
                                "这个包里没有可用的基础包（base.apk），无法转换。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (plan.direct != null) {
                            val direct = plan.direct
                            Text(
                                "包内已有完整单 APK（${direct?.displayName}）：直接取出即可，" +
                                    "保留官方签名、可直接安装。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            PlanSummary(plan, abiFolders, deviceAbi, deviceAbiUsable, allAbis) { allAbis = it }
                        }
                        if (errorText != null) {
                            Text(
                                errorText.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }

                if (running) {
                    Spacer(Modifier.size(12.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$stageText ${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedPlan?.let { start(it) } },
                enabled = !running && selectedPlan != null,
            ) {
                Text(if (running) "转换中…" else "开始转换")
            }
        },
        dismissButton = {
            TextButton(onClick = { if (running) job?.cancel() else onDismiss() }) {
                Text(if (running) "取消转换" else "取消")
            }
        },
    )
}

@Composable
private fun PlanSummary(
    plan: ConvertPlan,
    abiFolders: List<String>,
    deviceAbi: String?,
    deviceAbiUsable: Boolean,
    allAbis: Boolean,
    onAllAbisChange: (Boolean) -> Unit,
) {
    val base = plan.base
    if (base != null) {
        Text(
            "合并：${base.displayName}" +
                if (plan.merging.isEmpty()) "（无分包）"
                else " + ${plan.merging.size} 个分包（${plan.merging.joinToString("、") { it.displayName }}）",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (plan.skippedRes.isNotEmpty()) {
        Text(
            "跳过 ${plan.skippedRes.size} 个资源分包" + nameHint(plan.skippedRes.map { it.displayName }) +
                "：高密度图片与其它语言会回退到基础资源",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (plan.skippedFeature.isNotEmpty()) {
        Text(
            "跳过 ${plan.skippedFeature.size} 个功能模块" +
                nameHint(plan.skippedFeature.map { it.displayName }) +
                "：模块清单里的组件没法并进 base 清单",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (plan.skippedAbi.isNotEmpty()) {
        Text(
            "跳过 ${plan.skippedAbi.size} 个其它 ABI 分包" +
                nameHint(plan.skippedAbi.map { it.displayName }),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (abiFolders.size > 1 && deviceAbi != null && deviceAbiUsable) {
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !allAbis, onClick = { onAllAbisChange(false) })
            Text("仅本机 ABI（$deviceAbi，体积小）", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = allAbis, onClick = { onAllAbisChange(true) })
            Text("全部 ABI（${abiFolders.joinToString(" + ")}）", style = MaterialTheme.typography.bodySmall)
        }
    }

    Text(
        "签名：使用内置测试证书（与官方签名不同，覆盖安装前要先卸载原应用）",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** 列表过长时只点前三个名字（真实 .apks 可能有 100+ 个语言分包） */
private fun nameHint(names: List<String>): String {
    if (names.isEmpty()) return ""
    val head = names.take(3).joinToString("、")
    return if (names.size > 3) "（$head 等）" else "（$head）"
}

/** 文件名：`demo.apks` → `demo.apk`（同名由 [AppContainer.uniqueChild] 加序号） */
private fun outputNameOf(sourceName: String): String {
    val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "converted" }
    return "$base.apk"
}

/**
 * 三个阶段折算成一个总进度（解压 35% / 合并 50% / 签名 15%）。
 * 「直接取出」只有解压一步，占满 100%。
 */
private fun overallOf(stage: ConvertStage, fraction: Float, merging: Boolean): Float {
    if (!merging) return fraction.coerceIn(0f, 1f)
    return when (stage) {
        ConvertStage.EXTRACT -> fraction * 0.35f
        ConvertStage.MERGE -> 0.35f + fraction * 0.5f
        ConvertStage.SIGN -> 0.85f + fraction * 0.15f
    }.coerceIn(0f, 1f)
}
