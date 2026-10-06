package com.u707t.panelfm.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.u707t.panelfm.core.model.SortBy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 动态取色默认关闭（MT 观感是固定中性色板，设置里可开） */
    val dynamicColor: Boolean = false,
    val showHidden: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val sortAscending: Boolean = true,
    val dirsFirst: Boolean = true,
    val maxConcurrentTasks: Int = 2,
    val thumbnailsOnMobile: Boolean = false,
    val useSingleColumn: Boolean = false,
    /**
     * 浏览模式三档：AUTO / SINGLE / DUAL（设置页可改，重启保留）。
     *
     * 默认 **DUAL** 而不是 AUTO：AUTO 在手机上（屏宽 < 600dp）展开成单列，
     * 于是「每次打开都是单列」——本应用的定位是「打开即双列」（与 MT 一致）。
     * 想按屏宽自适应的用户仍可显式选「自动切换」。
     */
    val browseMode: String = "DUAL",
    val userAgent: String = "PanelFM/0.1 (Android)",
    val trustSelfSigned: Boolean = false,
    /** 「设为首页」的路径（URI 字符串），空 = 内部存储根 */
    val homePath: String? = null,
    /** 时间显示到秒 */
    val showSeconds: Boolean = false,
    /** 记忆上次的双列路径 */
    val rememberLastPath: Boolean = true,
    /** 左右分隔比例 */
    val splitRatio: Float = 0.5f,
    /** 底栏上滑调出书签 */
    val bookmarkSwipe: Boolean = true,
    /** 底部工具栏额外下边距（全面屏手势下的舒适区，0=自动） */
    val bottomBarPaddingDp: Int = 8,
    val skipThumbsWhileScrolling: Boolean = true,
    /** 按文件夹记忆的排序（MT「仅应用于此文件夹」）：key = 去 query 的 uri 字符串，值 = "by|asc|dirs" */
    val folderSorts: Map<String, String> = emptyMap(),
    /** 搜索历史（最近在前，最多 10 条） */
    val searchHistory: List<String> = emptyList(),
    /** 字体大小档位：0 紧凑（0.88）/ 1 适中（0.94，默认）/ 2 标准（1.0） */
    val fontScaleLevel: Int = 1,
    /** MT「保留文件时间」：复制/解压/下载完成后把源 mtime 写回目标 */
    val preserveModifiedTime: Boolean = true,
    /** MT「启动路径 - 左/右窗口」：true = 首页，false = 上次路径 */
    val startAtHome: Boolean = false,
    /**
     * MT「文件列表显示」三档（文案 `0x7f110200/201/202`）：
     *   0 不显示权限（只有时间）/ 1 非存储目录显示「权限+大小」/ 2 全部目录显示「时间+大小」
     *
     * **默认 2**：MT 截图实测列表副标题是时间（`26-10-04 13:16`）而不是权限位。
     */
    val listDisplayMode: Int = 2,
    /** MT「超过 X 大小的图片文件不加载缩略图」（字节，0 = 不限制） */
    val thumbnailMaxBytes: Long = 3L * 1024 * 1024,
    /** MT「缩略图未在 N 秒内加载完成将会取消加载」（0 = 不超时） */
    val thumbnailTimeoutSec: Int = 5,
    /** MT「退出前双次确认」 */
    val confirmExit: Boolean = false,
    /** MT「保存文件时自动将原文件重命名为 .bak 备份文件」 */
    val backupOnSave: Boolean = false,
    /** MT「对话框图标」：0 深色背景（自适应）/ 1 浅色背景（自适应）/ 2 无背景 */
    val dialogIconMode: Int = 0,
    /**
     * MT 的输入框历史（`app:recordKey`）：键名照抄 MT（`filter_record` / `rename_multi_pattern` /
     * `editor_find` …），值 = 最近使用在前、去重、最多 12 条。
     */
    val inputHistory: Map<String, List<String>> = emptyMap(),
    /**
     * MT 0x7f110630「开启后点击列表中任意两个项，将会自动选择它们中间所有的项。」
     * 默认关（MT 同默认）；开启后多选态里点第二项 = 区间选择。
     */
    val tapRangeSelect: Boolean = false,
)

private val Context.panelDataStore: DataStore<Preferences> by preferencesDataStore(name = "panel_prefs")

class PrefsStore(private val context: Context) {

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val showHidden = booleanPreferencesKey("show_hidden")
        val sortBy = stringPreferencesKey("sort_by")
        val sortAsc = booleanPreferencesKey("sort_asc")
        val dirsFirst = booleanPreferencesKey("dirs_first")
        val maxConcurrent = intPreferencesKey("max_concurrent")
        val thumbsMobile = booleanPreferencesKey("thumbs_mobile")
        val singleColumn = booleanPreferencesKey("single_column")
        val browseMode = stringPreferencesKey("browse_mode")
        val userAgent = stringPreferencesKey("user_agent")
        val trustSelfSigned = booleanPreferencesKey("trust_self_signed")
        val homePath = stringPreferencesKey("home_path")
        val showSeconds = booleanPreferencesKey("show_seconds")
        val rememberLast = booleanPreferencesKey("remember_last_path")
        val splitRatio = androidx.datastore.preferences.core.floatPreferencesKey("split_ratio")
        val bookmarkSwipe = booleanPreferencesKey("bookmark_swipe")
        val bottomPad = intPreferencesKey("bottom_bar_padding")
        val lastLeft = stringPreferencesKey("last_left")
        val lastRight = stringPreferencesKey("last_right")
        val folderSorts = androidx.datastore.preferences.core.stringSetPreferencesKey("folder_sorts")
        val searchHistory = stringPreferencesKey("search_history")
        val fontScaleLevel = intPreferencesKey("font_scale_level")
        val skipThumbs = booleanPreferencesKey("skip_thumbs_scrolling")
        // MT 对齐批次（v0.14.0）：保留文件时间 / 启动路径 / 列表显示 / 缩略图策略 / 退出确认 / .bak / 对话框图标
        val preserveMtime = booleanPreferencesKey("preserve_mtime")
        val startAtHome = booleanPreferencesKey("start_at_home")
        val listDisplayMode = intPreferencesKey("list_display_mode")
        val thumbMaxBytes = androidx.datastore.preferences.core.longPreferencesKey("thumb_max_bytes")
        val thumbTimeoutSec = intPreferencesKey("thumb_timeout_sec")
        val confirmExit = booleanPreferencesKey("confirm_exit")
        val backupOnSave = booleanPreferencesKey("backup_on_save")
        val dialogIconMode = intPreferencesKey("dialog_icon_mode")
        val tapRangeSelect = booleanPreferencesKey("tap_range_select")
        // MT 对齐（v1.0）：输入框历史（recordKey → 历史值）
        val inputHistory = androidx.datastore.preferences.core.stringSetPreferencesKey("input_history")
        // 「上次打开的文件」：格式 "<mode>|<uri>|<epochMillis>"；用于意外退出/直接退出后自动回到那里
        val lastOpenedPreview = stringPreferencesKey("last_opened_preview")
    }

    /** MT 的 recordKey 常量（与 MT 的 app:recordKey 同名，便于日后对表） */
    object RecordKeys {
        const val FILTER = "filter_record"
        const val RENAME_PATTERN = "rename_multi_pattern"
        const val RENAME_SEARCH = "rename_multi_search"
        const val RENAME_REPLACE = "rename_multi_replace"
        const val EDITOR_FIND = "editor_find"
        const val EDITOR_REPLACE = "editor_replace"
    }

    val settings: Flow<AppSettings> = context.panelDataStore.data.map { p ->
        AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.theme] ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = p[Keys.dynamicColor] ?: false,
            showHidden = p[Keys.showHidden] ?: false,
            sortBy = runCatching { SortBy.valueOf(p[Keys.sortBy] ?: SortBy.NAME.name) }.getOrDefault(SortBy.NAME),
            sortAscending = p[Keys.sortAsc] ?: true,
            dirsFirst = p[Keys.dirsFirst] ?: true,
            maxConcurrentTasks = p[Keys.maxConcurrent] ?: 2,
            thumbnailsOnMobile = p[Keys.thumbsMobile] ?: false,
            useSingleColumn = p[Keys.singleColumn] ?: false,
            // 老数据没有 browse_mode 键：退回旧的「默认单列显示」布尔值，
            // 否则曾开启该开关的用户会突然变成双列（静默改掉用户的既有选择）
            browseMode = p[Keys.browseMode]
                ?: if (p[Keys.singleColumn] == true) "SINGLE" else "DUAL",
            userAgent = p[Keys.userAgent] ?: "PanelFM/0.1 (Android)",
            trustSelfSigned = p[Keys.trustSelfSigned] ?: false,
            homePath = p[Keys.homePath],
            showSeconds = p[Keys.showSeconds] ?: false,
            rememberLastPath = p[Keys.rememberLast] ?: true,
            splitRatio = p[Keys.splitRatio] ?: 0.5f,
            bookmarkSwipe = p[Keys.bookmarkSwipe] ?: true,
            bottomBarPaddingDp = p[Keys.bottomPad] ?: 8,
            folderSorts = (p[Keys.folderSorts] ?: emptySet())
                .mapNotNull { line ->
                    val parts = line.split('|')
                    if (parts.size == 4) parts[0] to "${parts[1]}|${parts[2]}|${parts[3]}" else null
                }
                .toMap(),
            searchHistory = (p[Keys.searchHistory] ?: "").split('\n').filter { it.isNotBlank() },
            fontScaleLevel = p[Keys.fontScaleLevel] ?: 1,
            skipThumbsWhileScrolling = p[Keys.skipThumbs] ?: true,
            preserveModifiedTime = p[Keys.preserveMtime] ?: true,
            startAtHome = p[Keys.startAtHome] ?: false,
            listDisplayMode = (p[Keys.listDisplayMode] ?: 2).coerceIn(0, 2),
            thumbnailMaxBytes = p[Keys.thumbMaxBytes] ?: (3L * 1024 * 1024),
            thumbnailTimeoutSec = p[Keys.thumbTimeoutSec] ?: 5,
            confirmExit = p[Keys.confirmExit] ?: false,
            backupOnSave = p[Keys.backupOnSave] ?: false,
            dialogIconMode = (p[Keys.dialogIconMode] ?: 0).coerceIn(0, 2),
            tapRangeSelect = p[Keys.tapRangeSelect] ?: false,
            inputHistory = (p[Keys.inputHistory] ?: emptySet())
                .mapNotNull { line ->
                    val idx = line.indexOf('|')
                    if (idx <= 0) null
                    else line.substring(0, idx) to line.substring(idx + 1).split('\u0001').filter { it.isNotBlank() }
                }
                .toMap(),
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.panelDataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = context.panelDataStore.edit { it[Keys.dynamicColor] = on }
    suspend fun setShowHidden(on: Boolean) = context.panelDataStore.edit { it[Keys.showHidden] = on }
    suspend fun setSort(by: SortBy, ascending: Boolean) = context.panelDataStore.edit {
        it[Keys.sortBy] = by.name
        it[Keys.sortAsc] = ascending
    }
    suspend fun setDirsFirst(on: Boolean) = context.panelDataStore.edit { it[Keys.dirsFirst] = on }
    suspend fun setMaxConcurrent(n: Int) = context.panelDataStore.edit { it[Keys.maxConcurrent] = n.coerceIn(1, 4) }
    suspend fun setThumbsOnMobile(on: Boolean) = context.panelDataStore.edit { it[Keys.thumbsMobile] = on }
    suspend fun setSkipThumbsWhileScrolling(on: Boolean) = context.panelDataStore.edit { it[Keys.skipThumbs] = on }
    suspend fun setSingleColumn(on: Boolean) = context.panelDataStore.edit {
        it[Keys.singleColumn] = on
        // 两个键保持同步：browse_mode 是新的一等入口，single_column 只作兼容回退
        it[Keys.browseMode] = if (on) "SINGLE" else "DUAL"
    }

    /** 浏览模式（AUTO / SINGLE / DUAL）。与 [setSingleColumn] 同步写，避免两个开关打架。 */
    suspend fun setBrowseMode(mode: String) = context.panelDataStore.edit {
        it[Keys.browseMode] = mode
        it[Keys.singleColumn] = (mode == "SINGLE")
    }
    suspend fun setUserAgent(ua: String) = context.panelDataStore.edit { it[Keys.userAgent] = ua }
    suspend fun setTrustSelfSigned(on: Boolean) = context.panelDataStore.edit { it[Keys.trustSelfSigned] = on }

    suspend fun setShowSeconds(on: Boolean) = context.panelDataStore.edit { it[Keys.showSeconds] = on }
    suspend fun setRememberLastPath(on: Boolean) = context.panelDataStore.edit { it[Keys.rememberLast] = on }
    suspend fun setSplitRatio(ratio: Float) = context.panelDataStore.edit { it[Keys.splitRatio] = ratio }
    suspend fun setBookmarkSwipe(on: Boolean) = context.panelDataStore.edit { it[Keys.bookmarkSwipe] = on }
    suspend fun setBottomBarPadding(dp: Int) = context.panelDataStore.edit { it[Keys.bottomPad] = dp.coerceIn(0, 28) }

    /** 按文件夹记忆排序：value == null 表示清除该文件夹的规则 */
    suspend fun setFolderSort(key: String, value: String?) = context.panelDataStore.edit { prefs ->
        val cleaned = (prefs[Keys.folderSorts] ?: emptySet()).filterNot { it.startsWith("$key|") }
        prefs[Keys.folderSorts] = if (value == null) cleaned.toSet() else (cleaned + "$key|$value").toSet()
    }

    suspend fun clearFolderSorts() = context.panelDataStore.edit { it[Keys.folderSorts] = emptySet() }

    suspend fun setFontScaleLevel(level: Int) = context.panelDataStore.edit { it[Keys.fontScaleLevel] = level.coerceIn(0, 2) }
    suspend fun setPreserveModifiedTime(on: Boolean) = context.panelDataStore.edit { it[Keys.preserveMtime] = on }
    suspend fun setStartAtHome(on: Boolean) = context.panelDataStore.edit { it[Keys.startAtHome] = on }
    suspend fun setListDisplayMode(mode: Int) = context.panelDataStore.edit { it[Keys.listDisplayMode] = mode.coerceIn(0, 2) }
    suspend fun setThumbnailMaxBytes(bytes: Long) = context.panelDataStore.edit { it[Keys.thumbMaxBytes] = bytes.coerceAtLeast(0) }
    suspend fun setThumbnailTimeoutSec(sec: Int) = context.panelDataStore.edit { it[Keys.thumbTimeoutSec] = sec.coerceIn(0, 60) }
    suspend fun setConfirmExit(on: Boolean) = context.panelDataStore.edit { it[Keys.confirmExit] = on }
    suspend fun setBackupOnSave(on: Boolean) = context.panelDataStore.edit { it[Keys.backupOnSave] = on }
    suspend fun setDialogIconMode(mode: Int) = context.panelDataStore.edit { it[Keys.dialogIconMode] = mode.coerceIn(0, 2) }
    suspend fun setTapRangeSelect(on: Boolean) = context.panelDataStore.edit { it[Keys.tapRangeSelect] = on }

    /**
     * MT 的输入框历史（`app:recordKey`）：记一条历史（去重、最近在前、最多 [limit] 条）。
     * 键名用 [RecordKeys] 里的常量，与 MT 的资源键保持一致。
     */
    suspend fun addInputHistory(key: String, value: String, limit: Int = 12) = context.panelDataStore.edit { prefs ->
        val v = value.trim()
        if (v.isEmpty()) return@edit
        val all = (prefs[Keys.inputHistory] ?: emptySet()).filterNot { it.startsWith("$key|") }
        val old = (prefs[Keys.inputHistory] ?: emptySet())
            .firstOrNull { it.startsWith("$key|") }
            ?.substringAfter('|')
            ?.split('\u0001')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val merged = (listOf(v) + old.filterNot { it == v }).take(limit)
        prefs[Keys.inputHistory] = (all + "$key|" + merged.joinToString("\u0001")).toSet()
    }

    suspend fun addSearchQuery(query: String) = context.panelDataStore.edit { prefs ->
        val q = query.trim()
        if (q.isEmpty()) return@edit
        val old = (prefs[Keys.searchHistory] ?: "").split('\n').filter { it.isNotBlank() }
        prefs[Keys.searchHistory] = (listOf(q) + old.filterNot { it == q }).take(10).joinToString("\n")
    }

    suspend fun saveLastPaths(left: String?, right: String?) = context.panelDataStore.edit { prefs ->
        if (left == null) prefs.remove(Keys.lastLeft) else prefs[Keys.lastLeft] = left
        if (right == null) prefs.remove(Keys.lastRight) else prefs[Keys.lastRight] = right
    }

    /** 异步读上次双列路径（启动不阻塞主线程） */
    suspend fun lastPathsSuspend(): Pair<String?, String?> {
        val p = context.panelDataStore.data.first()
        return p[Keys.lastLeft] to p[Keys.lastRight]
    }

    // ------------------------------------------------------------------ 上次打开的文件

    data class LastOpened(val mode: String, val uri: String, val at: Long)

    /**
     * 记录「当前正打开着的文件」。
     * 退出/被杀时如果这一条还在（用户没主动返回），下次启动会自动回到它 ——
     * 覆盖「看视频时被系统杀掉」「直接退出 App」两种场景。
     */
    suspend fun saveLastOpenedPreview(mode: String, uri: String) = context.panelDataStore.edit { prefs ->
        prefs[Keys.lastOpenedPreview] = "$mode|$uri|${System.currentTimeMillis()}"
    }

    /** 用户主动离开预览（返回）→ 不再是「打开着的文件」，下次启动不自动跳回 */
    suspend fun clearLastOpenedPreview() = context.panelDataStore.edit { prefs ->
        prefs.remove(Keys.lastOpenedPreview)
    }

    suspend fun lastOpenedPreview(): LastOpened? =
        context.panelDataStore.data.first()[Keys.lastOpenedPreview]?.let { raw ->
            val first = raw.indexOf('|')
            val last = raw.lastIndexOf('|')
            if (first <= 0 || last <= first) return@let null
            val at = raw.substring(last + 1).toLongOrNull() ?: return@let null
            LastOpened(
                mode = raw.substring(0, first),
                uri = raw.substring(first + 1, last),
                at = at,
            )
        }

    suspend fun setHomePath(uri: String?) = context.panelDataStore.edit { prefs ->
        if (uri == null) prefs.remove(Keys.homePath) else prefs[Keys.homePath] = uri
    }
}
