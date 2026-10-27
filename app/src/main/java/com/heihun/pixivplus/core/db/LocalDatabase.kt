package com.heihun.pixivplus.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.heihun.pixivplus.core.log.ModuleLog

class LocalDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try {
            db.setForeignKeyConstraintsEnabled(true)
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS history (
                type TEXT NOT NULL,
                content_id INTEGER NOT NULL,
                title TEXT,
                author_id INTEGER,
                author_name TEXT,
                thumbnail_url TEXT,
                tags TEXT,
                caption TEXT,
                page_count INTEGER NOT NULL DEFAULT 0,
                visited_at INTEGER NOT NULL,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                original_url TEXT,
                page_images TEXT,
                PRIMARY KEY (type, content_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_visited_at ON history (visited_at DESC)")
        createAuthorProfile(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS filter_rule (
                rule_id INTEGER PRIMARY KEY AUTOINCREMENT,
                rule_type TEXT NOT NULL,
                value TEXT NOT NULL,
                match_mode TEXT NOT NULL,
                scope TEXT NOT NULL,
                target_types TEXT NOT NULL,
                action TEXT NOT NULL,
                surfaces TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        ModuleLog.i("LocalDatabase", "schema created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ModuleLog.i("LocalDatabase", "upgrade $oldVersion -> $newVersion (no destructive migration)")
        createAuthorProfile(db)
        addHistoryColumns(db)
    }

    private fun addHistoryColumns(db: SQLiteDatabase) {
        val columns = HashSet<String>()
        try {
            db.rawQuery("PRAGMA table_info(${HISTORY_TABLE})", null).use { cursor ->
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(1))
                }
            }
        } catch (t: Throwable) {
            ModuleLog.e("LocalDatabase", "history column scan failed", t)
            return
        }
        val additions = listOf(
            "width" to "INTEGER NOT NULL DEFAULT 0",
            "height" to "INTEGER NOT NULL DEFAULT 0",
            "original_url" to "TEXT",
            "page_images" to "TEXT"
        )
        for ((name, declaration) in additions) {
            if (columns.contains(name)) continue
            try {
                db.execSQL("ALTER TABLE ${HISTORY_TABLE} ADD COLUMN $name $declaration")
            } catch (t: Throwable) {
                ModuleLog.e("LocalDatabase", "add column $name failed", t)
            }
        }
    }

    private fun createAuthorProfile(db: SQLiteDatabase) {
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS author_profile (
                    author_id INTEGER PRIMARY KEY,
                    name TEXT,
                    account TEXT,
                    avatar_url TEXT,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        } catch (t: Throwable) {
            ModuleLog.e("LocalDatabase", "author_profile table creation failed", t)
        }
    }

    fun writable(): SQLiteDatabase {
        return try {
            writableDatabase
        } catch (t: Throwable) {
            ModuleLog.e("LocalDatabase", "writable failed", t)
            throw t
        }
    }

    companion object {
        const val DATABASE_NAME = "pixiv_local_plus.db"
        const val DATABASE_VERSION = 3

        const val HISTORY_TABLE = "history"
        const val FILTER_RULE_TABLE = "filter_rule"
        const val AUTHOR_PROFILE_TABLE = "author_profile"
    }
}
