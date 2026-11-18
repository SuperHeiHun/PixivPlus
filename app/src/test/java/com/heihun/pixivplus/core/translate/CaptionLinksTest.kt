package com.heihun.pixivplus.core.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionLinksTest {

    @Test
    fun `finds plain illust reference`() {
        val text = "前作 illust/123 と関連しています"
        val links = CaptionLinks.find(text)
        assertEquals(1, links.size)
        val link = links[0]
        assertEquals("illust/123", text.substring(link.start, link.end))
        assertEquals("pixiv://illusts/123", link.deepLink)
        assertEquals("https://www.pixiv.net/artworks/123", link.webUrl)
    }

    @Test
    fun `finds artworks reference with fullwidth slash`() {
        val links = CaptionLinks.find("新作 →artworks／98765")
        assertEquals(1, links.size)
        assertEquals("pixiv://illusts/98765", links[0].deepLink)
    }

    @Test
    fun `finds user and novel references`() {
        val user = CaptionLinks.find("by user/42")
        assertEquals(1, user.size)
        assertEquals("pixiv://users/42", user[0].deepLink)

        val novel = CaptionLinks.find("続きは novel/77 です")
        assertEquals(1, novel.size)
        assertEquals("pixiv://novels/77", novel[0].deepLink)
        assertEquals("https://www.pixiv.net/novel/show.php?id=77", novel[0].webUrl)
    }

    @Test
    fun `full pixiv url becomes single link with deep link`() {
        val text = "参考 https://www.pixiv.net/artworks/456 です"
        val links = CaptionLinks.find(text)
        assertEquals(1, links.size)
        val link = links[0]
        assertEquals("https://www.pixiv.net/artworks/456", text.substring(link.start, link.end))
        assertEquals("pixiv://illusts/456", link.deepLink)
    }

    @Test
    fun `novel show url maps to novel deep link`() {
        val links = CaptionLinks.find("https://www.pixiv.net/novel/show.php?id=12345")
        assertEquals(1, links.size)
        assertEquals("pixiv://novels/12345", links[0].deepLink)
    }

    @Test
    fun `generic url keeps original address and strips trailing punctuation`() {
        val text = "詳しくは https://example.com/a(b)。を参照"
        val links = CaptionLinks.find(text)
        assertEquals(1, links.size)
        val link = links[0]
        assertEquals("https://example.com/a(b)", text.substring(link.start, link.end))
        assertEquals("https://example.com/a(b)", link.deepLink)
        assertEquals("https://example.com/a(b)", link.webUrl)
    }

    @Test
    fun `multiple links are sorted by position`() {
        val text = "illust/1 と user/2 と https://example.com"
        val links = CaptionLinks.find(text)
        assertEquals(3, links.size)
        assertTrue(links.zipWithNext().all { (a, b) -> a.start < b.start })
        assertEquals("pixiv://illusts/1", links[0].deepLink)
        assertEquals("pixiv://users/2", links[1].deepLink)
    }

    @Test
    fun `text without references returns empty`() {
        assertEquals(0, CaptionLinks.find("これは普通の文章です。").size)
        assertEquals(0, CaptionLinks.find("short").size)
    }

    @Test
    fun `non pixiv domain is not upgraded to deep link`() {
        val links = CaptionLinks.find("see https://example.com/illust/999/page")
        assertEquals(1, links.size)
        assertEquals("https://example.com/illust/999/page", links[0].deepLink)
    }
}
