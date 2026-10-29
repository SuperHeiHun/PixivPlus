package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.log.ModuleLog
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Base64

/**
 * 让宿主 App 自身的网络请求也走模块代理。
 *
 * - 进程级 `ProxySelector`：接管 `HttpURLConnection` 等走平台代理选择的请求；
 * - OkHttp 客户端由 [AppProxyOkhttp] 在 `OkHttpClient.Builder.build()` 前注入 proxy；
 * - 「跟随系统」模式不接管（App 本就使用系统代理），模块自身请求也不受影响。
 */
object AppProxy {

    private const val TAG = "AppProxy"

    /** 安装模块选择器前记录的进程原始选择器，「跟随系统」解析时使用。 */
    @Volatile
    var originalSelector: ProxySelector? = null
        private set

    @Volatile
    private var installedSelector: ProxySelector? = null

    /** 代理对象缓存，避免每次 select 都做 DNS 解析。 */
    private var cachedKey: String? = null
    private var cachedProxy: Proxy? = null

    fun isInstalled(): Boolean = installedSelector != null

    /**
     * 模块代理配置映射为 java.net.Proxy；无法接管时返回 null。
     * Android 的 Socket(Proxy) 只支持 SOCKS5，SOCKS4 无法在 App 层使用；
     * 「跟随系统」交还给系统默认选择器。
     */
    fun proxyFor(spec: ProxySpec): Proxy? = when (spec.mode) {
        ProxyMode.DIRECT -> Proxy.NO_PROXY
        ProxyMode.HTTP -> endpointProxy(Proxy.Type.HTTP, spec)
        ProxyMode.SOCKS5 -> endpointProxy(Proxy.Type.SOCKS, spec)
        else -> null
    }

    /** HTTP 代理的 Basic 认证头值；未配置凭据返回 null。 */
    fun basicCredentials(spec: ProxySpec): String? {
        if (!spec.hasCredentials()) return null
        val raw = "${spec.username}:${spec.password}".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    /**
     * 按当前配置安装进程级 ProxySelector。
     * DIRECT 表示强制 App 直连（忽略系统代理），HTTP / SOCKS5 走配置的代理。
     */
    fun install(spec: ProxySpec) {
        val selector = Selector(spec)
        synchronized(this) {
            if (installedSelector == null) {
                originalSelector = ProxySelector.getDefault()
            }
            try {
                ProxySelector.setDefault(selector)
                installedSelector = selector
                ModuleLog.i(TAG, "app proxy selector installed: ${spec.display()}")
            } catch (t: Throwable) {
                ModuleLog.w(TAG, "install proxy selector failed", t)
            }
        }
    }

    /** 恢复进程原始 ProxySelector（「跟随系统」或关闭功能时调用）。 */
    fun restore() {
        synchronized(this) {
            if (installedSelector == null) return
            installedSelector = null
            try {
                ProxySelector.setDefault(originalSelector)
            } catch (t: Throwable) {
                ModuleLog.w(TAG, "restore proxy selector failed", t)
            }
            ModuleLog.i(TAG, "app proxy selector restored")
        }
    }

    @Synchronized
    private fun endpointProxy(type: Proxy.Type, spec: ProxySpec): Proxy? {
        if (!spec.hasValidEndpoint()) return null
        val key = "${type.name}:${spec.host}:${spec.port}"
        if (key == cachedKey) return cachedProxy
        val value = try {
            Proxy(type, InetSocketAddress(spec.host, spec.port))
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "invalid proxy endpoint: ${spec.display()}")
            null
        }
        cachedKey = key
        cachedProxy = value
        return value
    }

    /** 把配置代理暴露给平台代理选择；无法接管时表现为直连。 */
    private class Selector(private val spec: ProxySpec) : ProxySelector() {

        override fun select(uri: URI?): List<Proxy> {
            val proxy = proxyFor(spec)
            return if (proxy == null) listOf(Proxy.NO_PROXY) else listOf(proxy)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            ModuleLog.w(
                TAG,
                "app proxy connect failed: ${ioe?.message ?: ioe?.javaClass?.simpleName ?: "unknown"}"
            )
        }
    }
}
