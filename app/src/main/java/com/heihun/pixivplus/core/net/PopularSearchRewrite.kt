package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

object PopularSearchRewrite {

    private const val TAG = "PopularRewrite"
    private const val REQUEST_BUILDER_CLASS = "okhttp3.Request\$Builder"
    private const val HTTP_URL_CLASS = "okhttp3.HttpUrl"

    private const val SEARCH_ILLUST_PATH = "/v1/search/illust"
    private const val SEARCH_NOVEL_PATH = "/v1/search/novel"
    private const val PREVIEW_ILLUST_PATH = "/v1/search/popular-preview/illust"
    private const val PREVIEW_NOVEL_PATH = "/v1/search/popular-preview/novel"

    private val POPULAR_SORTS = setOf("popular_desc", "popular_male_desc", "popular_female_desc")

    private val KEPT_PARAMS = setOf(
        "filter",
        "include_translated_tag_results",
        "merge_plain_keyword_results",
        "word",
        "search_target",
        "search_ai_type",
        "start_date",
        "end_date"
    )

    private val installed = AtomicBoolean(false)

    fun install(host: Host) {
        if (!installed.compareAndSet(false, true)) return
        val builderClass = ReflectUtil.loadClass(host.classLoader, REQUEST_BUILDER_CLASS)
        if (builderClass == null) {
            ModuleLog.w(TAG, "okhttp3.Request\$Builder not found, popular search rewrite skipped")
            installed.set(false)
            return
        }
        val urlClass = ReflectUtil.loadClass(host.classLoader, HTTP_URL_CLASS)
        if (urlClass == null) {
            ModuleLog.w(TAG, "okhttp3.HttpUrl not found, popular search rewrite skipped")
            installed.set(false)
            return
        }
        val buildMethod = ReflectUtil.findMethods(builderClass, "build").firstOrNull { it.parameterCount == 0 }
        if (buildMethod == null) {
            ModuleLog.w(TAG, "Request.Builder.build not found, popular search rewrite skipped")
            installed.set(false)
            return
        }
        try {
            XposedBridge.hookMethod(buildMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!PayloadProcessor.premiumEnabled) return
                    val request = param.result ?: return
                    val rewritten = rewriteRequest(request, urlClass) ?: return
                    param.result = rewritten
                }
            })
            ModuleLog.i(TAG, "popular search rewrite installed")
        } catch (t: Throwable) {
            installed.set(false)
            ModuleLog.w(TAG, "hook Request.Builder.build failed", t)
        }
    }

    private fun rewriteRequest(request: Any, urlClass: Class<*>): Any? {
        val url = ReflectUtil.invoke(request, ReflectUtil.findMethod(request.javaClass, "url")) ?: return null
        val text = ReflectUtil.invoke(url, ReflectUtil.findMethod(url.javaClass, "toString")) as? String ?: return null
        val target = buildPreviewUrl(text) ?: return null
        val parseMethod = ReflectUtil.findMethod(urlClass, "parse", String::class.java) ?: return null
        val newUrl = ReflectUtil.invokeStatic(parseMethod, target) ?: return null
        val builder = ReflectUtil.invoke(request, ReflectUtil.findMethod(request.javaClass, "newBuilder")) ?: return null
        ReflectUtil.invoke(builder, ReflectUtil.findMethod(builder.javaClass, "url", urlClass), newUrl)
        val newRequest = ReflectUtil.invoke(builder, ReflectUtil.findMethod(builder.javaClass, "build")) ?: return null
        ModuleLog.d(TAG, "popular search rewritten to preview endpoint: $target")
        return newRequest
    }

    internal fun buildPreviewUrl(urlText: String): String? {
        val queryIndex = urlText.indexOf('?')
        if (queryIndex < 0) return null
        val base = urlText.substring(0, queryIndex)
        val query = urlText.substring(queryIndex + 1)
        var sort: String? = null
        val kept = ArrayList<String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val key = part.substringBefore('=')
            if (key == "sort") {
                sort = part.substringAfter('=', "")
                continue
            }
            if (key in KEPT_PARAMS) kept.add(part)
        }
        val sortValue = sort ?: return null
        if (sortValue !in POPULAR_SORTS) return null
        val path = when {
            base.endsWith(SEARCH_ILLUST_PATH) -> base.substring(0, base.length - SEARCH_ILLUST_PATH.length) + PREVIEW_ILLUST_PATH
            base.endsWith(SEARCH_NOVEL_PATH) -> base.substring(0, base.length - SEARCH_NOVEL_PATH.length) + PREVIEW_NOVEL_PATH
            else -> return null
        }
        if (kept.isEmpty()) return null
        return "$path?${kept.joinToString("&")}"
    }
}
