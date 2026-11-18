package com.heihun.pixivplus.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNamesTest {
    @Test fun `repeated placeholders are substituted without interpreting author text as template`() {
        assertEquals("{id}_标题_123_123", MediaNames.stem("{id}", "标题", 123, "{author}_{title}_{id}_{id}"))
    }
    @Test(expected = IllegalArgumentException::class) fun `stem rejects corrupt saved template`() {
        MediaNames.stem("作者", "标题", 123, "{invalid}")
    }
    @Test fun `custom templates support id and fixed literals`() {
        assertEquals("123-标题-作者", MediaNames.stem("作者", "标题", 123, "{id}-{title}-{author}"))
        assertEquals("自定义名称", MediaNames.stem("作者", "标题", 123, "自定义名称"))
    }
    @Test(expected = IllegalArgumentException::class) fun `invalid template rejected`() {
        MediaNames.validateTemplate("{page}")
    }
    @Test fun `metadata contains correct author and work links`() {
        val info = MediaInfo(123, "作者", "标题", 456).linksText()
        assertTrue(info.contains("https://www.pixiv.net/users/456"))
        assertTrue(info.contains("https://www.pixiv.net/artworks/123"))
        assertTrue(!MediaInfo(123, "作者", "标题", null).linksText().contains("users/null"))
    }
    @Test fun `names use author and title with one based page numbers`() {
        val name = MediaNames.stem("作者", "标题", 123)
        assertEquals("作者_标题", name)
        assertEquals("作者_标题_p3.png", MediaNames.image(name, OriginalImage(3, "https://i.pximg.net/a.PNG")))
    }
    @Test fun `names sanitize separators control characters and trailing dots`() {
        assertEquals("A_B_C_D", MediaNames.stem("A/B", "C:D...", 123))
        assertEquals("作者_标题", MediaNames.stem(" 作者 ", " 标题...", 123))
        assertTrue(!MediaNames.stem("a\n", "b\u0000", 123).contains('\u0000'))
    }
    @Test fun `missing names have safe fallbacks`() {
        assertEquals("未知作者_作品123", MediaNames.stem(null, " ", 123))
    }
    @Test fun `unicode names fit byte limits without cutting surrogate pairs`() {
        val name = MediaNames.stem("作".repeat(100), "😀".repeat(100), 123)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 181)
        assertTrue(name.endsWith("😀"))
    }
}
