package com.heihun.pixivplus.core.db

import android.content.ContentValues
import com.heihun.pixivplus.core.log.ModuleLog

class AuthorProfileDao(private val database: LocalDatabase) {

    data class Profile(
        val authorId: Long,
        val name: String?,
        val account: String?,
        val avatarUrl: String?
    )

    fun upsert(profile: Profile): Boolean {
        return try {
            val values = ContentValues().apply {
                put("author_id", profile.authorId)
                put("name", profile.name)
                put("account", profile.account)
                put("avatar_url", profile.avatarUrl)
                put("updated_at", System.currentTimeMillis())
            }
            database.writable().replace(LocalDatabase.AUTHOR_PROFILE_TABLE, null, values) > 0
        } catch (t: Throwable) {
            ModuleLog.e("AuthorProfileDao", "upsert failed", t)
            false
        }
    }

    fun query(authorIds: Collection<Long>): Map<Long, Profile> {
        val result = HashMap<Long, Profile>()
        val ids = authorIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return result
        return try {
            val placeholders = ids.joinToString(",") { "?" }
            val sql = "SELECT author_id, name, account, avatar_url FROM ${LocalDatabase.AUTHOR_PROFILE_TABLE} WHERE author_id IN ($placeholders)"
            database.writable().rawQuery(sql, ids.map { it.toString() }.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    result[id] = Profile(
                        authorId = id,
                        name = cursor.getString(1)?.ifEmpty { null },
                        account = cursor.getString(2)?.ifEmpty { null },
                        avatarUrl = cursor.getString(3)?.ifEmpty { null }
                    )
                }
            }
            result
        } catch (t: Throwable) {
            ModuleLog.e("AuthorProfileDao", "query failed", t)
            result
        }
    }
}
