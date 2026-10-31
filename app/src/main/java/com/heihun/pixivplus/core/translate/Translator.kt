package com.heihun.pixivplus.core.translate

import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.ProxyHttp
import org.json.JSONArray
import java.net.HttpURLConnection
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
    private const val MODULE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

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

    /** 最近一次翻译失败的详细原因（模块栈 + 宿主栈），供 UI 展示。 */
    @Volatile
    var lastError: String? = null
        private set

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
                return Result(null, lastError ?: "翻译请求失败")
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
        val errors = ArrayList<String>()

        // 1) 模块网络栈（含 SOCKS 认证等自建能力）。
        try {
            val response = ProxyHttp.request(
                store = store,
                url = URL("$ENDPOINT&tl=$target"),
                method = "POST",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
                body = body.toByteArray(Charsets.UTF_8),
                maxBodyChars = 2 * 1024 * 1024,
                connectTimeoutMs = 8000,
                readTimeoutMs = 20000
            )
            if (response != null && response.code == 200) {
                parseResponse(response.body)?.let { return it }
                errors += "模块栈响应解析失败"
                ModuleLog.w(TAG, "module translate parse failed")
            } else {
                val reason = response?.let { "HTTP ${it.code}" }
                    ?: (ProxyHttp.lastFailureReason() ?: "请求失败")
                errors += "模块栈：$reason"
                ModuleLog.w(TAG, "module translate failed: $reason")
            }
        } catch (t: Throwable) {
            errors += "模块栈：${t.message ?: t.javaClass.simpleName}"
            ModuleLog.w(TAG, "module translate exception: ${t.javaClass.simpleName}: ${t.message}")
        }

        // 2) 宿主网络栈（自动使用系统代理 / App 代理选择器）。
        requestChunkHost(target, body)?.let { return it }

        lastError = (errors + "宿主栈失败").joinToString("；") +
            "。请在「网络代理」配置可用代理，或开启「让 Pixiv App 也走此代理」后重试"
        return null
    }

    private fun requestChunkHost(target: String, formBody: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("$ENDPOINT&tl=$target").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8"
            )
            connection.setRequestProperty("User-Agent", MODULE_UA)
            connection.outputStream.use { output ->
                output.write(formBody.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            if (connection.responseCode !in 200..299) {
                ModuleLog.w(TAG, "host translate http ${connection.responseCode}")
                return null
            }
            val text = connection.inputStream.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
            parseResponse(text) ?: run {
                ModuleLog.w(TAG, "host translate parse failed")
                null
            }
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "host translate failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
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
