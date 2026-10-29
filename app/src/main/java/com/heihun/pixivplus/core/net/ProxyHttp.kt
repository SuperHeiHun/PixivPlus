package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.log.ModuleLog
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL

data class ProxyResponse(val code: Int, val body: String)

/**
 * 模块自身的 HTTP 请求入口。
 *
 * 系统代理与直连使用 HttpURLConnection；HTTP(S) / SOCKS4/4a/5 使用自建的
 * Socket + TLS 实现，以便支持代理认证，且不需要修改宿主进程的全局
 * Authenticator。Android/OpenJDK 的 Proxy.Type.SOCKS 只支持 SOCKS5，
 * 无法连接 SOCKS4 代理。
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
        lastFailure.remove()
        val spec = ProxySettings.current(store)
        val allHeaders = defaultHeaders(headers)
        return try {
            when (spec.mode) {
                ProxyMode.HTTP, ProxyMode.SOCKS4, ProxyMode.SOCKS5 -> {
                    if (!spec.hasValidEndpoint()) {
                        ModuleLog.w(TAG, "invalid proxy config (${spec.display()})")
                        lastFailure.set("代理地址或端口无效")
                        null
                    } else {
                        TunnelHttpClient.get(
                            spec = spec,
                            url = url,
                            headers = allHeaders,
                            maxBodyChars = maxBodyChars,
                            connectTimeoutMs = connectTimeoutMs,
                            readTimeoutMs = readTimeoutMs,
                            onError = { error ->
                                lastFailure.set(error.message ?: error.javaClass.simpleName)
                            }
                        )
                    }
                }
                ProxyMode.SYSTEM, ProxyMode.DIRECT -> {
                    getViaConnection(spec, url, allHeaders, maxBodyChars, connectTimeoutMs, readTimeoutMs)
                }
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

    private fun getViaConnection(
        spec: ProxySpec,
        url: URL,
        headers: Map<String, String>,
        maxBodyChars: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): ProxyResponse? {
        val connection = ProxySettings.openConnection(spec, url) ?: return null
        return try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            for ((name, value) in headers) {
                connection.setRequestProperty(name, value)
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                readLimited(reader, maxBodyChars)
            }.orEmpty()
            ProxyResponse(code, body)
        } catch (t: Throwable) {
            ModuleLog.w(
                TAG,
                "http request failed (${ProxySettings.describe(spec)}): ${t.javaClass.simpleName}: ${t.message}"
            )
            lastFailure.set(t.message ?: t.javaClass.simpleName)
            null
        } finally {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readLimited(reader: Reader, maxChars: Int): String {
        val builder = StringBuilder()
        val buffer = CharArray(8192)
        while (builder.length < maxChars) {
            val read = reader.read(buffer)
            if (read < 0) break
            builder.append(buffer, 0, read)
        }
        return builder.toString()
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
