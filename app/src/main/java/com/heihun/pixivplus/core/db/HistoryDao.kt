package com.heihun.pixivplus.core.db

import android.content.ContentValues
import android.database.Cursor
import com.heihun.pixivplus.core.log.ModuleLog

class HistoryDao(private val database: LocalDatabase) {

    data class HistoryItem(
        val type: String,
        val contentId: Long,
        val title: String?,
        val authorId: Long?,
        val authorName: String?,
        val thumbnailUrl: String?,
        val tags: String?,
        val caption: String?,
        val pageCount: Int,
        val visitedAt: Long,
        val width: Int = 0,
        val height: Int = 0,
        val originalUrl: String? = null,
        val pageImages: String? = null
    )

    private val columns =
        "type, content_id, title, author_id, author_name, thumbnail_url, tags, caption, page_count, visited_at, width, height, original_url, page_images"

    fun upsert(item: HistoryItem): Boolean {
        return try {
            val values = ContentValues().apply {
                put("type", item.type)
                put("content_id", item.contentId)
                put("title", item.title)
                put("author_id", item.authorId)
                put("author_name", item.authorName)
                put("thumbnail_url", item.thumbnailUrl)
                put("tags", item.tags)
                put("caption", item.caption)
                put("page_count", item.pageCount)
                put("visited_at", item.visitedAt)
                put("width", item.width)
                put("height", item.height)
                put("original_url", item.originalUrl)
                put("page_images", item.pageImages)
            }
            database.writable().replace(LocalDatabase.HISTORY_TABLE, null, values) > 0
        } catch (t: Throwable) {
            ModuleLog.e("HistoryDao", "upsert failed", t)
            false
        }
    }

    fun query(type: String, keyword: String?, limit: Int): List<HistoryItem> {
        val result = ArrayList<HistoryItem>()
        return try {
            val sql = StringBuilder("SELECT $columns FROM ${LocalDatabase.HISTORY_TABLE} WHERE type = ?")
            val args = ArrayList<String>()
            args.add(type)
            if (!keyword.isNullOrBlank()) {
                sql.append(" AND (title LIKE ? OR author_name LIKE ? OR tags LIKE ?)")
                val pattern = "%$keyword%"
                args.add(pattern)
                args.add(pattern)
                args.add(pattern)
            }
            sql.append(" ORDER BY visited_at DESC LIMIT ?")
            args.add(limit.toString())
            database.writable().rawQuery(sql.toString(), args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(read(cursor))
                }
            }
            result
        } catch (t: Throwable) {
            ModuleLog.e("HistoryDao", "query failed", t)
            result
        }
    }

    fun queryTypes(types: List<String>, limit: Int, offset: Int = 0): List<HistoryItem> {
        val result = ArrayList<HistoryItem>()
        if (types.isEmpty()) return result
        return try {
            val placeholders = types.joinToString(",") { "?" }
            val sql = "SELECT $columns FROM ${LocalDatabase.HISTORY_TABLE} WHERE type IN ($placeholders) " +
                "ORDER BY visited_at DESC LIMIT ? OFFSET ?"
            val args = ArrayList<String>(types)
            args.add(limit.toString())
            args.add(offset.coerceAtLeast(0).toString())
            database.writable().rawQuery(sql, args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(read(cursor))
                }
            }
            result
        } catch (t: Throwable) {
            ModuleLog.e("HistoryDao", "queryTypes failed", t)
            result
        }
    }

    /** 指定类型的历史总数，用于浏览记录翻页判断。 */
    fun countTypes(types: List<String>): Int {
        if (types.isEmpty()) return 0
        return try {
            val placeholders = types.joinToString(",") { "?" }
            val sql = "SELECT COUNT(*) FROM ${LocalDatabase.HISTORY_TABLE} WHERE type IN ($placeholders)"
            database.writable().rawQuery(sql, types.toTypedArray()).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (t: Throwable) {
            ModuleLog.e("HistoryDao", "countTypes failed", t)
            0
        }
    }

    fun queryAll(limit: Int): List<HistoryItem> {
        val result = ArrayList<HistoryItem>()
        return try {
            val sql = "SELECT $columns FROM ${LocalDatabase.HISTORY_TABLE} ORDER BY visited_at DESC LIMIT ?"
            database.writable().rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(read(cursor))
                }
            }
            result
        } catch (t: Throwable) {
            ModuleLog.e("HistoryDao", "queryAll failed", t)
            result
        }
    }

    fun delete(type: String, contentId: Long): Int {
        return try {
            database.writable().delete(
                LocalDatabase.HISTORY_TABLE,
                "type = ? AND content_id = ?",
                arrayOf(type, contentId.toString())
            )
        } catch (t: Throwable) {
            ModuleLog.e("HistoryDao", "delete failed", t)
            0
        }
    }

    fun clear(type: String?): Int {
        return try {
            if (type == null) {
                database.writable().delete(LocalDatabase.HISTORY_TABLE, null, null)
            } else {
                database.writable().delete(LocalDatabase.HISTORY_TABLE, "type = ?", arrayOf(type))
            }
        } catch (t: Throwable) {
            ModuleLog.e("HistoryDao", "clear failed", t)
            0
        }
    }

    fun count(type: String?): Int {
        return try {
            val cursor: Cursor = if (type == null) {
                database.writable().rawQuery("SELECT COUNT(*) FROM ${LocalDatabase.HISTORY_TABLE}", null)
            } else {
                database.writable().rawQuery("SELECT COUNT(*) FROM ${LocalDatabase.HISTORY_TABLE} WHERE type = ?", arrayOf(type))
            }
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (t: Throwable) {
            ModuleLog.e("HistoryDao", "count failed", t)
            0
        }
    }

    fun prune(olderThanMillis: Long): Int {
        return try {
            database.writable().delete(
                LocalDatabase.HISTORY_TABLE,
                "visited_at < ?",
                arrayOf(olderThanMillis.toString())
            )
        } catch (t: Throwable) {
            ModuleLog.e("HistoryDao", "prune failed", t)
            0
        }
    }

    private fun read(cursor: Cursor): HistoryItem {
        return HistoryItem(
            type = cursor.getString(0) ?: "",
            contentId = cursor.getLong(1),
            title = cursor.getString(2),
            authorId = if (cursor.isNull(3)) null else cursor.getLong(3),
            authorName = cursor.getString(4),
            thumbnailUrl = cursor.getString(5),
            tags = cursor.getString(6),
            caption = cursor.getString(7),
            pageCount = cursor.getInt(8),
            visitedAt = cursor.getLong(9),
            width = if (cursor.isNull(10)) 0 else cursor.getInt(10),
            height = if (cursor.isNull(11)) 0 else cursor.getInt(11),
            originalUrl = cursor.getString(12),
            pageImages = cursor.getString(13)
        )
    }
}
