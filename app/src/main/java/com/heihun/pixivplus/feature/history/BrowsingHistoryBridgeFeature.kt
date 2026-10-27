package com.heihun.pixivplus.feature.history

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.HistoryPayloadBuilder
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class BrowsingHistoryBridgeFeature : ConfigurableFeature(
    "history_bridge",
    "个人首页浏览记录联动",
    ConfigKeys.HISTORY_BRIDGE_ENABLED
) {

    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())

    private companion object {
        const val BRIDGE_INTERCEPTOR = "okhttp3.internal.http.BridgeInterceptor"
        const val SERVER_INTERCEPTOR = "okhttp3.internal.http.CallServerInterceptor"
        const val RESPONSE_BODY = "okhttp3.ResponseBody"
        const val PATH_ILLUSTS = "/browsing-history/illusts"
        const val PATH_NOVELS = "/browsing-history/novels"
        const val INJECT_LIMIT = 50
    }

    override fun hook(host: Host) {
        val installed = hookInterceptor(host, BRIDGE_INTERCEPTOR)
        if (!installed) {
            hookInterceptor(host, SERVER_INTERCEPTOR)
        }
        ModuleLog.i(displayName, "browsing history bridge ready (enabled=${bridgeEnabled()})")
    }

    private fun hookInterceptor(host: Host, className: String): Boolean {
        val clazz = ReflectUtil.loadClass(host.classLoader, className) ?: return false
        val method = ReflectUtil.findMethods(clazz, "intercept")
            .firstOrNull { it.parameterCount == 1 } ?: return false
        if (!hookedMethods.add(method)) return true
        return try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val response = param.result ?: return
                    try {
                        inject(host, param, response)
                    } catch (t: Throwable) {
                        ModuleLog.w(displayName, "history bridge failed", t)
                    }
                }
            })
            ModuleLog.i(displayName, "interceptor hooked: $className")
            true
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "interceptor hook failed: $className", t)
            false
        }
    }

    private fun inject(host: Host, param: XC_MethodHook.MethodHookParam, response: Any) {
        if (!bridgeEnabled()) return
        val path = requestPath(param.args?.firstOrNull()) ?: return
        val listKey: String
        val types: List<String>
        when {
            path.contains(PATH_ILLUSTS) -> {
                listKey = HistoryPayloadBuilder.KEY_ILLUSTS
                types = listOf("ILLUST", "MANGA")
            }
            path.contains(PATH_NOVELS) -> {
                listKey = HistoryPayloadBuilder.KEY_NOVELS
                types = listOf("NOVEL")
            }
            else -> return
        }
        val rows = Runtime.historyDaoOrNull()?.queryTypes(types, INJECT_LIMIT).orEmpty()
        if (rows.isEmpty()) return
        val body = ReflectUtil.invoke(response, ReflectUtil.findMethod(response.javaClass, "body")) ?: return
        val text = ReflectUtil.invoke(body, ReflectUtil.findMethod(body.javaClass, "string")) as? String ?: return
        val profiles = Runtime.authorProfileDaoOrNull()
            ?.query(rows.mapNotNull { it.authorId })
            .orEmpty()
        val content = HistoryPayloadBuilder.mergeLocalHistory(text, listKey, rows, profiles) ?: text
        val rebuilt = rebuildResponse(host, response, body, content) ?: return
        param.result = rebuilt
        if (content != text) {
            ModuleLog.i(
                displayName,
                "injected local history into $listKey (rows=${rows.size}, avatars=${profiles.count { it.value.avatarUrl != null }})"
            )
        }
    }

    private fun requestPath(chain: Any?): String? {
        if (chain == null) return null
        val request = ReflectUtil.invoke(chain, ReflectUtil.findMethod(chain.javaClass, "request")) ?: return null
        val url = ReflectUtil.invoke(request, ReflectUtil.findMethod(request.javaClass, "url")) ?: return null
        return ReflectUtil.invoke(url, ReflectUtil.findMethod(url.javaClass, "encodedPath")) as? String
    }

    private fun rebuildResponse(host: Host, response: Any, body: Any, content: String): Any? {
        val bodyClass = ReflectUtil.loadClass(host.classLoader, RESPONSE_BODY) ?: return null
        val contentType = ReflectUtil.invoke(body, ReflectUtil.findMethod(body.javaClass, "contentType"))
        val create = bodyClass.methods.firstOrNull { candidate ->
            candidate.name == "create" &&
                candidate.parameterCount == 2 &&
                candidate.parameterTypes[0].name == "okhttp3.MediaType" &&
                candidate.parameterTypes[1] == String::class.java
        } ?: return null
        val newBody = try {
            create.invoke(null, contentType, content)
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "create response body failed", t)
            return null
        }
        val builder = ReflectUtil.invoke(response, ReflectUtil.findMethod(response.javaClass, "newBuilder")) ?: return null
        val bodySetter = ReflectUtil.findMethod(builder.javaClass, "body", bodyClass) ?: return null
        try {
            bodySetter.invoke(builder, newBody)
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "replace response body failed", t)
            return null
        }
        return ReflectUtil.invoke(builder, ReflectUtil.findMethod(builder.javaClass, "build"))
    }

    private fun bridgeEnabled(): Boolean {
        return try {
            val store = ConfigStoreRegistry.store
            isEnabled(store) && store.get(ConfigKeys.HISTORY_ENABLED)
        } catch (_: Throwable) {
            false
        }
    }
}
