package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.db.AuthorProfileDao
import com.heihun.pixivplus.core.db.HistoryDao
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HistoryPayloadBuilder {

    const val KEY_ILLUSTS = "illusts"
    const val KEY_NOVELS = "novels"

    private const val TYPE_ILLUST = "ILLUST"
    private const val TYPE_MANGA = "MANGA"
    private const val DEFAULT_WIDTH = 1000
    private const val DEFAULT_HEIGHT = 1000

    /**
     * 把本地历史合并到服务端响应。
     *
     * @param nextUrl 本地记录还有下一页时指向下一批的地址；为 null 时保留服务端原有的 `next_url`。
     */
    fun mergeLocalHistory(
        responseText: String,
        listKey: String,
        rows: List<HistoryDao.HistoryItem>,
        profiles: Map<Long, AuthorProfileDao.Profile> = emptyMap(),
        nextUrl: String? = null
    ): String? {
        if (rows.isEmpty()) return null
        val root = try {
            JSONObject(responseText)
        } catch (_: Throwable) {
            return null
        }
        val serverItems = root.optJSONArray(listKey) ?: return null
        val merged = JSONArray()
        val known = HashSet<Long>()
        for (index in 0 until serverItems.length()) {
            val item = serverItems.optJSONObject(index) ?: continue
            val id = item.optLong("id", 0L)
            if (id > 0L) known.add(id)
        }
        var added = 0
        for (row in rows) {
            if (row.contentId <= 0L) continue
            if (!known.add(row.contentId)) continue
            val profile = row.authorId?.let { profiles[it] }
            val item = if (listKey == KEY_NOVELS) {
                buildNovelItem(row, profile)
            } else {
                buildIllustItem(row, profile)
            } ?: continue
            merged.put(item)
            added++
        }
        for (index in 0 until serverItems.length()) {
            val item = serverItems.optJSONObject(index) ?: continue
            merged.put(item)
        }
        if (added == 0 && nextUrl == null) return null
        root.put(listKey, merged)
        if (nextUrl != null) {
            root.put("next_url", nextUrl)
        }
        return root.toString()
    }

    fun buildIllustItem(
        row: HistoryDao.HistoryItem,
        profile: AuthorProfileDao.Profile? = null
    ): JSONObject? {
        val thumbnail = row.thumbnailUrl.orEmpty()
        if (thumbnail.isBlank()) return null
        val pages = PageImageCodec.decode(row.pageImages)
        val width = row.width.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val height = row.height.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        val item = JSONObject()
        item.put("id", row.contentId)
        item.put("title", row.title.orEmpty())
        item.put("type", if (row.type == TYPE_MANGA) "manga" else "illust")
        item.put("image_urls", imageUrls(thumbnail))
        item.put("caption", row.caption.orEmpty())
        item.put("user", user(row, profile, thumbnail))
        item.put("tags", tags(row))
        item.put("create_date", formatDate(row.visitedAt))
        item.put("page_count", if (pages.isEmpty()) 1 else pages.size)
        item.put("width", width)
        item.put("height", height)
        item.put("sanity_level", 0)
        item.put("x_restrict", 0)
        item.put("series", JSONObject.NULL)
        item.put(
            "meta_single_page",
            JSONObject().put("original_image_url", row.originalUrl ?: thumbnail)
        )
        item.put("meta_pages", if (pages.isEmpty()) JSONArray() else PageImageCodec.toJson(pages))
        item.put("total_view", 0)
        item.put("total_bookmarks", 0)
        item.put("total_comments", 0)
        item.put("is_bookmarked", false)
        item.put("visible", true)
        item.put("is_muted", false)
        item.put("seasonal_effect_animation_urls", JSONObject.NULL)
        item.put("event_banners", JSONArray())
        item.put("illust_ai_type", 0)
        item.put("illust_book_style", 0)
        item.put("restriction_attributes", JSONArray())
        item.put("request", JSONObject.NULL)
        return item
    }

    fun buildNovelItem(
        row: HistoryDao.HistoryItem,
        profile: AuthorProfileDao.Profile? = null
    ): JSONObject? {
        val thumbnail = row.thumbnailUrl.orEmpty()
        if (thumbnail.isBlank()) return null
        val item = JSONObject()
        item.put("id", row.contentId)
        item.put("title", row.title.orEmpty())
        item.put("caption", row.caption.orEmpty())
        item.put("image_urls", imageUrls(thumbnail))
        item.put("user", user(row, profile, thumbnail))
        item.put("tags", tags(row))
        item.put("create_date", formatDate(row.visitedAt))
        item.put("page_count", 1)
        item.put("text_length", 0)
        item.put("restrict", 0)
        item.put("is_original", false)
        item.put("algorithm", "")
        item.put("series", JSONObject.NULL)
        item.put("total_view", 0)
        item.put("total_bookmarks", 0)
        item.put("total_comments", 0)
        item.put("is_bookmarked", false)
        item.put("visible", true)
        item.put("is_muted", false)
        item.put("sanity_level", 0)
        item.put("x_restrict", 0)
        item.put("novel_ai_type", 0)
        return item
    }

    fun isIllustType(type: String): Boolean = type == TYPE_ILLUST || type == TYPE_MANGA

    private fun imageUrls(thumbnail: String): JSONObject {
        return JSONObject()
            .put("square_medium", thumbnail)
            .put("medium", thumbnail)
            .put("large", thumbnail)
    }

    private fun user(
        row: HistoryDao.HistoryItem,
        profile: AuthorProfileDao.Profile?,
        fallbackImage: String
    ): JSONObject {
        val avatar = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: fallbackImage
        return JSONObject()
            .put("id", row.authorId ?: profile?.authorId ?: 0L)
            .put("name", profile?.name ?: row.authorName.orEmpty())
            .put("account", profile?.account.orEmpty())
            .put("profile_image_urls", JSONObject().put("medium", avatar))
            .put("is_followed", false)
            .put("is_accept_request", false)
    }

    private fun tags(row: HistoryDao.HistoryItem): JSONArray {
        val array = JSONArray()
        val raw = row.tags.orEmpty()
        if (raw.isEmpty()) return array
        for (name in raw.split(",")) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) continue
            array.put(JSONObject().put("name", trimmed).put("translated_name", JSONObject.NULL))
        }
        return array
    }

    private fun formatDate(millis: Long): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(millis))
        } catch (_: Throwable) {
            "1970-01-01T00:00:00+09:00"
        }
    }
}
