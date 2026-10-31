package com.heihun.pixivplus.core.translate

import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.ProxyHttp
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 简介 / 小说翻译。
 *
 * 使用 Google 翻译免费端点（无需密钥），请求沿用模块代理设置；
 * 长文本按段落 / 句子切块串行翻译，结果带内存缓存。
 */
object Translator {

    private const val TAG = "Translator"
    private const val ENDPOINT =
        "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t"
    private const val MAX_CHUNK_CHARS = 3500
    private const val MAX_CACHE_ENTRIES = 400

    data class Target(val code: String, val label: String)

    data class Result(val text: String?, val error: String?)

    val TARGETS = listOf(
        Target("zh-CN", "简体中文"),
        Target("zh-TW", "繁體中文"),
        Target("en", "English"),
        Target("ja", "日本語"),
        Target("ko", "한국어")
    )

    private val cache = ConcurrentHashMap<String, String>()

    fun targetOf(store: ConfigStore): Target {
        val configured = store.get(ConfigKeys.TRANSLATE_TARGET)
        return TARGETS.firstOrNull { it.code == configured } ?: TARGETS[0]
    }

    fun translate(
        store: ConfigStore,
        text: String,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Result {
        if (text.isBlank()) return Result("", null)
        val target = targetOf(store)
        val key = cacheKey(target.code, text)
        cache[key]?.let { return Result(it, null) }

        val chunks = splitChunks(text)
        if (chunks.isEmpty()) return Result("", null)
        val builder = StringBuilder()
        for ((index, chunk) in chunks.withIndex()) {
            onProgress?.invoke(index, chunks.size)
            val translated = requestChunk(store, target.code, chunk)
            if (translated == null) {
                return Result(null, ProxyHttp.lastFailureReason() ?: "翻译请求失败")
            }
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(translated)
        }
        val result = builder.toString()
        if (cache.size > MAX_CACHE_ENTRIES) cache.clear()
        cache[key] = result
        return Result(result, null)
    }

    private fun requestChunk(store: ConfigStore, target: String, chunk: String): String? {
        val body = "q=" + URLEncoder.encode(chunk, "UTF-8")
        val response = try {
            ProxyHttp.request(
                store = store,
                url = URL("$ENDPOINT&tl=$target"),
                method = "POST",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
                body = body.toByteArray(Charsets.UTF_8),
                maxBodyChars = 2 * 1024 * 1024,
                connectTimeoutMs = 8000,
                readTimeoutMs = 20000
            )
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "request failed: ${t.javaClass.simpleName}: ${t.message}")
            return null
        } ?: return null
        if (response.code != 200) {
            ModuleLog.w(TAG, "translate http ${response.code}")
            return null
        }
        val parsed = parseResponse(response.body)
        if (parsed == null) {
            ModuleLog.w(TAG, "translate response parse failed")
        }
        return parsed
    }

    /** 解析 `[[[译文, 原文, ...], ...], ...]` 结构，拼接全部片段。 */
    fun parseResponse(json: String): String? {
        return try {
            val root = JSONArray(json)
            val segments = root.optJSONArray(0) ?: return null
            val builder = StringBuilder()
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONArray(index) ?: continue
                if (segment.isNull(0)) continue
                builder.append(segment.optString(0, ""))
            }
            builder.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 按段落切块；单段超长时优先在句末标点切分，其次硬切。
     * 返回的块之间用换行拼接，保证译文段落结构。
     */
    fun splitChunks(text: String, maxChars: Int = MAX_CHUNK_CHARS): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val result = ArrayList<String>()
        val current = StringBuilder()
        fun pushCurrent() {
            val value = current.toString().trim('\n')
            current.setLength(0)
            if (value.isNotEmpty()) result.add(value)
        }
        for (paragraph in normalized.split('\n')) {
            if (paragraph.length > maxChars) {
                pushCurrent()
                var rest = paragraph
                while (rest.length > maxChars) {
                    var cut = -1
                    for (mark in listOf('。', '！', '？', '!', '?', '.')) {
                        val index = rest.lastIndexOf(mark, maxChars - 1)
                        if (index > cut) cut = index
                    }
                    if (cut < maxChars / 2) cut = maxChars - 1
                    result.add(rest.substring(0, cut + 1))
                    rest = rest.substring(cut + 1)
                }
                if (rest.isNotEmpty()) current.append(rest)
                continue
            }
            if (current.isNotEmpty() && current.length + paragraph.length + 1 > maxChars) {
                pushCurrent()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(paragraph)
        }
        pushCurrent()
        return result
    }

    private fun cacheKey(target: String, text: String): String =
        "$target:${text.length}:${text.hashCode()}"
}
