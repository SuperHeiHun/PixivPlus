package com.heihun.pixivplus.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryPagingTest {

    @Test
    fun `parses local offset and limit from url`() {
        val url = "https://app-api.pixiv.net/v1/user/browsing-history/illusts?plp_offset=60&limit=30"
        assertEquals(60, HistoryPaging.localOffsetOf(url))
        assertEquals(30, HistoryPaging.limitOf(url))
    }

    @Test
    fun `does not treat server offset as local offset`() {
        val url = "https://app-api.pixiv.net/v1/user/browsing-history/illusts?offset=30"
        assertEquals(0, HistoryPaging.localOffsetOf(url))
    }

    @Test
    fun `falls back to defaults for missing or invalid values`() {
        assertEquals(0, HistoryPaging.localOffsetOf(null))
        assertEquals(0, HistoryPaging.localOffsetOf("https://host/path"))
        assertEquals(0, HistoryPaging.localOffsetOf("https://host/path?plp_offset=-5"))
        assertEquals(0, HistoryPaging.localOffsetOf("https://host/path?plp_offset=abc"))

        assertEquals(HistoryPaging.DEFAULT_LIMIT, HistoryPaging.limitOf(null))
        assertEquals(HistoryPaging.DEFAULT_LIMIT, HistoryPaging.limitOf("https://host/path?limit=0"))
        assertEquals(HistoryPaging.DEFAULT_LIMIT, HistoryPaging.limitOf("https://host/path?limit=-1"))
        assertEquals(HistoryPaging.MAX_LIMIT, HistoryPaging.limitOf("https://host/path?limit=500"))
    }

    @Test
    fun `replaces existing local offset and keeps other params`() {
        val url = "https://app-api.pixiv.net/v1/user/browsing-history/illusts?filter=for_android&offset=30&plp_offset=30&limit=30"
        assertEquals(
            "https://app-api.pixiv.net/v1/user/browsing-history/illusts?filter=for_android&offset=30&plp_offset=60&limit=30",
            HistoryPaging.withLocalOffset(url, 60)
        )
    }

    @Test
    fun `appends local offset when absent`() {
        assertEquals(
            "https://app-api.pixiv.net/v1/user/browsing-history/illusts?lang=zh&plp_offset=30",
            HistoryPaging.withLocalOffset("https://app-api.pixiv.net/v1/user/browsing-history/illusts?lang=zh", 30)
        )
        assertEquals(
            "https://app-api.pixiv.net/v1/user/browsing-history/illusts?plp_offset=30",
            HistoryPaging.withLocalOffset("https://app-api.pixiv.net/v1/user/browsing-history/illusts", 30)
        )
    }

    @Test
    fun `keeps fragment and clamps negative offset`() {
        assertEquals(
            "https://host/path?lang=en&plp_offset=0#section",
            HistoryPaging.withLocalOffset("https://host/path?lang=en#section", -5)
        )
    }

    @Test
    fun `returns null for blank url`() {
        assertNull(HistoryPaging.withLocalOffset(null, 30))
        assertNull(HistoryPaging.withLocalOffset("", 30))
    }
}
