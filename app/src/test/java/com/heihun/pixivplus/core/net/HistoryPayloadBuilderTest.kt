package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.db.AuthorProfileDao
import com.heihun.pixivplus.core.db.HistoryDao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPayloadBuilderTest {

    private fun row(
        type: String = "ILLUST",
        id: Long = 100L,
        title: String = "local work",
        author: String = "artist",
        authorId: Long = 55L,
        thumbnail: String = "https://img/$id.jpg",
        tags: String = "tag-a,tag-b",
        caption: String = "caption",
        pageCount: Int = 2,
        visitedAt: Long = 1_700_000_000_000L,
        width: Int = 0,
        height: Int = 0,
        originalUrl: String? = null,
        pageImages: String? = null
    ): HistoryDao.HistoryItem {
        return HistoryDao.HistoryItem(
            type = type,
            contentId = id,
            title = title,
            authorId = authorId,
            authorName = author,
            thumbnailUrl = thumbnail,
            tags = tags,
            caption = caption,
            pageCount = pageCount,
            visitedAt = visitedAt,
            width = width,
            height = height,
            originalUrl = originalUrl,
            pageImages = pageImages
        )
    }

    private fun profile(
        authorId: Long = 55L,
        name: String? = "artist",
        account: String? = "artist_account",
        avatar: String? = "https://img/avatar.jpg"
    ): AuthorProfileDao.Profile {
        return AuthorProfileDao.Profile(
            authorId = authorId,
            name = name,
            account = account,
            avatarUrl = avatar
        )
    }

    @Test
    fun buildsIllustItemWithRequiredFields() {
        val item = HistoryPayloadBuilder.buildIllustItem(row())
        assertNotNull(item)
        assertEquals(100L, item!!.getLong("id"))
        assertEquals("local work", item.getString("title"))
        assertEquals("illust", item.getString("type"))
        assertEquals("https://img/100.jpg", item.getJSONObject("image_urls").getString("medium"))
        assertEquals("artist", item.getJSONObject("user").getString("name"))
        assertEquals(2, item.getJSONArray("tags").length())
        assertEquals("tag-a", item.getJSONArray("tags").getJSONObject(0).getString("name"))
        assertTrue(item.getString("create_date").contains("T"))
    }

    @Test
    fun buildsMangaItemType() {
        val item = HistoryPayloadBuilder.buildIllustItem(row(type = "MANGA"))
        assertNotNull(item)
        assertEquals("manga", item!!.getString("type"))
    }

    @Test
    fun buildsNovelItem() {
        val item = HistoryPayloadBuilder.buildNovelItem(row(type = "NOVEL"))
        assertNotNull(item)
        assertEquals(100L, item!!.getLong("id"))
        assertEquals("local work", item.getString("title"))
        assertEquals("artist", item.getJSONObject("user").getString("name"))
    }

    @Test
    fun usesAuthorProfileAvatarWhenAvailable() {
        val item = HistoryPayloadBuilder.buildIllustItem(row(), profile())
        assertNotNull(item)
        val user = item!!.getJSONObject("user")
        assertEquals("https://img/avatar.jpg", user.getJSONObject("profile_image_urls").getString("medium"))
        assertEquals("artist_account", user.getString("account"))
        assertEquals("artist", user.getString("name"))
    }

    @Test
    fun fallsBackToThumbnailWhenAvatarMissing() {
        val item = HistoryPayloadBuilder.buildIllustItem(row(), profile(avatar = null))
        assertNotNull(item)
        val avatar = item!!.getJSONObject("user").getJSONObject("profile_image_urls").getString("medium")
        assertTrue(avatar.isNotEmpty())
        assertEquals("https://img/100.jpg", avatar)
    }

    @Test
    fun skipsRowsWithoutThumbnail() {
        assertNull(HistoryPayloadBuilder.buildIllustItem(row(thumbnail = "")))
        assertNull(HistoryPayloadBuilder.buildNovelItem(row(type = "NOVEL", thumbnail = "")))
    }

    @Test
    fun mergesLocalItemsBeforeServerItems() {
        val server = JSONObject()
            .put("illusts", org.json.JSONArray().put(JSONObject().put("id", 1).put("title", "server")))
            .put("next_url", JSONObject.NULL)
            .toString()
        val merged = HistoryPayloadBuilder.mergeLocalHistory(server, "illusts", listOf(row(id = 2L)))
        assertNotNull(merged)
        val array = JSONObject(merged!!).getJSONArray("illusts")
        assertEquals(2, array.length())
        assertEquals(2L, array.getJSONObject(0).getLong("id"))
        assertEquals(1L, array.getJSONObject(1).getLong("id"))
    }

    @Test
    fun skipsIdsAlreadyPresentInServerList() {
        val server = JSONObject()
            .put("illusts", org.json.JSONArray().put(JSONObject().put("id", 2).put("title", "server")))
            .toString()
        val merged = HistoryPayloadBuilder.mergeLocalHistory(server, "illusts", listOf(row(id = 2L)))
        assertNull(merged)
    }

    @Test
    fun returnsNullWithoutRows() {
        val server = JSONObject().put("illusts", org.json.JSONArray()).toString()
        assertNull(HistoryPayloadBuilder.mergeLocalHistory(server, "illusts", emptyList()))
    }

    @Test
    fun skipsRowsWithoutThumbnailDuringMerge() {
        val server = JSONObject().put("illusts", org.json.JSONArray()).toString()
        val merged = HistoryPayloadBuilder.mergeLocalHistory(server, "illusts", listOf(row(thumbnail = "")))
        assertNull(merged)
    }

    @Test
    fun keepsNextUrlField() {
        val server = JSONObject()
            .put("novels", org.json.JSONArray())
            .put("next_url", "https://next")
            .toString()
        val merged = HistoryPayloadBuilder.mergeLocalHistory(server, "novels", listOf(row(type = "NOVEL")))
        assertNotNull(merged)
        assertEquals("https://next", JSONObject(merged!!).getString("next_url"))
    }

    @Test
    fun usesStoredDimensions() {
        val item = HistoryPayloadBuilder.buildIllustItem(row(width = 1200, height = 1600))
        assertNotNull(item)
        assertEquals(1200, item!!.getInt("width"))
        assertEquals(1600, item.getInt("height"))
    }

    @Test
    fun fallsBackToSafeDimensionsWhenUnknown() {
        val item = HistoryPayloadBuilder.buildIllustItem(row())
        assertNotNull(item)
        assertTrue(item!!.getInt("width") > 0)
        assertTrue(item.getInt("height") > 0)
    }

    @Test
    fun usesOriginalUrlForSinglePage() {
        val item = HistoryPayloadBuilder.buildIllustItem(row(originalUrl = "https://img/original.jpg"))
        assertNotNull(item)
        assertEquals(
            "https://img/original.jpg",
            item!!.getJSONObject("meta_single_page").getString("original_image_url")
        )
        assertEquals(0, item.getJSONArray("meta_pages").length())
    }

    @Test
    fun buildsMetaPagesFromStoredPageImages() {
        val encoded = PageImageCodec.encode(
            listOf(
                com.heihun.pixivplus.core.util.PageImage("https://img/p0.jpg", "https://img/p0_l.jpg", "https://img/p0_m.jpg", 0, 0),
                com.heihun.pixivplus.core.util.PageImage("https://img/p1.jpg", "https://img/p1_l.jpg", "https://img/p1_m.jpg", 0, 0)
            )
        )
        val item = HistoryPayloadBuilder.buildIllustItem(row(pageImages = encoded))
        assertNotNull(item)
        val pages = item!!.getJSONArray("meta_pages")
        assertEquals(2, pages.length())
        assertEquals(2, item.getInt("page_count"))
        assertEquals("https://img/p1.jpg", pages.getJSONObject(1).getJSONObject("image_urls").getString("original"))
    }
}
