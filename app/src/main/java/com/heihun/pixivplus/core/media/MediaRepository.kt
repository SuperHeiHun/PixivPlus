package com.heihun.pixivplus.core.media

import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.net.ApiHooks
import com.heihun.pixivplus.core.net.ProxyHttp
import com.heihun.pixivplus.core.util.EntityReader
import com.heihun.pixivplus.core.util.ReflectUtil
import org.json.JSONObject
import java.io.File
import java.net.URL

/** 原图地址只读取 original 字段，缺失时借用宿主登录态请求详情，不猜测缩略图路径。 */
object MediaRepository {
    fun info(store: ConfigStore, id: Long, entity: Any?): MediaInfo {
        val author = entity?.let { EntityReader.authorName(it) }
        val title = entity?.let { EntityReader.title(it) }
        val authorId = entity?.let { EntityReader.authorId(it) }
        if (!author.isNullOrBlank() && !title.isNullOrBlank() && authorId != null && authorId > 0) return MediaInfo(id, author, title, authorId)
        val illust = api(store, "/v1/illust/detail?illust_id=$id").getJSONObject("illust")
        val user = illust.optJSONObject("user")
        return MediaInfo(id, user?.optString("name"), illust.optString("title"), user?.optLong("id")?.takeIf { it > 0 })
    }

    fun originals(store: ConfigStore, id: Long, entity: Any?): List<OriginalImage> {
        if (entity != null) {
            val count = EntityReader.pageCount(entity)
            // Jadx 会把混淆 getter 显示为易读名称；运行时优先读取真实字段。
            val pages = (ReflectUtil.readField(entity, "metaPages") as? List<*>)?.map { page ->
                val urls = ReflectUtil.readField(page, "imageUrls")
                (ReflectUtil.readField(urls, "original") as? String) to previewOf(urls)
            }.orEmpty()
            if (count == 1) {
                val single = ReflectUtil.readField(entity, "metaSinglePage")
                val original = (ReflectUtil.readField(single, "originalImageUrl") as? String)?.takeIf { it.isNotBlank() }
                    ?: pages.firstOrNull()?.first
                val preview = previewOf(ReflectUtil.readField(entity, "imageUrls")) ?: pages.firstOrNull()?.second
                if (!original.isNullOrBlank()) return listOf(OriginalImage(1, MediaModels.requireImageUrl(original), preview))
            } else if (count in 2..1000 && pages.size == count && pages.all { !it.first.isNullOrBlank() }) {
                return pages.mapIndexed { index, urls -> OriginalImage(index + 1, MediaModels.requireImageUrl(urls.first!!), urls.second) }
            }
        }
        return MediaModels.originals(api(store, "/v1/illust/detail?illust_id=$id"))
    }

    fun ugoira(store: ConfigStore, id: Long): UgoiraMetadata =
        MediaModels.ugoira(api(store, "/v1/ugoira/metadata?illust_id=$id"))

    private fun previewOf(urls: Any?): String? =
        (ReflectUtil.readField(urls, "medium") as? String)?.takeIf { it.isNotBlank() }
            ?: (ReflectUtil.readField(urls, "large") as? String)?.takeIf { it.isNotBlank() }

    private fun api(store: ConfigStore, path: String): JSONObject {
        val token = ApiHooks.authorizationToken()
            ?: throw IllegalStateException("未获取到 Pixiv 登录态，请先加载作品详情后重试")
        val response = ProxyHttp.get(store, URL("https://app-api.pixiv.net$path"), headers = mapOf(
            "Authorization" to token,
            "User-Agent" to "PixivAndroidApp/6.192.1 (Android 11; PixivPlus)",
            "App-OS" to "android", "App-OS-Version" to "11", "App-Version" to "6.192.1"
        )) ?: throw IllegalStateException("获取图片信息失败，请检查网络代理")
        check(response.code == 200) { "获取图片信息失败：HTTP ${response.code}" }
        return JSONObject(response.body)
    }

    /** 下载单个文件到本次任务的临时目录，限制单文件大小，避免无界内存占用。 */
    fun download(store: ConfigStore, url: String, destination: File, maxBytes: Int = 64 * 1024 * 1024) {
        com.heihun.pixivplus.core.task.TaskExecution.check()
        MediaModels.requireImageUrl(url)
        ProxyHttp.downloadFile(store, URL(url), destination, maxBytes.toLong()) { done, total ->
            if (total > 0) com.heihun.pixivplus.core.task.TaskExecution.progress("正在下载 ${destination.name}… ($done/$total)")
            else com.heihun.pixivplus.core.task.TaskExecution.progress("正在下载 ${destination.name}… 已接收 $done 字节")
        }
    }
}
