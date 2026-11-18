package com.heihun.pixivplus.core.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaModelsTest {
    @Test fun `single preview is separate from original`() {
        val image = MediaModels.originals(JSONObject("""{"page_count":1,"meta_single_page":{"original_image_url":"https://i.pximg.net/a.png"},"image_urls":{"medium":"https://i.pximg.net/preview.jpg"}}""")).single()
        assertEquals("https://i.pximg.net/a.png", image.url)
        assertEquals("https://i.pximg.net/preview.jpg", image.previewUrl)
    }
    @Test fun `multipage previews preserve page correspondence`() {
        val images = MediaModels.originals(JSONObject("""{"page_count":2,"meta_pages":[{"image_urls":{"original":"https://i.pximg.net/a_p0.png","medium":"https://i.pximg.net/p0.jpg"}},{"image_urls":{"original":"https://i.pximg.net/a_p1.png","large":"https://i.pximg.net/p1.jpg"}}]}"""))
        assertEquals(listOf(1, 2), images.map { it.page })
        assertEquals(listOf("https://i.pximg.net/p0.jpg", "https://i.pximg.net/p1.jpg"), images.map { it.previewUrl })
    }
    @Test fun `selection supports ranges duplicates and fullwidth commas`() {
        assertEquals(listOf(1, 3, 5, 6, 7, 8), MediaModels.selectPages("8,1，3,5-8", 10))
    }
    @Test(expected = IllegalArgumentException::class) fun `selection rejects out of range`() {
        MediaModels.selectPages("1-2000000000", 10)
    }
    @Test(expected = IllegalArgumentException::class) fun `selection rejects reversed range`() {
        MediaModels.selectPages("3-1", 10)
    }
    @Test(expected = IllegalArgumentException::class) fun `selection rejects empty segments`() {
        MediaModels.selectPages("1,,2", 10)
    }
    @Test fun `single image uses original not thumbnail`() {
        val images = MediaModels.originals(JSONObject("""{"page_count":1,"meta_single_page":{"original_image_url":"https://i.pximg.net/a/123_p0.png"}}"""))
        assertEquals("123_p0.png", images.single().fileName)
        assertEquals("image/png", images.single().mime)
    }
    @Test(expected = IllegalArgumentException::class) fun `missing original fails instead of saving preview`() {
        MediaModels.originals(JSONObject("""{"page_count":1,"image_urls":{"large":"https://i.pximg.net/preview.jpg"}}"""))
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects non pixiv image host`() {
        MediaModels.requireImageUrl("https://i.pximg.net.example.com/a.jpg")
    }
    @Test fun `ugoira preserves frame order and timing`() {
        val data = MediaModels.ugoira(JSONObject("""{"ugoira_metadata":{"zip_urls":{"medium":"https://i.pximg.net/123.zip"},"frames":[{"file":"000001.jpg","delay":60},{"file":"000000.jpg","delay":100}]}}"""))
        assertEquals(listOf("000001.jpg", "000000.jpg"), data.frames.map { it.file })
        assertEquals(listOf(60, 100), data.frames.map { it.delayMs })
    }
    @Test(expected = IllegalArgumentException::class) fun `ugoira rejects path traversal`() {
        MediaModels.ugoira(JSONObject("""{"ugoira_metadata":{"zip_urls":{"medium":"https://i.pximg.net/123.zip"},"frames":[{"file":"../bad.jpg","delay":60}]}}"""))
    }
}
