package com.u707t.panelfm.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 轻量持久化：直接用 SQLiteOpenHelper（不引入 Room / 注解处理器），
 * 表结构按策划文档 §11 设计，后续要换 Room 只需替换 DAO 实现。
 */
class PanelDb(context: Context) : SQLiteOpenHelper(context, "panel.db", null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE connection(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              type TEXT NOT NULL,
              name TEXT NOT NULL,
              host TEXT NOT NULL DEFAULT '',
              port INTEGER NOT NULL DEFAULT 0,
              user TEXT NOT NULL DEFAULT '',
              secret_ref TEXT,
              base_path TEXT NOT NULL DEFAULT '/',
              group_name TEXT NOT NULL DEFAULT '',
              options_json TEXT NOT NULL DEFAULT '{}',
              sort_order INTEGER NOT NULL DEFAULT 0,
              last_used_at INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE secret(
              ref TEXT PRIMARY KEY,
              cipher TEXT NOT NULL,
              iv TEXT NOT NULL,
              updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE bookmark(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              connection_id INTEGER,
              uri TEXT NOT NULL,
              name TEXT NOT NULL,
              created_at INTEGER NOT NULL DEFAULT 0,
              sort_order INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE path_history(
              uri TEXT PRIMARY KEY,
              connection_id INTEGER,
              visited_at INTEGER NOT NULL DEFAULT 0,
              hits INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE resume_entry(
              task_id TEXT NOT NULL,
              item_index INTEGER NOT NULL,
              source TEXT NOT NULL,
              dest TEXT NOT NULL,
              temp_uri TEXT,
              offset_done INTEGER NOT NULL DEFAULT 0,
              total_size INTEGER NOT NULL DEFAULT -1,
              validator TEXT,
              updated_at INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(task_id, item_index)
            )
            """.trimIndent()
        )
        // 断点续传按 (source, dest) 路径查找（ResumeDao.findFor）——没有索引时每次全表扫描。
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_resume_source_dest ON resume_entry(source, dest)"
        )
        db.execSQL(
            """
            CREATE TABLE task_record(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              op TEXT NOT NULL,
              title TEXT NOT NULL,
              status TEXT NOT NULL,
              bytes INTEGER NOT NULL DEFAULT 0,
              file_count INTEGER NOT NULL DEFAULT 0,
              started_at INTEGER NOT NULL DEFAULT 0,
              ended_at INTEGER NOT NULL DEFAULT 0,
              error TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE tab_session(
              pane TEXT NOT NULL,
              tab_index INTEGER NOT NULL,
              uri TEXT NOT NULL,
              connection_id INTEGER,
              scroll_index INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(pane, tab_index)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE known_host(
              host TEXT NOT NULL,
              port INTEGER NOT NULL,
              kind TEXT NOT NULL DEFAULT 'ssh',
              key_type TEXT NOT NULL DEFAULT '',
              fingerprint_sha256 TEXT NOT NULL,
              added_at INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(host, port)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE preview_pref(
              ext TEXT PRIMARY KEY,
              handler_id TEXT NOT NULL,
              chosen_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 加法式迁移：只补新表/新列，不动用户数据
        if (oldVersion < 3) {
            // 书签「长按后拖动排序」（MT 0x7f110140）：新增排序列，老数据按创建时间序（默认 0）
            runCatching { db.execSQL("ALTER TABLE bookmark ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0") }
        }
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS known_host(
                  host TEXT NOT NULL,
                  port INTEGER NOT NULL,
                  kind TEXT NOT NULL DEFAULT 'ssh',
                  key_type TEXT NOT NULL DEFAULT '',
                  fingerprint_sha256 TEXT NOT NULL,
                  added_at INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY(host, port)
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 4) {
            // 断点续传查询按 (source, dest) 走索引（第 4 批审计 🔵4）：大目录批量复制的逐文件
            // 查找不再全表扫描。IF NOT EXISTS：v3 老库升级与 v4 新装库都安全（迁移幂等）。
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_resume_source_dest ON resume_entry(source, dest)"
            )
        }
    }

    companion object {
        const val DB_VERSION = 4
    }
}
