package com.heihun.pixivplus.core.net

import android.provider.Settings
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.log.ModuleLog
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.URL
import java.util.Base64

enum class ProxyMode(val storageValue: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    DIRECT("direct", "直连"),
    HTTP("http", "HTTP / HTTPS"),
    SOCKS4("socks4", "SOCKS4"),
    SOCKS5("socks5", "SOCKS5");

    fun requiresEndpoint(): Boolean {
        return this == HTTP || this == SOCKS4 || this == SOCKS5
    }

    companion object {
        fun fromStorage(value: String?): ProxyMode {
            val normalized = value?.trim().orEmpty()
            if (normalized.isEmpty()) return SYSTEM
            return entries.firstOrNull { it.storageValue.equals(normalized, ignoreCase = true) } ?: SYSTEM
        }
    }
}

data class ProxySpec(
    val mode: ProxyMode,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
) {
    fun hasValidEndpoint(): Boolean = host.isNotBlank() && port in 1..65535

    fun hasCredentials(): Boolean = username.isNotEmpty() || password.isNotEmpty()

    /** HTTP Basic 凭据；HTTP 代理与 CONNECT 隧道共用。 */
    fun basicAuthHeaderOrNull(): String? {
        if (!hasCredentials()) return null
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    fun display(): String = when (mode) {
        ProxyMode.SYSTEM -> "跟随系统"
        ProxyMode.DIRECT -> "直连"
        else -> "${mode.label} ${host.ifBlank { "-" }}:$port"
    }
}

data class ProxyTestResult(val ok: Boolean, val message: String)

object ProxySettings {

    private const val TAG = "Proxy"
    private const val TEST_URL = "https://www.pixiv.net/"
    private const val TEST_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    fun current(store: ConfigStore): ProxySpec {
        return ProxySpec(
            mode = ProxyMode.fromStorage(store.get(ConfigKeys.PROXY_MODE)),
            host = store.get(ConfigKeys.PROXY_HOST).trim(),
            port = store.get(ConfigKeys.PROXY_PORT).trim().toIntOrNull() ?: 0,
            username = store.get(ConfigKeys.PROXY_USERNAME).trim(),
            password = store.get(ConfigKeys.PROXY_PASSWORD)
        )
    }

    fun describe(spec: ProxySpec): String = when (spec.mode) {
        ProxyMode.SYSTEM -> {
            val address = systemProxy()?.address()
            if (address is InetSocketAddress && !address.isUnresolved && address.port > 0) {
                "system(${address.hostString}:${address.port})"
            } else {
                "system(direct)"
            }
        }
        ProxyMode.DIRECT -> "direct"
        else -> buildString {
            append(spec.mode.storageValue).append("://")
            append(spec.host.ifBlank { "-" }).append(':').append(spec.port)
            if (spec.hasCredentials()) append(" (auth)")
        }
    }

    internal fun openConnection(spec: ProxySpec, url: URL): HttpURLConnection? {
        val proxy = when (spec.mode) {
            ProxyMode.SYSTEM -> systemProxy()
            else -> Proxy.NO_PROXY
        }
        return try {
            url.openConnection(proxy ?: Proxy.NO_PROXY) as? HttpURLConnection
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "open connection failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    fun test(store: ConfigStore): ProxyTestResult {
        val spec = current(store)
        if (spec.mode.requiresEndpoint() && !spec.hasValidEndpoint()) {
            return ProxyTestResult(false, "代理配置不完整，请填写地址和端口")
        }
        val started = System.currentTimeMillis()
        val response = ProxyHttp.get(
            store = store,
            url = URL(TEST_URL),
            headers = mapOf(
                "User-Agent" to TEST_UA,
                "Accept" to "*/*"
            ),
            maxBodyChars = 4096,
            connectTimeoutMs = 8000,
            readTimeoutMs = 8000
        )
        val elapsed = System.currentTimeMillis() - started
        if (response == null) {
            val detail = ProxyHttp.lastFailureReason()
            val suffix = if (detail.isNullOrBlank()) "" else "：$detail"
            return ProxyTestResult(false, "连接失败（${describe(spec)}，${elapsed} ms）$suffix")
        }
        if (response.code == 407) {
            return ProxyTestResult(false, "HTTP 代理认证失败（407），请检查用户名和密码")
        }
        return if (response.code in 200..399) {
            ProxyTestResult(true, "连接成功：HTTP ${response.code}，${elapsed} ms（${describe(spec)}）")
        } else {
            ProxyTestResult(false, "已连接，但返回 HTTP ${response.code}，${elapsed} ms")
        }
    }

    /** 读取系统 HTTP 代理；Android 的设置不一定反映在 ProxySelector 上，因此做双重兜底。 */
    fun systemProxy(): Proxy? {
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
}
