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
}
