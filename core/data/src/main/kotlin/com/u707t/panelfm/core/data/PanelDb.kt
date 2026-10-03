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
              created_at INTEGER NOT NULL DEFAULT 0
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
            CREATE TABLE preview_pref(
              ext TEXT PRIMARY KEY,
              handler_id TEXT NOT NULL,
              chosen_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 个人项目：破坏性升级足够，配置导出/导入兜底
        db.execSQL("DROP TABLE IF EXISTS connection")
        db.execSQL("DROP TABLE IF EXISTS secret")
        db.execSQL("DROP TABLE IF EXISTS bookmark")
        db.execSQL("DROP TABLE IF EXISTS path_history")
        db.execSQL("DROP TABLE IF EXISTS resume_entry")
        db.execSQL("DROP TABLE IF EXISTS task_record")
        db.execSQL("DROP TABLE IF EXISTS tab_session")
        db.execSQL("DROP TABLE IF EXISTS preview_pref")
        onCreate(db)
    }

    companion object {
        const val DB_VERSION = 1
    }
}
