package com.heihun.pixivplus.core.media

import com.heihun.pixivplus.core.i18n.DownloadText as D
import org.json.JSONArray
import org.json.JSONObject

enum class DownloadFormat { IMAGES, ZIP, GIF }
/** 历史只保存重试参数，不保存登录令牌或全部图片 URL；重试重新获取地址。 */
data class DownloadPlan(
    val workId: Long, val stem: String, val folder: String, val format: DownloadFormat,
    val pages: List<Int> = emptyList(), val linksText: String? = null,
    val cacheKey: String = java.util.UUID.randomUUID().toString()
) {
    fun encode(): String = JSONObject().put("work", workId).put("stem", stem).put("folder", folder)
        .put("format", format.name).put("pages", JSONArray(pages)).put("links", linksText).put("cache", cacheKey).toString()
    companion object {
        fun decode(text: String): DownloadPlan {
            val json = JSONObject(text)
            val pages = json.getJSONArray("pages")
            val plan = DownloadPlan(json.getLong("work"), json.getString("stem"), json.getString("folder"),
                DownloadFormat.valueOf(json.getString("format")), (0 until pages.length()).map { pages.getInt(it) },
                if (json.isNull("links")) null else json.getString("links"), json.getString("cache"))
            require(plan.workId > 0 && plan.stem.isNotBlank() && plan.stem.toByteArray().size <= 200 &&
                plan.stem.none { it == '/' || it == '\\' || it == '\u0000' } && plan.stem != "." && plan.stem != "..") { D.BAD_PLAN_NAME() }
            require((plan.folder.startsWith("content://") || plan.folder == OutputPolicy.DEFAULT_FOLDER) &&
                plan.cacheKey.matches(Regex("[a-fA-F0-9-]{36}"))) { D.BAD_PLAN_LOCATION() }
            require(plan.pages.size <= 1000 && plan.pages.all { it in 1..1000 } && plan.pages.distinct().size == plan.pages.size) { D.BAD_PLAN_PAGES() }
            require(plan.format == DownloadFormat.GIF || plan.pages.isNotEmpty()) { D.NO_PLAN_PAGES() }
            return plan
        }
    }
}
