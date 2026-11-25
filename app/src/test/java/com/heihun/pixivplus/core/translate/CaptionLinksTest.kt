package com.heihun.pixivplus.core.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionLinksTest {

    @Test
    fun `finds ilust reference alias`() {
        val links = CaptionLinks.find("ilust/123")
        assertEquals(1, links.size)
        assertEquals("pixiv://illusts/123", links[0].deepLink)
    }

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

    @Test fun `named html anchor target keeps its own text range`() {
        val link = CaptionLinks.fromUrl("https://example.com/details", 5, 9)!!
        assertEquals(5, link.start)
        assertEquals(9, link.end)
        assertEquals("https://example.com/details", link.webUrl)
    }

    @Test fun `relative and localized pixiv anchors prefer host deep links`() {
        assertEquals("pixiv://illusts/123", CaptionLinks.fromUrl("/en/artworks/123", 0, 4)!!.deepLink)
        assertEquals("pixiv://users/42", CaptionLinks.fromUrl("//www.pixiv.net/users/42", 0, 4)!!.deepLink)
        assertEquals("pixiv://novels/77", CaptionLinks.fromUrl("https://www.pixiv.net/novel/show.php?lang=en&id=77&v=1", 0, 4)!!.deepLink)
        assertEquals("pixiv://illusts/8", CaptionLinks.fromUrl("illust/8", 0, 4)!!.deepLink)
        assertEquals("https://www.pixiv.net/novel/show.php?id=77", CaptionLinks.fromUrl("pixiv://novels/77", 0, 4)!!.webUrl)
    }

    @Test fun `lookalike hosts userinfo query paths and nonstandard ports never become pixiv deep links`() {
        for (url in listOf("https://pixiv.net.evil.test/artworks/999", "https://evilpixiv.net/artworks/123",
            "https://www.pixiv.net@evil.test/artworks/123", "https://example.com/?next=https://www.pixiv.net/artworks/999",
            "https://www.pixiv.net:8443/artworks/123")) {
            val links = CaptionLinks.find(url)
            assertEquals(url, 1, links.size)
            assertEquals(url, url, links.single().deepLink)
        }
    }

    @Test fun `unsupported html href schemes malformed paths and unknown pixiv routes are rejected`() {
        for (url in listOf("javascript:alert(1)", "file:///sdcard/a", "content://private/item", "intent://login",
            "data:text/html,hi", "mailto:test@example.com", "pixiv://unknown/123", "/bad url")) {
            assertNull(url, CaptionLinks.fromUrl(url, 0, 4))
        }
    }

    @Test fun `host clickable spans and html anchors keep priority while adjacent links are allowed`() {
        val a = CaptionLinks.Link(0, 10, "https://example.com", "https://example.com")
        val b = a.copy(start = 10, end = 20)
        assertEquals(listOf(b), CaptionLinks.excluding(listOf(a, b), listOf(CaptionLinks.Range(0, 10))))
        assertEquals(listOf(b), CaptionLinks.excluding(listOf(a, b), listOf(CaptionLinks.Range(4, 8))))
        assertEquals(listOf(a, b), CaptionLinks.excluding(listOf(a, b), emptyList()))
    }

    @Test fun `uppercase web schemes are clickable and zero or overflowing plain ids are ignored`() {
        assertEquals("pixiv://illusts/123", CaptionLinks.find("HTTPS://WWW.PIXIV.NET/artworks/123").single().deepLink)
        assertTrue(CaptionLinks.find("illust/0 novel/999999999999999999999999999999").isEmpty())
        assertTrue(CaptionLinks.find("evilpixiv.net/novel/show.php?id=123").isEmpty())
        assertTrue(CaptionLinks.find("evilpixiv.net/artworks/123 example.com/users/42").isEmpty())
        assertEquals("pixiv://illusts/42", CaptionLinks.find("pixiv://illusts/42").single().deepLink)
    }
}
