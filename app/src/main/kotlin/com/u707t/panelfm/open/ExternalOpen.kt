package com.u707t.panelfm.open

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 应用内「外部应用 / 打开方式」要排除 PanelFM 自身。
 *
 * 新增 `ACTION_VIEW(content://)` filter 后，系统选择器会把 PanelFM 也列出来；
 * 用户从 PanelFM 里再选 PanelFM 会变成「复制一份缓存再预览」的自环。
 * `EXTRA_EXCLUDE_COMPONENTS` 由 Resolver/Chooser 读取（API 24+，本项目 minSdk 26）。
 */
internal fun selfExclusion(context: Context): Array<ComponentName> =
    arrayOf(ComponentName(context, com.u707t.panelfm.MainActivity::class.java))

/**
 * 本应用 FileProvider 的 `content://` URI —— **authority 只在这里写一次**。
 *
 * 以前 6 处（分享 / 打开方式 / 指定应用打开 / 安装 / 预览外部应用 / 播放器外部打开）
 * 各自拼 `"${context.packageName}.fileprovider"` 并各自包一层 runCatching；
 * authority 改名（Manifest 与代码必须同步）时要改 6 个地方，漏一个就是「文件不存在」。
 *
 * @return null = 生成失败（路径不在 file_paths.xml 覆盖范围内 / FileProvider 未注册）
 */
internal fun fileProviderUri(context: Context, file: File): Uri? = runCatching {
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()
