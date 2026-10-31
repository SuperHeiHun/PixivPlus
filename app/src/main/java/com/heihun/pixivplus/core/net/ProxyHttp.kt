package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.log.ModuleLog
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

data class ProxyResponse(val code: Int, val body: String)

class ProxyBytesResponse(val code: Int, val bytes: ByteArray)

/**
 * 模块自身的 HTTP 请求入口。
 *
 * 所有请求统一走自建的 Socket + TLS 客户端，以便支持无认证限制的任意
 * HTTP 方法（PUT / PROPFIND 等）以及 HTTP(S) / SOCKS4/4a/5 代理认证，
 * 同时不需要修改宿主进程的全局 Authenticator。
 */
object ProxyHttp {

    private const val TAG = "ProxyHttp"
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val lastFailure = ThreadLocal<String?>()

    fun get(
        store: ConfigStore,
        url: URL,
        headers: Map<String, String> = emptyMap(),
        maxBodyChars: Int = 2 * 1024 * 1024,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 12000
    ): ProxyResponse? {
        return request(
            store = store,
            url = url,
            method = "GET",
            headers = headers,
            body = null,
            maxBodyChars = maxBodyChars,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )
    }

    fun request(
        store: ConfigStore,
        url: URL,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        maxBodyChars: Int = 2 * 1024 * 1024,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 12000
    ): ProxyResponse? {
        lastFailure.remove()
        val spec = effectiveSpec(store)
        val allHeaders = defaultHeaders(headers)
        return try {
            if (spec.mode.requiresEndpoint() && !spec.hasValidEndpoint()) {
                ModuleLog.w(TAG, "invalid proxy config (${spec.display()})")
                lastFailure.set("代理地址或端口无效")
                null
            } else {
                TunnelHttpClient.request(
                    spec = spec,
                    method = method,
                    url = url,
                    headers = allHeaders,
                    body = body,
                    maxBodyChars = maxBodyChars,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    onError = { error ->
                        lastFailure.set(error.message ?: error.javaClass.simpleName)
                    }
                )
            }
        } catch (t: Throwable) {
            ModuleLog.w(
                TAG,
                "request failed (${ProxySettings.describe(spec)}): ${t.javaClass.simpleName}: ${t.message}"
            )
            lastFailure.set(t.message ?: t.javaClass.simpleName)
            null
        }
    }

    /** 当前线程最近一次请求失败的原因，用于设置页提示；不包含代理密码。 */
    fun lastFailureReason(): String? = lastFailure.get()

    /** 二进制 GET：图片下载用；非 2xx 或失败返回 null。 */
    fun getBytes(
        store: ConfigStore,
        url: URL,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = 24 * 1024 * 1024,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 20000
    ): ProxyBytesResponse? {
        lastFailure.remove()
        val spec = effectiveSpec(store)
        val allHeaders = defaultHeaders(headers)
        return try {
            if (spec.mode.requiresEndpoint() && !spec.hasValidEndpoint()) {
                ModuleLog.w(TAG, "invalid proxy config (${spec.display()})")
                lastFailure.set("代理地址或端口无效")
                null
            } else {
                TunnelHttpClient.requestBytes(
                    spec = spec,
                    url = url,
                    headers = allHeaders,
                    maxBytes = maxBytes,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    onError = { error ->
                        lastFailure.set(error.message ?: error.javaClass.simpleName)
                    }
                )
            }
        } catch (t: Throwable) {
            ModuleLog.w(
                TAG,
                "request failed (${ProxySettings.describe(spec)}): ${t.javaClass.simpleName}: ${t.message}"
            )
            lastFailure.set(t.message ?: t.javaClass.simpleName)
            null
        }
    }

    /**
     * 将「跟随系统」解析为具体的直连 / HTTP / SOCKS5 配置。
     *
     * Android 的 HttpURLConnection 不支持 PROPFIND 等自定义方法，因此系统
     * 代理也必须转换成自建客户端可用的具体端点。
     */
    private fun effectiveSpec(store: ConfigStore): ProxySpec {
        val spec = ProxySettings.current(store)
        if (spec.mode != ProxyMode.SYSTEM) return spec
        val proxy = ProxySettings.systemProxy()
        if (proxy == null) return ProxySpec(ProxyMode.DIRECT, "", 0)
        val address = proxy.address() as? InetSocketAddress
        if (address == null || address.isUnresolved || address.port !in 1..65535) {
            return ProxySpec(ProxyMode.DIRECT, "", 0)
        }
        val mode = if (proxy.type() == Proxy.Type.SOCKS) ProxyMode.SOCKS5 else ProxyMode.HTTP
        return ProxySpec(mode, address.hostString, address.port)
    }

    internal fun defaultHeaders(headers: Map<String, String>): Map<String, String> {
        val all = LinkedHashMap<String, String>()
        if (headers.keys.none { it.equals("User-Agent", true) }) {
            all["User-Agent"] = DEFAULT_UA
        }
        if (headers.keys.none { it.equals("Accept-Encoding", true) }) {
            all["Accept-Encoding"] = "identity"
        }
        all.putAll(headers)
        return all
    }
}
