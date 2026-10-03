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
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val showHidden: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val sortAscending: Boolean = true,
    val dirsFirst: Boolean = true,
    val maxConcurrentTasks: Int = 2,
    val thumbnailsOnMobile: Boolean = false,
    val useSingleColumn: Boolean = false,
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
    val skipThumbsWhileScrolling: Boolean = true,
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
        val userAgent = stringPreferencesKey("user_agent")
        val trustSelfSigned = booleanPreferencesKey("trust_self_signed")
        val homePath = stringPreferencesKey("home_path")
        val showSeconds = booleanPreferencesKey("show_seconds")
        val rememberLast = booleanPreferencesKey("remember_last_path")
        val splitRatio = androidx.datastore.preferences.core.floatPreferencesKey("split_ratio")
        val bookmarkSwipe = booleanPreferencesKey("bookmark_swipe")
        val lastLeft = stringPreferencesKey("last_left")
        val lastRight = stringPreferencesKey("last_right")
    }

    val settings: Flow<AppSettings> = context.panelDataStore.data.map { p ->
        AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.theme] ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = p[Keys.dynamicColor] ?: true,
            showHidden = p[Keys.showHidden] ?: false,
            sortBy = runCatching { SortBy.valueOf(p[Keys.sortBy] ?: SortBy.NAME.name) }.getOrDefault(SortBy.NAME),
            sortAscending = p[Keys.sortAsc] ?: true,
            dirsFirst = p[Keys.dirsFirst] ?: true,
            maxConcurrentTasks = p[Keys.maxConcurrent] ?: 2,
            thumbnailsOnMobile = p[Keys.thumbsMobile] ?: false,
            useSingleColumn = p[Keys.singleColumn] ?: false,
            userAgent = p[Keys.userAgent] ?: "PanelFM/0.1 (Android)",
            trustSelfSigned = p[Keys.trustSelfSigned] ?: false,
            homePath = p[Keys.homePath],
            showSeconds = p[Keys.showSeconds] ?: false,
            rememberLastPath = p[Keys.rememberLast] ?: true,
            splitRatio = p[Keys.splitRatio] ?: 0.5f,
            bookmarkSwipe = p[Keys.bookmarkSwipe] ?: true,
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
    suspend fun setSingleColumn(on: Boolean) = context.panelDataStore.edit { it[Keys.singleColumn] = on }
    suspend fun setUserAgent(ua: String) = context.panelDataStore.edit { it[Keys.userAgent] = ua }
    suspend fun setTrustSelfSigned(on: Boolean) = context.panelDataStore.edit { it[Keys.trustSelfSigned] = on }

    suspend fun setShowSeconds(on: Boolean) = context.panelDataStore.edit { it[Keys.showSeconds] = on }
    suspend fun setRememberLastPath(on: Boolean) = context.panelDataStore.edit { it[Keys.rememberLast] = on }
    suspend fun setSplitRatio(ratio: Float) = context.panelDataStore.edit { it[Keys.splitRatio] = ratio }
    suspend fun setBookmarkSwipe(on: Boolean) = context.panelDataStore.edit { it[Keys.bookmarkSwipe] = on }

    suspend fun saveLastPaths(left: String?, right: String?) = context.panelDataStore.edit { prefs ->
        if (left == null) prefs.remove(Keys.lastLeft) else prefs[Keys.lastLeft] = left
        if (right == null) prefs.remove(Keys.lastRight) else prefs[Keys.lastRight] = right
    }

    fun lastPaths(): Pair<String?, String?> {
        // 注意：DataStore 是异步的，这里用 runBlocking 只为启动时读取一次
        return runCatching {
            kotlinx.coroutines.runBlocking {
                var l: String? = null
                var r: String? = null
                context.panelDataStore.data.collect { p ->
                    l = p[Keys.lastLeft]
                    r = p[Keys.lastRight]
                    throw kotlinx.coroutines.CancellationException()
                }
                l to r
            }
        }.getOrElse { null to null }
    }

    suspend fun setHomePath(uri: String?) = context.panelDataStore.edit { prefs ->
        if (uri == null) prefs.remove(Keys.homePath) else prefs[Keys.homePath] = uri
    }
}
