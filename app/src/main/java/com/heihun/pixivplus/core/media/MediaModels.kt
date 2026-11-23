package com.heihun.pixivplus.core.media

import com.heihun.pixivplus.core.i18n.DownloadText as D
import org.json.JSONObject
import java.net.URI

data class OriginalImage(val page: Int, val url: String, val previewUrl: String? = null) {
    val fileName: String get() = URI(url).path.substringAfterLast('/')
    val mime: String get() = when (fileName.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
}

data class UgoiraFrame(val file: String, val delayMs: Int)
data class UgoiraMetadata(val zipUrl: String, val frames: List<UgoiraFrame>)

data class MediaInfo(val id: Long, val author: String?, val title: String?, val authorId: Long?) {
    fun linksText(): String = buildString {
        append("作者：").append(author.orEmpty()).append('\n')
        append("作者链接：").append(authorId?.takeIf { it > 0 }?.let { "https://www.pixiv.net/users/$it" } ?: "未获取到").append('\n')
        append("标题：").append(title.orEmpty()).append('\n')
        append("作品链接：https://www.pixiv.net/artworks/").append(id).append('\n')
    }
}

object MediaModels {
    /** 仅接受 Pixiv 图片服务器的 HTTPS 原图 / 动图地址。 */
    fun requireImageUrl(value: String): String {
        val uri = URI(value)
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && (host == "pximg.net" || host.endsWith(".pximg.net"))) { D.BAD_URL() }
        require(uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { D.BAD_URL() }
        require(uri.path.substringAfterLast('/').matches(Regex("[A-Za-z0-9_.-]+"))) { D.BAD_IMAGE_NAME() }
        return value
    }

    /** 自选页码使用 1 起始，支持 1,3,5-8；去重并按原顺序保存。 */
    fun selectPages(input: String, total: Int): List<Int> {
        require(total in 1..1000) { D.IMAGE_COUNT_LIMIT() }
        require(input.isNotBlank()) { D.ENTER_PAGES() }
        val pages = sortedSetOf<Int>()
        for (part in input.replace('，', ',').split(',')) {
            val match = Regex("\\s*(\\d+)\\s*(?:-\\s*(\\d+)\\s*)?").matchEntire(part)
                ?: throw IllegalArgumentException(D.BAD_PAGE_FORMAT())
            val start = match.groupValues[1].toIntOrNull() ?: 0
            val end = match.groupValues[2].ifEmpty { match.groupValues[1] }.toIntOrNull() ?: 0
            require(start in 1..total && end in start..total) { D.BAD_PAGE_RANGE(total) }
            pages.addAll(start..end)
        }
        return pages.toList()
    }

    fun originals(json: JSONObject): List<OriginalImage> {
        val illust = json.optJSONObject("illust") ?: json
        val count = illust.optInt("page_count", 1)
        require(count in 1..1000) { D.IMAGE_COUNT_LIMIT() }
        val pages = illust.optJSONArray("meta_pages")
        if (count == 1) {
            val single = illust.optJSONObject("meta_single_page")?.optString("original_image_url").orEmpty()
            val url = single.ifEmpty { pages?.optJSONObject(0)?.optJSONObject("image_urls")?.optString("original").orEmpty() }
            require(url.isNotBlank()) { D.NO_ORIGINAL_URL() }
            val preview = preview(illust.optJSONObject("image_urls")) ?: preview(pages?.optJSONObject(0)?.optJSONObject("image_urls"))
            return listOf(OriginalImage(1, requireImageUrl(url), preview))
        }
        require(pages != null && pages.length() == count) { D.MISSING_PAGES() }
        return (0 until count).map { page ->
            val url = pages!!.optJSONObject(page)?.optJSONObject("image_urls")?.optString("original").orEmpty()
            require(url.isNotBlank()) { D.PAGE_NO_ORIGINAL_URL(page + 1) }
            OriginalImage(page + 1, requireImageUrl(url), preview(pages.optJSONObject(page)?.optJSONObject("image_urls")))
        }
    }

    private fun preview(urls: JSONObject?): String? =
        urls?.optString("medium")?.takeIf { it.isNotBlank() } ?: urls?.optString("large")?.takeIf { it.isNotBlank() }

    fun ugoira(json: JSONObject): UgoiraMetadata {
        val metadata = json.optJSONObject("ugoira_metadata") ?: throw IllegalArgumentException(D.MISSING_UGOIRA_DATA())
        val urls = metadata.optJSONObject("zip_urls") ?: throw IllegalArgumentException(D.MISSING_UGOIRA_ZIP())
        val url = urls.optString("original").ifBlank { urls.optString("medium") }
        require(url.isNotBlank()) { D.MISSING_UGOIRA_ZIP() }
        val frames = metadata.optJSONArray("frames") ?: throw IllegalArgumentException(D.MISSING_FRAMES())
        require(frames.length() in 1..2000) { D.FRAME_COUNT_LIMIT() }
        val names = HashSet<String>()
        val parsed = (0 until frames.length()).map { index ->
            val frame = frames.getJSONObject(index)
            val file = frame.getString("file")
            val delay = frame.getInt("delay")
            require(file.matches(Regex("[A-Za-z0-9_.-]+")) && file != "." && file != ".." && names.add(file)) { D.BAD_FRAME_NAME() }
            require(delay in 1..655350) { D.BAD_FRAME_DELAY() }
            UgoiraFrame(file, delay)
        }
        return UgoiraMetadata(requireImageUrl(url), parsed)
    }
}
