package com.heihun.pixivplus.feature.caption

import android.app.Activity
import android.content.Intent
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.ApiHooks
import com.heihun.pixivplus.core.net.PayloadProcessor
import com.heihun.pixivplus.core.net.ProxyHttp
import com.heihun.pixivplus.core.net.ProxySettings
import com.heihun.pixivplus.core.util.DetailActivityTracker
import com.heihun.pixivplus.core.util.EntityReader
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 恢复被 Android 版 Pixiv 隐藏的插画简介。
 *
 * 官方为满足应用商店合规要求，会隐藏包含 URL 的简介文本。
 * 优先使用列表响应中的 description（PayloadProcessor 本地索引），
 * 索引中没有时再请求网页版 ajax 接口兜底。
 */
class CaptionFixFeature : ConfigurableFeature(
    "caption_fix",
    "插画简介修复",
    ConfigKeys.CAPTION_FIX
) {

    @Volatile
    private var enabled = true

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "plp-caption").apply { isDaemon = true }
    }

    private val webCaptions = ConcurrentHashMap<Long, String>()
    private val inflight = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val retryAfter = ConcurrentHashMap<Long, Long>()

    /** 已回填到界面的简介长度（id → 长度），避免相同简介反复触发渲染。 */
    private val appliedCaptions = ConcurrentHashMap<Long, Int>()

    private companion object {
        const val CAPTION_VIEW_CLASS =
            "jp.pxv.android.feature.component.androidview.DetailCaptionAndTagsView"
        const val PIXIV_ILLUST_CLASS = "jp.pxv.android.domain.commonentity.PixivIllust"
        const val WEB_API = "https://www.pixiv.net/ajax/illust/%d?lang=zh"
        const val WEB_REFERER = "https://www.pixiv.net/"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val MAX_BODY_CHARS = 2 * 1024 * 1024
        const val RETRY_INTERVAL_MS = 10 * 60 * 1000L
        const val APPLY_RETRY_LIMIT = 5
        const val APPLY_RETRY_DELAY_MS = 600L
    }

    override fun hook(host: Host) {
        enabled = isEnabled(ConfigStoreRegistry.store)
        PayloadProcessor.captionFixEnabled = enabled
        PayloadProcessor.captionSink = { id, caption -> onCaptionIndexed(id, caption) }
        PayloadProcessor.captionPrefetcher = { id -> kickPrefetch(id) }
        ApiHooks.ensureInstalled(host)
        hookDetailActivityIntents(host)

        val viewClass = ReflectUtil.loadClass(host.classLoader, CAPTION_VIEW_CLASS)
        val illustClass = ReflectUtil.loadClass(host.classLoader, PIXIV_ILLUST_CLASS)
        if (viewClass == null || illustClass == null) {
            ModuleLog.w(displayName, "caption view class not found, feature skipped")
            return
        }

        var installed = 0
        try {
            for (method in viewClass.declaredMethods) {
                if (method.name != "setIllust" || method.parameterCount != 1) continue
                if (!illustClass.isAssignableFrom(method.parameterTypes[0])) continue
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            onIllustBound(param.thisObject as? View, param.args?.firstOrNull())
                        } catch (t: Throwable) {
                            ModuleLog.w(displayName, "caption fix failed", t)
                        }
                    }
                })
                installed++
            }
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "hook setIllust failed", t)
        }

        if (installed == 0) {
            ModuleLog.w(displayName, "setIllust hook not installed")
        } else {
            ModuleLog.i(displayName, "illust caption hooks installed: $installed (enabled=$enabled)")
        }
    }

    /** 同步获取简介（后台线程调用，供预取使用）。 */
    private fun fetchCaptionSync(id: Long): String? {
        // 优先借用 App 自身网络栈（代理 / DNS / TLS 与 App 完全一致，成功率最高）。
        fetchCaptionViaApp(id)?.let { return it }
        val cookie = readWebCookie()
        return fetchWebCaption(id, cookie)
    }

    private val DETAIL_ACTIVITY_CLASSES = arrayOf(
        "jp.pxv.android.feature.illustviewer.detail.IllustDetailSingleActivity",
        "jp.pxv.android.feature.illustviewer.detail.IllustDetailPagerActivity"
    )

    /** 详情 Activity 创建时从 intent 提前预取简介，与页面加载并行，避免响应或回填串行等待。 */
    private fun hookDetailActivityIntents(host: Host) {
        var installed = 0
        for (name in DETAIL_ACTIVITY_CLASSES) {
            val clazz = ReflectUtil.loadClass(host.classLoader, name) ?: continue
            try {
                XposedBridge.hookAllMethods(clazz, "onCreate", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val id = extractIllustIdFromIntent(activity.intent) ?: return
                        ModuleLog.d(displayName, "detail intent detected id=$id (${clazz.simpleName})")
                        kickPrefetch(id)
                    }
                })
                installed++
            } catch (t: Throwable) {
                ModuleLog.w(displayName, "hook detail activity intent failed: $name", t)
            }
        }
        if (installed > 0) {
            ModuleLog.i(displayName, "detail activity prefetch hooks installed: $installed")
        }
    }

    /** 深链 pixiv://illusts/{id} / 跳转 intent 中的作品 id。 */
    private fun extractIllustIdFromIntent(intent: Intent?): Long? {
        if (intent == null) return null
        return try {
            val data = intent.data
            val fromData = data?.lastPathSegment
                ?.takeIf { it.matches(Regex("\\d{4,15}")) }
                ?.toLongOrNull()
            if (fromData != null && fromData > 0L) return fromData
            val extras = intent.extras ?: return null
            for (key in extras.keySet()) {
                if (!key.lowercase().contains("illust")) continue
                val value = extras.get(key) ?: continue
                val id = when (value) {
                    is Long -> value
                    is Int -> value.toLong()
                    is String -> value.toLongOrNull()
                    else -> null
                } ?: continue
                if (id in 1000..999_999_999_999L) return id
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 异步预取：去重后立即返回，结果写入缓存并回填当前详情页。 */
    private fun kickPrefetch(id: Long) {
        if (id <= 0L) return
        if (!webCaptions[id].isNullOrEmpty()) return
        if (!PayloadProcessor.lookupCaption(id).isNullOrEmpty()) return
        if (!inflight.add(id)) return
        executor.execute {
            try {
                val caption = fetchCaptionSync(id) ?: return@execute
                if (webCaptions.size > 2000) webCaptions.clear()
                webCaptions[id] = caption
                // 仅在详情页确实打开时写入注入索引，防止 intent 误判的 id 污染缓存。
                if (DetailActivityTracker.get() != null) {
                    PayloadProcessor.rememberCaption(id, caption)
                }
                applyToCurrentActivity(id, caption, "prefetch", 0)
            } finally {
                inflight.remove(id)
            }
        }
    }

    /** 借用宿主 OkHttpClient 获取简介（web-api / app-api，网络路径与 App 相同）。 */
    private fun fetchCaptionViaApp(id: Long): String? {
        // okhttp 类必须从宿主 classLoader 加载（模块 classLoader 里没有）。
        val loader = Host.classLoader
        val requestBuilderClass = ReflectUtil.loadClass(loader, "okhttp3.Request\$Builder") ?: return null
        val requestClass = ReflectUtil.loadClass(loader, "okhttp3.Request") ?: return null
        val callClass = ReflectUtil.loadClass(loader, "okhttp3.Call") ?: return null
        val responseClass = ReflectUtil.loadClass(loader, "okhttp3.Response") ?: return null
        val clientClass = ReflectUtil.loadClass(loader, "okhttp3.OkHttpClient") ?: return null
        val bodyClass = ReflectUtil.loadClass(loader, "okhttp3.ResponseBody") ?: return null

        val client = ApiHooks.hostClient() ?: try {
            clientClass.getDeclaredConstructor().newInstance()
        } catch (_: Throwable) {
            return null
        }
        val source = if (ApiHooks.hostClient() != null) "host" else "fresh"
        ModuleLog.i(displayName, "caption fetch via app client=$source id=$id")
        val token = ApiHooks.authorizationToken()
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7",
            "Referer" to WEB_REFERER
        )
        // web-api 优先：被屏蔽作品 app-api 必然返回空 caption，先查 app-api 纯浪费一个 RTT。
        for (url in listOf(
            String.format(java.util.Locale.ROOT, WEB_API, id),
            "https://app-api.pixiv.net/v1/illust/detail?illust_id=$id"
        )) {
            try {
                val builder = requestBuilderClass.getDeclaredConstructor().newInstance()
                ReflectUtil.invoke(builder, ReflectUtil.findMethod(requestBuilderClass, "url", String::class.java), url)
                for ((name, value) in headers) {
                    ReflectUtil.invoke(
                        builder,
                        ReflectUtil.findMethod(requestBuilderClass, "header", String::class.java, String::class.java),
                        name, value
                    )
                }
                if (url.contains("app-api") && !token.isNullOrEmpty()) {
                    ReflectUtil.invoke(
                        builder,
                        ReflectUtil.findMethod(requestBuilderClass, "header", String::class.java, String::class.java),
                        "Authorization", token
                    )
                }
                val request = ReflectUtil.invoke(builder, ReflectUtil.findMethod(requestBuilderClass, "build")) ?: continue
                val call = ReflectUtil.invoke(client, ReflectUtil.findMethod(clientClass, "newCall", requestClass), request) ?: continue
                val response = ReflectUtil.invoke(call, ReflectUtil.findMethod(callClass, "execute")) ?: continue
                try {
                    val code = ReflectUtil.invoke(response, ReflectUtil.findMethod(responseClass, "code")) as? Int
                    if (code != 200) continue
                    val body = ReflectUtil.invoke(response, ReflectUtil.findMethod(responseClass, "body"))
                    val text = body?.let { ReflectUtil.invoke(it, ReflectUtil.findMethod(bodyClass, "string")) as? String } ?: continue
                    if (url.contains("app-api")) {
                        val root = JSONObject(text)
                        if (root.optBoolean("error", false)) continue
                        val cap = root.optJSONObject("illust")?.optString("caption").orEmpty()
                        if (cap.isNotEmpty()) {
                            ModuleLog.i(displayName, "caption fetched via app-api id=$id len=${cap.length}")
                            return sanitizeCaptionForApp(cap)
                        }
                    } else {
                        val root = JSONObject(text)
                        if (root.optBoolean("error", false)) continue
                        val cap = root.optJSONObject("body")?.optString("description").orEmpty()
                        if (cap.isNotEmpty()) {
                            ModuleLog.i(displayName, "caption fetched via web-api id=$id len=${cap.length}")
                            return sanitizeCaptionForApp(cap)
                        }
                    }
                } finally {
                    try {
                        ReflectUtil.invoke(response, ReflectUtil.findMethod(responseClass, "close"))
                    } catch (_: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                ModuleLog.w(displayName, "caption fetch via app failed id=$id: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return null
    }

    override fun onConfigChanged(host: Host) {
        enabled = isEnabled(ConfigStoreRegistry.store)
        PayloadProcessor.captionFixEnabled = enabled
        if (!enabled) PayloadProcessor.captionSink = null
        retryAfter.clear()
        ModuleLog.i(displayName, "插画简介修复已${if (enabled) "开启" else "关闭"}")
    }

    /**
     * 详情 / 列表响应把更长简介写入索引后，回填当前正在浏览的详情页。
     *
     * 首次打开被屏蔽简介的作品时，setIllust 触发早于详情响应：当时本地索引
     * 为空、网页兜底可能不可用，简介只能等索引建立后由这里补渲染。
     */
    private fun onCaptionIndexed(id: Long, caption: String) {
        if (!enabled) return
        if (caption.isBlank()) return
        val applied = appliedCaptions[id]
        if (applied != null && applied >= caption.length) return
        applyToCurrentActivity(id, caption, "detail", 0)
    }

    /**
     * 回填当前详情页上所有绑定该作品的 caption view。
     * 详情 UI 可能尚未构建（响应注入早于 App 首次绑定）或部分实例尚未绑定，
     * 找不到任何实例时短暂重试。
     */
    private fun applyToCurrentActivity(id: Long, caption: String, source: String, attempt: Int) {
        val activity = DetailActivityTracker.get()
        if (activity == null) {
            scheduleApplyRetry(id, caption, source, attempt)
            return
        }
        try {
            activity.runOnUiThread {
                try {
                    val views = findCurrentCaptionViews(activity, id)
                    if (views.isEmpty()) {
                        scheduleApplyRetry(id, caption, source, attempt)
                        return@runOnUiThread
                    }
                    var appliedAny = false
                    for (view in views) {
                        val rendered = captionTextFromView(view)
                        if (rendered != null && rendered.length >= caption.length) continue
                        ModuleLog.i(
                            displayName,
                            "applying caption to view id=$id source=$source len=${caption.length}"
                        )
                        applyCaption(view, id, caption, source, caption.length)
                        appliedAny = true
                    }
                    if (appliedAny) {
                        appliedCaptions[id] = caption.length
                        if (appliedCaptions.size > 2000) appliedCaptions.clear()
                    }
                } catch (t: Throwable) {
                    ModuleLog.w(displayName, "apply indexed caption failed id=$id", t)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun scheduleApplyRetry(id: Long, caption: String, source: String, attempt: Int) {
        if (attempt >= APPLY_RETRY_LIMIT) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            applyToCurrentActivity(id, caption, source, attempt + 1)
        }, APPLY_RETRY_DELAY_MS)
    }

    private fun findCurrentCaptionViews(activity: Activity, id: Long): List<View> {
        return findCaptionViews(activity.window?.decorView).filter { view ->
            val entity = ReflectUtil.readField(view, "f") ?: return@filter false
            (readId(entity) ?: -1L) == id
        }
    }

    private fun findCaptionViews(root: View?): List<View> {
        val result = ArrayList<View>()
        collectCaptionViews(root, result)
        return result
    }

    private fun collectCaptionViews(root: View?, out: MutableList<View>) {
        if (root == null) return
        if (root.javaClass.name == CAPTION_VIEW_CLASS) {
            out.add(root)
            return
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                collectCaptionViews(root.getChildAt(index), out)
            }
        }
    }

    /** 读取简介控件上已渲染的文本（简介修复恢复后的内容）。 */
    private fun captionTextFromView(view: View): String? {
        return try {
            val textId = view.resources.getIdentifier(
                "caption_text_view",
                "id",
                view.context.packageName
            )
            if (textId != 0) {
                view.findViewById<TextView>(textId)?.text?.toString()?.let { text ->
                    if (text.isNotBlank()) return text
                }
            }
            val holder = ReflectUtil.readField(view, "e") ?: return null
            val textView = ReflectUtil.readField(holder, "e") as? TextView ?: return null
            textView.text?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun onIllustBound(view: View?, entity: Any?) {
        if (!enabled || view == null || entity == null) return
        val id = readId(entity) ?: return
        if (id <= 0L) return

        val current = EntityReader.caption(entity)
        val currentLen = current?.length ?: 0
        val local = PayloadProcessor.lookupCaption(id)
        val localLen = local?.length ?: 0

        if (currentLen == 0) {
            ModuleLog.d(displayName, "empty caption id=$id indexLen=$localLen")
        }
        if (local != null && localLen > currentLen) {
            applyCaption(view, id, local, "index", localLen)
            return
        }
        if (currentLen > 0) return

        // 本地索引没有可用简介时，使用网页接口兜底。
        val cached = webCaptions[id]
        if (cached != null) {
            applyCaption(view, id, cached, "cache", cached.length)
            return
        }
        requestWebCaption(view, id)
    }

    private fun requestWebCaption(view: View, id: Long) {
        val throttledUntil = retryAfter[id] ?: 0L
        if (throttledUntil > System.currentTimeMillis()) return
        if (!inflight.add(id)) return

        val cookie = readWebCookie()
        executor.execute {
            try {
                val caption = fetchWebCaption(id, cookie)
                if (caption.isNullOrEmpty()) {
                    retryAfter[id] = System.currentTimeMillis() + RETRY_INTERVAL_MS
                    return@execute
                }
                if (webCaptions.size > 2000) webCaptions.clear()
                webCaptions[id] = caption
                PayloadProcessor.rememberCaption(id, caption)
                // PagerActivity 多个 view 实例（当前页 + 预绑定页）可能绑定同一作品，
                // 必须遍历全部实例渲染，只渲染引用的那个会漏掉用户屏幕上的页面。
                applyToCurrentActivity(id, caption, "web", 0)
            } finally {
                inflight.remove(id)
            }
        }
    }

    private fun applyCaption(
        view: View,
        expectedId: Long,
        caption: String,
        source: String,
        sourceLength: Int
    ) {
        view.post {
            try {
                val currentEntity = ReflectUtil.readField(view, "f")
                val currentId = currentEntity?.let { readId(it) } ?: 0L
                if (currentId != expectedId) return@post

                val existing = currentEntity?.let { EntityReader.caption(it) }
                val existingLen = existing?.length ?: 0
                if (caption.length <= existingLen) return@post

                if (!render(view, caption)) {
                    ModuleLog.w(displayName, "caption render method not found id=$expectedId")
                    return@post
                }
                view.requestLayout()
                view.invalidate()
                ModuleLog.i(
                    displayName,
                    "restored caption id=$expectedId source=$source " +
                        "appLen=$existingLen sourceLen=$sourceLength"
                )
            } catch (t: Throwable) {
                ModuleLog.w(displayName, "apply caption failed id=$expectedId", t)
            }
        }
    }

    private fun render(view: View, caption: String): Boolean {
        val safe = sanitizeCaptionForApp(caption)
        val method = ReflectUtil.findMethod(view.javaClass, "c", String::class.java)
        if (method != null) {
            ReflectUtil.invoke(view, method, safe)
            return true
        }
        val captionTextView = findCaptionTextView(view) ?: return false
        captionTextView.movementMethod = LinkMovementMethod.getInstance()
        captionTextView.text = Html.fromHtml(safe, 0)
        return true
    }

    private fun findCaptionTextView(view: View): TextView? {
        val id = view.resources.getIdentifier("caption_text_view", "id", view.context.packageName)
        if (id != 0) {
            val found = view.findViewById<TextView>(id)
            if (found != null) return found
        }
        val holder = ReflectUtil.readField(view, "e") ?: return null
        return ReflectUtil.readField(holder, "e") as? TextView
    }

    private fun readId(entity: Any): Long? {
        val method = ReflectUtil.findMethod(entity.javaClass, "getId") ?: return null
        return ReflectUtil.invoke(entity, method) as? Long
    }

    private fun readWebCookie(): String? {
        return try {
            android.webkit.CookieManager.getInstance().getCookie("https://www.pixiv.net")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "read web cookie failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /** 外链 <a> 标签（href 非 pixiv 站内），DOTALL 匹配跨行标签内容。 */
    private val EXTERNAL_ANCHOR = Regex(
        "<a\\s[^>]*href=(?:\"([^\"]*)\"|'([^']*)')[^>]*>(.*?)</a>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val INNER_TAG = Regex("<[^>]*>")

    /**
     * App 渲染层（c(String)）会剥离外链 <a> 标签，外链型简介（服务端屏蔽那种基本全是外链）
     * 会被剥成空白。注入 / 渲染前把外部链接替换为其显示文字，站内 pixiv 链接保留标签。
     */
    fun sanitizeCaptionForApp(caption: String): String {
        if (!caption.contains("<a", ignoreCase = true)) return caption
        return EXTERNAL_ANCHOR.replace(caption) { m ->
            val href = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            val text = m.groupValues[3].replace(INNER_TAG, " ").trim()
            val internal = href.isEmpty() ||
                href.startsWith("pixiv://") ||
                href.startsWith("/") ||
                href.contains("//pixiv.net") ||
                href.contains("//www.pixiv.net")
            if (internal) m.value else text.ifEmpty { href }
        }
    }

    private fun fetchWebCaption(id: Long, cookie: String?): String? {
        return try {
            val store = ConfigStoreRegistry.store
            val spec = ProxySettings.current(store)
            val headers = linkedMapOf(
                "Referer" to WEB_REFERER,
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7"
            )
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }
            ModuleLog.d(displayName, "web caption fetch id=$id proxy=${ProxySettings.describe(spec)}")
            val response = ProxyHttp.get(
                store = store,
                url = URL(String.format(java.util.Locale.ROOT, WEB_API, id)),
                headers = headers,
                maxBodyChars = MAX_BODY_CHARS
            ) ?: return null
            if (response.code != 200) {
                ModuleLog.w(displayName, "web caption http ${response.code} id=$id")
                return null
            }
            val root = JSONObject(response.body)
            if (root.optBoolean("error", false)) {
                ModuleLog.w(displayName, "web caption error id=$id message=${root.optString("message")}")
                return null
            }
            root.optJSONObject("body")?.optString("description")?.ifEmpty { null }
                ?.let { sanitizeCaptionForApp(it) }
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "web caption fetch failed id=$id: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }
}
