package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.PageImage
import org.json.JSONArray
import org.json.JSONObject

object PayloadProcessor {

    private const val LIST_KEY_ILLUSTS = "illusts"
    private const val LIST_KEY_NOVELS = "novels"
    private const val LIST_KEY_CONTENTS = "contents"
    private const val DETAIL_KEY_ILLUST = "illust"
    private const val DETAIL_KEY_NOVEL = "novel"

    @Volatile
    var rules: FilterRules = FilterRules.EMPTY

    @Volatile
    var historyEnabled: Boolean = false

    @Volatile
    var premiumEnabled: Boolean = false

    @Volatile
    var detailSink: ((JSONObject, String) -> Unit)? = null

    @Volatile
    var authorSink: ((Long, String?, String?, String?) -> Unit)? = null

    private val counters = java.util.concurrent.atomic.AtomicLong(0)
    private val filteredCount = java.util.concurrent.atomic.AtomicLong(0)
    private val workIndex = java.util.concurrent.ConcurrentHashMap<Long, WorkInfo>()
    private val authorIndex = java.util.concurrent.ConcurrentHashMap<Long, Pair<String?, String?>>()

    data class WorkInfo(
        val id: Long,
        val type: String,
        val title: String?,
        val caption: String?,
        val tags: List<String>,
        val authorId: Long?,
        val authorName: String?,
        val thumbnail: String?,
        val pageCount: Int,
        val authorAccount: String? = null,
        val avatarUrl: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val originalUrl: String? = null,
        val pageImages: List<PageImage> = emptyList()
    )

    fun lookupWork(id: Long): WorkInfo? = workIndex[id]

    fun workIndexSize(): Int = workIndex.size

    fun process(text: String): String? {
        if (text.isEmpty()) return null
        val needFilter = rules.hasRules && (
            text.contains("\"$LIST_KEY_ILLUSTS\"") ||
                text.contains("\"$LIST_KEY_NOVELS\"") ||
                text.contains("\"$LIST_KEY_CONTENTS\"")
            )

        val needHistory = historyEnabled && (text.contains("\"$DETAIL_KEY_ILLUST\":") || text.contains("\"$DETAIL_KEY_NOVEL\":"))
        val needPremium = premiumEnabled && text.contains("\"is_premium\"")
        val needIndex = (historyEnabled || authorSink != null) && (
            text.contains("\"$LIST_KEY_ILLUSTS\"") ||
                text.contains("\"$LIST_KEY_NOVELS\"") ||
                text.contains("\"$LIST_KEY_CONTENTS\"")
            )
        if (!needFilter && !needHistory && !needPremium && !needIndex) return null

        return try {
            val root = parseRoot(text) ?: return null
            var changed = false
            if (needFilter) {
                changed = filterTree(root) || changed
            }
            if (needIndex) {
                indexWorks(root)
            }
            if (needHistory) {
                extractDetail(root)
            }
            if (needPremium) {
                changed = forcePremium(root) || changed
            }
            val processed = counters.incrementAndGet()
            if (processed <= 3L || processed % 10L == 0L) {
                ModuleLog.i("PayloadProcessor", "processed $processed payloads, filtered ${filteredCount.get()} item(s)")
            }
            if (changed) root.toString() else null
        } catch (t: Throwable) {
            ModuleLog.w("PayloadProcessor", "process failed", t)
            null
        }
    }

    fun processedCount(): Long = counters.get()

    fun filteredCount(): Long = filteredCount.get()

    fun resetCounters() {
        counters.set(0L)
        filteredCount.set(0L)
    }

    private fun parseRoot(text: String): Any? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return if (trimmed.startsWith("[")) JSONArray(text) else JSONObject(text)
    }

    private fun filterTree(node: Any): Boolean {
        var changed = false
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (value is JSONArray && isListKey(key)) {
                        changed = filterArray(value) || changed
                    } else if (value != null) {
                        changed = filterTree(value) || changed
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    val value = node.opt(index)
                    if (value != null) changed = filterTree(value) || changed
                }
            }
        }
        return changed
    }

    private fun isListKey(key: String): Boolean {
        return key == LIST_KEY_ILLUSTS || key == LIST_KEY_NOVELS || key == LIST_KEY_CONTENTS
    }

    private fun filterArray(array: JSONArray): Boolean {
        var changed = false
        var index = array.length() - 1
        while (index >= 0) {
            val item = array.opt(index)
            if (item is JSONObject && matchesItem(item)) {
                array.remove(index)
                changed = true
                filteredCount.incrementAndGet()
            }
            index--
        }
        if (changed) {
            ModuleLog.i("PayloadProcessor", "filtered ${array.length()} remaining items, rules=${rules.describe()}")
        }
        return changed
    }

    private fun matchesItem(item: JSONObject): Boolean {
        if (item.has("thumbnails")) return rules.matchesContent(item)
        return rules.matches(item)
    }

    private fun forcePremium(node: Any): Boolean {
        var changed = false
        when (node) {
            is JSONObject -> {
                if (node.has("is_premium") && !node.optBoolean("is_premium", false)) {
                    node.put("is_premium", true)
                    changed = true
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val value = node.opt(keys.next())
                    if (value != null) changed = forcePremium(value) || changed
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    val value = node.opt(index)
                    if (value != null) changed = forcePremium(value) || changed
                }
            }
        }
        return changed
    }

    private fun extractDetail(root: Any) {
        if (root !is JSONObject) return
        val sink = detailSink ?: return
        val illust = root.optJSONObject(DETAIL_KEY_ILLUST)
        if (illust != null && looksLikeWork(illust)) {
            sink(illust, illust.optString("type", "illust"))
            return
        }
        val novel = root.optJSONObject(DETAIL_KEY_NOVEL)
        if (novel != null && looksLikeWork(novel)) {
            sink(novel, "novel")
        }
    }

    private fun looksLikeWork(item: JSONObject): Boolean {
        if (!item.has("id")) return false
        return item.has("image_urls") || item.has("page_count")
    }

    private fun indexWorks(root: Any?) {
        when (root) {
            is JSONObject -> {
                root.optJSONArray(LIST_KEY_CONTENTS)?.let { array ->
                    for (index in 0 until array.length()) {
                        val entry = array.optJSONObject(index) ?: continue
                        val thumbnails = entry.optJSONArray("thumbnails") ?: continue
                        for (itemIndex in 0 until thumbnails.length()) {
                            indexWork(thumbnails.optJSONObject(itemIndex))
                        }
                    }
                }
                root.optJSONArray(LIST_KEY_ILLUSTS)?.let { array ->
                    for (index in 0 until array.length()) indexWork(array.optJSONObject(index))
                }
                root.optJSONArray(LIST_KEY_NOVELS)?.let { array ->
                    for (index in 0 until array.length()) indexWork(array.optJSONObject(index))
                }
            }
            is JSONArray -> {
                for (index in 0 until root.length()) indexWork(root.optJSONObject(index))
            }
        }
    }

    private fun indexWork(item: JSONObject?) {
        if (item == null) return
        val model = item.optJSONObject("app_model") ?: item
        val id = item.optLong("id", model.optLong("id", 0L))
        if (id <= 0L) return
        val rawType = item.optString("type", model.optString("type", "illust"))
        val type = when (rawType.lowercase()) {
            "manga" -> "MANGA"
            "novel" -> "NOVEL"
            else -> "ILLUST"
        }
        val title = item.optString("title").ifEmpty { model.optString("title") }.ifEmpty { null }
        val caption = item.optString("description").ifEmpty { item.optString("caption") }
            .ifEmpty { model.optString("caption") }.ifEmpty { null }
        val tags = collectTags(item.optJSONArray("tags")).ifEmpty { collectTags(model.optJSONArray("tags")) }
        val user = item.optJSONObject("user") ?: model.optJSONObject("user")
        val authorId = item.optLong("user_id", 0L).takeIf { it > 0L }
            ?: user?.optLong("id", 0L)?.takeIf { it > 0L }
        val authorName = item.optString("user_name").ifEmpty { user?.optString("name").orEmpty() }.ifEmpty { null }
        val authorAccount = user?.optString("account")?.ifEmpty { null }
        val avatar = user?.optJSONObject("profile_image_urls")?.optString("medium")?.ifEmpty { null }
        val existing = workIndex[id]
        if (authorId != null && (authorAccount != null || avatar != null)) {
            val previous = authorIndex[authorId]
            if (previous == null || previous.first != authorAccount || previous.second != avatar) {
                authorIndex[authorId] = authorAccount to avatar
                try {
                    authorSink?.invoke(authorId, authorName, authorAccount, avatar)
                } catch (_: Throwable) {
                }
            }
        }
        val thumbnail = pickThumbnail(item) ?: pickThumbnail(model)
        val pageCount = item.optInt("page_count", model.optInt("page_count", 0))
        val width = item.optInt("width", model.optInt("width", 0))
        val height = item.optInt("height", model.optInt("height", 0))
        val originalUrl = singlePageUrl(item) ?: singlePageUrl(model)
        val pageImages = PageImageCodec.fromJson(
            item.optJSONArray("meta_pages") ?: model.optJSONArray("meta_pages")
        )
        if (workIndex.size > 2000) workIndex.clear()
        workIndex[id] = WorkInfo(
            id = id,
            type = type,
            title = title,
            caption = caption,
            tags = tags,
            authorId = authorId,
            authorName = authorName,
            thumbnail = thumbnail,
            pageCount = pageCount,
            authorAccount = authorAccount ?: existing?.authorAccount,
            avatarUrl = avatar ?: existing?.avatarUrl,
            width = if (width > 0) width else (existing?.width ?: 0),
            height = if (height > 0) height else (existing?.height ?: 0),
            originalUrl = originalUrl ?: existing?.originalUrl,
            pageImages = pageImages.ifEmpty { existing?.pageImages.orEmpty() }
        )
    }

    private fun singlePageUrl(item: JSONObject): String? {
        val holder = item.optJSONObject("meta_single_page") ?: return null
        return PageImageCodec.stringOrNull(holder, "original_image_url")
    }

    private fun collectTags(array: JSONArray?): List<String> {
        if (array == null || array.length() == 0) return emptyList()
        val names = ArrayList<String>()
        for (index in 0 until array.length()) {
            val tag = array.optJSONObject(index) ?: continue
            val name = tag.optString("name")
            if (name.isNotEmpty() && !names.contains(name)) names.add(name)
        }
        return names
    }

    private fun pickThumbnail(item: JSONObject): String? {
        for (holder in listOf("image_urls", "cover_image_urls")) {
            val urls = item.optJSONObject(holder) ?: continue
            for (name in listOf("medium", "square_medium", "large")) {
                val value = urls.optString(name)
                if (value.isNotEmpty()) return value
            }
        }
        val pages = item.optJSONArray("pages") ?: return null
        for (index in 0 until pages.length()) {
            val page = pages.optJSONObject(index) ?: continue
            val urls = page.optJSONObject("urls") ?: continue
            val keys = urls.keys()
            var fallback: String? = null
            while (keys.hasNext()) {
                val key = keys.next()
                val value = urls.optString(key)
                if (value.isEmpty()) continue
                if (key.contains("360")) return value
                if (fallback == null) fallback = value
            }
            if (fallback != null) return fallback
        }
        return null
    }
}
