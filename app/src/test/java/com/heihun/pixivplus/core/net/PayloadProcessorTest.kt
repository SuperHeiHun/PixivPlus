package com.heihun.pixivplus.core.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PayloadProcessorTest {

    @Before
    fun setUp() {
        PayloadProcessor.rules = FilterRules.EMPTY
        PayloadProcessor.historyEnabled = false
        PayloadProcessor.premiumEnabled = false
        PayloadProcessor.captionFixEnabled = false
        PayloadProcessor.detailSink = null
        PayloadProcessor.resetCounters()
    }

    private fun illust(id: Long, title: String, tags: List<String>, author: String, account: String): JSONObject {
        val tagArray = JSONArray()
        for (tag in tags) {
            tagArray.put(JSONObject().put("name", tag).put("translated_name", JSONObject.NULL))
        }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("caption", "caption-$id")
            .put("type", "illust")
            .put("is_premium", false)
            .put("tags", tagArray)
            .put("user", JSONObject().put("name", author).put("account", account))
            .put("image_urls", JSONObject().put("square", JSONObject().put("image_url", "https://img/$id")))
    }

    private fun feed(vararg items: JSONObject): String {
        val array = JSONArray()
        for (item in items) array.put(item)
        return JSONObject().put("illusts", array).toString()
    }

    @Test
    fun removesItemMatchingKeyword() {
        PayloadProcessor.rules = FilterRules(listOf("secret"), emptyList(), emptyList(), false)
        val payload = feed(
            illust(1, "a secret work", listOf("art"), "alice", "alice"),
            illust(2, "normal work", listOf("art"), "bob", "bob")
        )
        val output = PayloadProcessor.process(payload)
        assertNotNull(output)
        val ids = JSONObject(output!!).getJSONArray("illusts")
        assertEquals(1, ids.length())
        assertEquals(2L, ids.getJSONObject(0).getLong("id"))
        assertEquals(1L, PayloadProcessor.filteredCount())
    }

    @Test
    fun removesItemMatchingTag() {
        PayloadProcessor.rules = FilterRules(emptyList(), listOf("オリジナル"), emptyList(), false)
        val payload = feed(
            illust(1, "one", listOf("オリジナル", "illustration"), "alice", "alice"),
            illust(2, "two", listOf("landscape"), "bob", "bob")
        )
        val output = PayloadProcessor.process(payload)
        assertNotNull(output)
        val ids = JSONObject(output!!).getJSONArray("illusts")
        assertEquals(1, ids.length())
        assertEquals(2L, ids.getJSONObject(0).getLong("id"))
    }

    @Test
    fun removesItemMatchingAuthor() {
        PayloadProcessor.rules = FilterRules(emptyList(), emptyList(), listOf("blocked"), false)
        val payload = feed(
            illust(1, "one", listOf("art"), "blocked", "acc1"),
            illust(2, "two", listOf("art"), "bob", "acc2")
        )
        val output = PayloadProcessor.process(payload)
        assertNotNull(output)
        val ids = JSONObject(output!!).getJSONArray("illusts")
        assertEquals(1, ids.length())
        assertEquals(2L, ids.getJSONObject(0).getLong("id"))
    }

    @Test
    fun keepsEverythingWithoutRules() {
        val payload = feed(illust(1, "one", listOf("art"), "alice", "alice"))
        assertNull(PayloadProcessor.process(payload))
    }

    @Test
    fun forcesPremiumFlagInPayload() {
        PayloadProcessor.premiumEnabled = true
        val payload = feed(illust(1, "one", listOf("art"), "alice", "alice"))
        val output = PayloadProcessor.process(payload)
        assertNotNull(output)
        val item = JSONObject(output!!).getJSONArray("illusts").getJSONObject(0)
        assertTrue(item.getBoolean("is_premium"))
    }

    @Test
    fun extractsIllustDetail() {
        PayloadProcessor.historyEnabled = true
        var captured: JSONObject? = null
        var capturedType: String? = null
        PayloadProcessor.detailSink = { item, type ->
            captured = item
            capturedType = type
        }
        val detail = JSONObject().put("illust", illust(42, "detail", listOf("art"), "alice", "alice")).toString()
        PayloadProcessor.process(detail)
        assertNotNull(captured)
        assertEquals(42L, captured!!.getLong("id"))
        assertEquals("illust", capturedType)
    }

    @Test
    fun extractsNovelDetail() {
        PayloadProcessor.historyEnabled = true
        var capturedType: String? = null
        PayloadProcessor.detailSink = { _, type -> capturedType = type }
        val novel = JSONObject()
            .put("novel", JSONObject().put("id", 7).put("title", "novel").put("page_count", 3))
            .toString()
        PayloadProcessor.process(novel)
        assertEquals("novel", capturedType)
    }

    @Test
    fun ignoresUnrelatedPayload() {
        assertNull(PayloadProcessor.process("{\"user\":{\"id\":1}}"))
        assertFalse(PayloadProcessor.processedCount() > 0)
    }

    @Test
    fun parsesRuleLists() {
        val parsed = FilterRules.parse("a, b\nc，d、e;f")
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), parsed)
        assertTrue(FilterRules.parse(null).isEmpty())
    }

    private fun content(kind: String, id: Long, title: String, tags: List<String>, userName: String): JSONObject {
        val tagArray = JSONArray()
        for (tag in tags) {
            tagArray.put(JSONObject().put("name", tag).put("translated_name", JSONObject.NULL))
        }
        val thumbnail = JSONObject()
            .put("type", kind)
            .put("id", id)
            .put("title", title)
            .put("description", "description-$id")
            .put("tags", tagArray)
            .put("user_name", userName)
        return JSONObject()
            .put("kind", kind)
            .put("thumbnails", JSONArray().put(thumbnail))
            .put("access", JSONObject().put("typ", kind).put("cid", id))
    }

    @Test
    fun removesNewFeedContentMatchingTag() {
        PayloadProcessor.rules = FilterRules(emptyList(), listOf("オリジナル"), emptyList(), false)
        val payload = JSONObject()
            .put(
                "contents",
                JSONArray()
                    .put(content("manga", 1, "manga one", listOf("オリジナル"), "alice"))
                    .put(content("illust", 2, "illust two", listOf("landscape"), "bob"))
                    .put(content("novel", 3, "novel three", listOf("fiction"), "carol"))
            )
            .toString()
        val output = PayloadProcessor.process(payload)
        assertNotNull(output)
        val contents = JSONObject(output!!).getJSONArray("contents")
        assertEquals(2, contents.length())
        assertEquals(2L, contents.getJSONObject(0).getJSONArray("thumbnails").getJSONObject(0).getLong("id"))
        assertEquals(1L, PayloadProcessor.filteredCount())
    }

    @Test
    fun removesNewFeedContentMatchingUserName() {
        PayloadProcessor.rules = FilterRules(emptyList(), emptyList(), listOf("carol"), false)
        val payload = JSONObject()
            .put(
                "contents",
                JSONArray()
                    .put(content("illust", 1, "one", listOf("art"), "alice"))
                    .put(content("novel", 2, "two", listOf("art"), "carol"))
            )
            .toString()
        val output = PayloadProcessor.process(payload)
        assertNotNull(output)
        val contents = JSONObject(output!!).getJSONArray("contents")
        assertEquals(1, contents.length())
        assertEquals(1L, contents.getJSONObject(0).getJSONArray("thumbnails").getJSONObject(0).getLong("id"))
    }

    @Test
    fun removesNewFeedContentMatchingKeywordInDescription() {
        PayloadProcessor.rules = FilterRules(listOf("description-2"), emptyList(), emptyList(), false)
        val payload = JSONObject()
            .put(
                "contents",
                JSONArray()
                    .put(content("illust", 1, "one", listOf("art"), "alice"))
                    .put(content("illust", 2, "two", listOf("art"), "alice"))
            )
            .toString()
        val output = PayloadProcessor.process(payload)
        assertNotNull(output)
        assertEquals(1, JSONObject(output!!).getJSONArray("contents").length())
    }

    @Test
    fun keepsNewFeedContentWithoutMatchingRules() {
        PayloadProcessor.rules = FilterRules(listOf("nothing"), emptyList(), emptyList(), false)
        val payload = JSONObject()
            .put("contents", JSONArray().put(content("illust", 1, "one", listOf("art"), "alice")))
            .toString()
        assertNull(PayloadProcessor.process(payload))
    }

    @Test
    fun indexesListCaptionWhenCaptionFixEnabled() {
        PayloadProcessor.captionFixEnabled = true
        val payload = feed(illust(9001, "one", listOf("art"), "alice", "alice"))
        PayloadProcessor.process(payload)
        assertEquals("caption-9001", PayloadProcessor.lookupCaption(9001))
    }

    @Test
    fun keepsLongerCaptionWhenRemembering() {
        PayloadProcessor.rememberCaption(9002, "short")
        PayloadProcessor.rememberCaption(9002, "a much longer web caption")
        PayloadProcessor.rememberCaption(9002, "tiny")
        assertEquals("a much longer web caption", PayloadProcessor.lookupCaption(9002))
    }
}
