package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.common.RenderFormats
import com.u707t.panelfm.core.common.SqliteFormats

/**
 * 按文件类型决定的**二级菜单项**（MT 的长按菜单会随选中项类型变化）。
 *
 * MT 语义（对照 `mt_strings_zh.txt`）：
 *  - 压缩包：`解压到当前目录`(0x7f110251) / `解压到单独的文件夹`(0x7f110252) /
 *    `解压到文件夹…`(0x7f110250)、`浏览压缩包`(0x7f1100ed)
 *  - APK：`安装`(0x7f110044)、`Apk信息`(0x7f1100e8)、`提取安装包`(0x7f110248)
 *  - 图片：`查看图片`(0x7f1100f2) · 音频：`播放音乐`(0x7f1100eb) ·
 *    字体：`查看字体`(0x7f1100f0) · 文本/代码：`编辑文本`(0x7f1100f9)
 *  - 通用：`打开方式…`(0x7f11040d)
 *
 * 这里只产出「该显示哪一项」，不依赖 Compose，便于单测。
 */
object TypeActions {

    const val ACTION_EXTRACT_HERE = "extract_here"
    const val ACTION_EXTRACT_OWN_FOLDER = "extract_own_folder"
    const val ACTION_EXTRACT_PICK = "extract_pick_folder"
    const val ACTION_BROWSE_ARCHIVE = "browse_archive"
    const val ACTION_INSTALL = "install_apk"
    const val ACTION_APK_INFO = "apk_info"
    const val ACTION_EXTRACT_APK_ICON = "extract_apk_icon"
    const val ACTION_OPEN_INTERNAL = "open_internal"
    /** 直接用内置编辑器打开（文本 / 代码 / 未识别类型都可手动选） */
    const val ACTION_EDIT_TEXT = "edit_text"

    /** Markdown / CSV 的渲染预览（marked / SheetJS） */
    const val ACTION_RENDER = "render_preview"

    /** SQLite 数据库只读浏览 */
    const val ACTION_SQLITE_BROWSE = "sqlite_browse"

    /** .epub 电子书阅读 */
    const val ACTION_READ_EPUB = "read_epub"
    const val ACTION_OPEN_WITH = "open_with"

    fun kindOf(extension: String): MimeTypes.Kind = MimeTypes.kindOf(extension)

    fun isApk(kind: MimeTypes.Kind): Boolean = kind == MimeTypes.Kind.APK

    /** 可直接用内置查看器打开（图片 / 音频 / 视频 / 字体 / PDF / 文本 / 代码 / Office 文档） */
    fun hasBuiltinViewer(kind: MimeTypes.Kind): Boolean = kind in setOf(
        MimeTypes.Kind.IMAGE, MimeTypes.Kind.AUDIO, MimeTypes.Kind.VIDEO,
        MimeTypes.Kind.FONT, MimeTypes.Kind.PDF, MimeTypes.Kind.TEXT, MimeTypes.Kind.CODE,
        MimeTypes.Kind.DOCUMENT,
    )

    /** MT 的内置查看标签（菜单文案；null = 该类型没有内置查看器） */
    fun viewerLabel(kind: MimeTypes.Kind): String? = when (kind) {
        MimeTypes.Kind.IMAGE -> "查看图片"
        MimeTypes.Kind.AUDIO -> "播放音乐"
        MimeTypes.Kind.VIDEO -> "播放视频"
        MimeTypes.Kind.FONT -> "查看字体"
        MimeTypes.Kind.PDF -> "查看 PDF"
        MimeTypes.Kind.DOCUMENT -> "文档预览"
        MimeTypes.Kind.TEXT, MimeTypes.Kind.CODE -> "查看文本"
        else -> null
    }

    /**
     * 长按菜单里「按类型追加」的二级菜单项 id。
     *
     * @param isDirectory    目录不参与类型菜单
     * @param inArchive      压缩包内部：解压 / 安装不可用（MT 提示「请先解压文件到本地目录后再进行该操作」0x7f11024f）
     * @param apkInstallable APK 是否可直接安装（网络 / 压缩包内需先复制到本地）
     */
    fun typedActionIds(
        extension: String,
        isDirectory: Boolean,
        inArchive: Boolean,
        apkInstallable: Boolean,
    ): List<String> {
        if (isDirectory) return emptyList()
        val kind = kindOf(extension)
        return buildList {
            when (kind) {
                MimeTypes.Kind.ARCHIVE -> {
                    if (!inArchive) {
                        add(ACTION_EXTRACT_HERE)
                        add(ACTION_EXTRACT_OWN_FOLDER)
                        add(ACTION_EXTRACT_PICK)
                    }
                    // .epub：既可以当压缩包浏览 / 解压，也可以直接阅读
                    if (extension.lowercase() == "epub") add(ACTION_READ_EPUB)
                    add(ACTION_BROWSE_ARCHIVE)
                }
                MimeTypes.Kind.APK -> {
                    if (!inArchive && apkInstallable) add(ACTION_INSTALL)
                    add(ACTION_APK_INFO)
                    add(ACTION_EXTRACT_APK_ICON)
                }
                // 文本 / 代码：查看 + 编辑
                MimeTypes.Kind.TEXT, MimeTypes.Kind.CODE -> {
                    add(ACTION_OPEN_INTERNAL)
                    add(ACTION_EDIT_TEXT)
                    // Markdown / CSV：可以渲染成页面（marked / SheetJS）
                    if (RenderFormats.isRenderable(extension)) add(ACTION_RENDER)
                }
                // 未识别：按「通用」处理 —— 文本查看 / 编辑文本 /（数据库则给）SQLite 浏览 / 打开方式
                MimeTypes.Kind.OTHER -> {
                    add(ACTION_OPEN_INTERNAL)
                    add(ACTION_EDIT_TEXT)
                    if (SqliteFormats.isSqlite(extension)) add(ACTION_SQLITE_BROWSE)
                }
                else -> if (hasBuiltinViewer(kind)) add(ACTION_OPEN_INTERNAL)
            }
            add(ACTION_OPEN_WITH)
        }
    }

    /** 二级菜单文案（按 id + 类型；未知 id 落到「打开方式…」）。 */
    fun labelOf(id: String, kind: MimeTypes.Kind): String = when (id) {
        ACTION_EXTRACT_HERE -> "解压到当前目录"
        ACTION_EXTRACT_OWN_FOLDER -> "解压到单独的文件夹"
        ACTION_EXTRACT_PICK -> "解压到文件夹…"
        ACTION_BROWSE_ARCHIVE -> "浏览压缩包"
        ACTION_INSTALL -> "安装"
        ACTION_APK_INFO -> "APK 信息"
        ACTION_EXTRACT_APK_ICON -> "提取图标"
        ACTION_OPEN_INTERNAL -> viewerLabel(kind) ?: "查看文本"
        ACTION_EDIT_TEXT -> "编辑文本"
        ACTION_RENDER -> "渲染预览"
        ACTION_SQLITE_BROWSE -> "SQLite 浏览"
        ACTION_READ_EPUB -> "阅读电子书"
        else -> "打开方式…"
    }
}
