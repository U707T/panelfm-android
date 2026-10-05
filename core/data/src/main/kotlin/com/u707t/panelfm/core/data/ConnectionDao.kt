package com.u707t.panelfm.core.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 连接配置读写（口令走 [SecretStore]，这里有且只有 secretRef）。 */
class ConnectionDao(private val db: PanelDb) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), String.serializer())

    fun all(): List<ConnectionConfig> = db.readableDatabase.query(
        "connection", null, null, null, null, null, "sort_order ASC, id ASC"
    ).use { c ->
        val out = ArrayList<ConnectionConfig>()
        while (c.moveToNext()) out.add(c.toConfig())
        out
    }

    fun insert(config: ConnectionConfig): Long {
        val values = config.toValues(includeCreatedAt = true)
        values.remove("id")
        return db.writableDatabase.insert("connection", null, values)
    }

    fun update(config: ConnectionConfig) {
        // 注意：update 不能带上 created_at —— 否则每次编辑连接都会把「创建时间」改成当前时间，
        // 侧边栏按创建时间排序 / 展示会随之漂移（旧实现 toValues() 里无条件写 created_at）。
        db.writableDatabase.update("connection", config.toValues(includeCreatedAt = false), "id = ?", arrayOf(config.id.toString()))
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("connection", "id = ?", arrayOf(id.toString()))
        db.writableDatabase.delete("secret", "ref = ?", arrayOf(secretRef(id)))
    }

    fun touch(id: Long) {
        val values = ContentValues().apply { put("last_used_at", System.currentTimeMillis()) }
        db.writableDatabase.update("connection", values, "id = ?", arrayOf(id.toString()))
    }

    fun secretRef(id: Long) = "conn-$id"

    private fun ConnectionConfig.toValues(includeCreatedAt: Boolean) = ContentValues().apply {
        put("id", id)
        put("type", type.name)
        put("name", name)
        put("host", host)
        put("port", port)
        put("user", user)
        put("secret_ref", secretRef ?: secretRef(id))
        put("base_path", basePath)
        put("group_name", group)
        put("options_json", json.encodeToString(mapSer, options))
        put("sort_order", sortOrder)
        put("last_used_at", lastUsedAt)
        // created_at 只在 insert 时写；update 保留库里原值（见 update() 注释）
        if (includeCreatedAt) put("created_at", System.currentTimeMillis())
    }

    private fun Cursor.toConfig(): ConnectionConfig {
        val id = getLong(getColumnIndexOrThrow("id"))
        val typeName = getString(getColumnIndexOrThrow("type"))
        val optionsJson = getString(getColumnIndexOrThrow("options_json")) ?: "{}"
        val options: Map<String, String> = runCatching {
            json.decodeFromString(mapSer, optionsJson)
        }.getOrDefault(emptyMap())
        return ConnectionConfig(
            id = id,
            type = runCatching { ConnectionType.valueOf(typeName) }.getOrDefault(ConnectionType.WEBDAV),
            name = getString(getColumnIndexOrThrow("name")) ?: "",
            host = getString(getColumnIndexOrThrow("host")) ?: "",
            port = getInt(getColumnIndexOrThrow("port")),
            user = getString(getColumnIndexOrThrow("user")) ?: "",
            secretRef = getString(getColumnIndexOrThrow("secret_ref")),
            basePath = getString(getColumnIndexOrThrow("base_path")) ?: "/",
            group = getString(getColumnIndexOrThrow("group_name")) ?: "",
            options = options,
            sortOrder = getInt(getColumnIndexOrThrow("sort_order")),
            lastUsedAt = getLong(getColumnIndexOrThrow("last_used_at")),
        )
    }

    private fun Cursor.getStringOrNull(name: String): String? =
        getColumnIndex(name).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }
}
