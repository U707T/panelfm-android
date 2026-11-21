package com.u707t.panelfm.ui.tools

import com.u707t.panelfm.core.common.Fmt

/**
 * 「已安装应用」页的模型与纯逻辑（分页 / 搜索 / 副标题 / 导出文件名）。
 *
 * 单独放一个文件是为了能单测：`PackageManager` 那一半在 [loadInstalledApps] 里，
 * 这一半只跟字符串和数字打交道。
 */

/** 分页（对齐 MT：用户应用 / 系统应用） */
internal enum class AppTab(val label: String) {
    USER("用户应用"),
    SYSTEM("系统应用"),
}

/** 一行应用的全部展示数据（在 IO 线程算好，组合期不再碰 PackageManager） */
internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    /** base + 所有 split 的字节数（MT 的「安装包大小」口径） */
    val bytes: Long,
    /** `ApplicationInfo.FLAG_SYSTEM`：预装 / 系统应用 */
    val isSystem: Boolean,
    /** 已安装包的 base APK 路径（`ApplicationInfo.sourceDir`） */
    val sourceDir: String,
    /** 分包数（base + splits）；1 = 单 APK */
    val splitCount: Int = 1,
    /** 壳检测用（`ApplicationInfo.className`） */
    val className: String? = null,
) {
    val tab: AppTab get() = if (isSystem) AppTab.SYSTEM else AppTab.USER
}

internal object AppList {

    /**
     * 列表副标题：`包名 · v版本 · 大小`（保持列表紧凑，三样都在一行）。
     *
     * 与 MT 的三行卡片不同 —— 列表保持紧凑是我们的选择，完整信息在点开的卡片里。
     */
    fun subtitleOf(app: InstalledApp): String = buildString {
        append(app.packageName)
        append(" · ")
        append(if (app.versionName.isBlank()) "—" else "v${app.versionName}")
        append(" · ")
        append(Fmt.sizeCompact(app.bytes))
    }

    /** 当前分页 + 搜索词下的列表（搜索匹配应用名或包名，忽略大小写） */
    fun filter(apps: List<InstalledApp>, tab: AppTab, query: String): List<InstalledApp> {
        val q = query.trim()
        return apps.filter { app ->
            app.tab == tab && (
                q.isEmpty() ||
                    app.label.contains(q, ignoreCase = true) ||
                    app.packageName.contains(q, ignoreCase = true)
                )
        }
    }

    fun count(apps: List<InstalledApp>, tab: AppTab): Int = apps.count { it.tab == tab }

    /**
     * 提取安装包的文件名（MT：`应用名_版本号.apk`；分包应用是 `.apks`）。
     *
     * 应用名里的 `/`、`\` 会被文件系统当路径分隔符，统一换成 `_`；
     * 版本号为空时只留应用名（避免出现 `xxx_.apk`）。
     */
    fun exportFileName(label: String, versionName: String, isBundle: Boolean): String {
        val safe = label.replace('/', '_').replace('\\', '_').trim().ifBlank { "app" }
        val suffix = if (isBundle) ".apks" else ".apk"
        return if (versionName.isBlank()) "$safe$suffix" else "${safe}_$versionName$suffix"
    }
}
