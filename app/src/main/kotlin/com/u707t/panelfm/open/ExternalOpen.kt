package com.u707t.panelfm.open

import android.content.ComponentName
import android.content.Context

/**
 * 应用内「外部应用 / 打开方式」要排除 PanelFM 自身。
 *
 * 新增 `ACTION_VIEW(content://)` filter 后，系统选择器会把 PanelFM 也列出来；
 * 用户从 PanelFM 里再选 PanelFM 会变成「复制一份缓存再预览」的自环。
 * `EXTRA_EXCLUDE_COMPONENTS` 由 Resolver/Chooser 读取（API 24+，本项目 minSdk 26）。
 */
internal fun selfExclusion(context: Context): Array<ComponentName> =
    arrayOf(ComponentName(context, com.u707t.panelfm.MainActivity::class.java))
