package com.heihun.pixivplus.feature.caption

import android.provider.Settings
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.ApiHooks
import com.heihun.pixivplus.core.net.PayloadProcessor
import com.heihun.pixivplus.core.util.EntityReader
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
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
    }

    override fun hook(host: Host) {
        enabled = isEnabled(ConfigStoreRegistry.store)
        PayloadProcessor.captionFixEnabled = enabled
        ApiHooks.ensureInstalled(host)

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

    override fun onConfigChanged(host: Host) {
        enabled = isEnabled(ConfigStoreRegistry.store)
        PayloadProcessor.captionFixEnabled = enabled
        ModuleLog.i(displayName, "插画简介修复已${if (enabled) "开启" else "关闭"}")
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
        val reference = WeakReference(view)
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
                val target = reference.get() ?: return@execute
                applyCaption(target, id, caption, "web", caption.length)
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
        val method = ReflectUtil.findMethod(view.javaClass, "c", String::class.java)
        if (method != null) {
            ReflectUtil.invoke(view, method, caption)
            return true
        }
        val captionTextView = findCaptionTextView(view) ?: return false
        captionTextView.movementMethod = LinkMovementMethod.getInstance()
        captionTextView.text = Html.fromHtml(caption, 0)
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

    private fun resolveProxy(): Proxy? {
        try {
            val selected = ProxySelector.getDefault()?.select(URI("https://www.pixiv.net/"))
            val http = selected?.firstOrNull { it.type() == Proxy.Type.HTTP }
            val address = http?.address()
            if (address is InetSocketAddress && !address.isUnresolved && address.port > 0) {
                return http
            }
        } catch (_: Throwable) {
        }
        return try {
            val context = Host.context() ?: return null
            val resolver = context.contentResolver
            var host = Settings.Global.getString(resolver, "global_http_proxy_host")
            var port = Settings.Global.getInt(resolver, "global_http_proxy_port", 0)
            if (host.isNullOrEmpty()) {
                val combined = Settings.Global.getString(resolver, Settings.Global.HTTP_PROXY) ?: return null
                val value = combined.substringAfter("://", combined)
                val index = value.lastIndexOf(':')
                if (index > 0) {
                    host = value.substring(0, index)
                    port = value.substring(index + 1).toIntOrNull() ?: 0
                }
            }
            if (host.isNullOrEmpty() || port <= 0) {
                null
            } else {
                Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchWebCaption(id: Long, cookie: String?): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(String.format(java.util.Locale.ROOT, WEB_API, id))
            val proxy = resolveProxy()
            ModuleLog.d(displayName, "web caption fetch id=$id proxy=${proxy?.address() ?: "direct"}")
            val conn = (if (proxy != null) url.openConnection(proxy) else url.openConnection())
                as HttpURLConnection
            connection = conn
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Referer", WEB_REFERER)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7")
            if (!cookie.isNullOrEmpty()) {
                conn.setRequestProperty("Cookie", cookie)
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                ModuleLog.w(displayName, "web caption http $code id=$id")
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(8192)
                while (builder.length < MAX_BODY_CHARS) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                }
                builder.toString()
            }
            val root = JSONObject(text)
            if (root.optBoolean("error", false)) {
                ModuleLog.w(displayName, "web caption error id=$id message=${root.optString("message")}")
                return null
            }
            root.optJSONObject("body")?.optString("description")?.ifEmpty { null }
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "web caption fetch failed id=$id: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }
}
