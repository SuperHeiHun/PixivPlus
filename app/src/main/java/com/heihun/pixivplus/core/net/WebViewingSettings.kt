package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.config.ConfigStore
import org.json.JSONObject
import java.net.URL

internal data class WebViewingState(val userId: String, val age: Int?, val level: Int) {
    val r18 get() = level >= 1
    val r18g get() = level == 2
    val canEdit get() = age != null && age >= 18

    fun withR18(enabled: Boolean) = if (enabled) maxOf(level, 1) else 0
    fun withR18g(enabled: Boolean) = if (enabled) 2 else if (level == 2) 1 else level
}

internal enum class WebViewingProblem { LOGIN, AGE, RESPONSE, NETWORK, SESSION, VERIFY }
internal class WebViewingException(val problem: WebViewingProblem) : Exception(problem.name)

/** 只访问固定 Pixiv HTTPS 端点；Cookie、CSRF 和账号状态不落盘、不进入日志或备份。 */
internal class WebViewingSettings(
    private val cookie: () -> String?,
    private val request: (String, String, Map<String, String>, ByteArray?) -> ProxyResponse?
) {
    constructor(store: ConfigStore, cookie: () -> String?) : this(cookie, { path, method, headers, body ->
        ProxyHttp.request(store, URL("$ORIGIN$path"), method, headers, body,
            maxBodyChars = 2 * 1024 * 1024, followRedirects = false)
    })

    fun load(): WebViewingState = load(session())

    fun save(expected: WebViewingState, level: Int): WebViewingState {
        require(level in 0..2)
        val session = session()
        val current = load(session)
        if (current.userId != expected.userId || current.level != expected.level) fail(WebViewingProblem.SESSION)
        if (!current.canEdit) fail(WebViewingProblem.AGE)
        val page = call("/settings/viewing", session)
        if (page.code !in 200..299) fail(WebViewingProblem.RESPONSE)
        val token = csrf(page.body) ?: fail(WebViewingProblem.LOGIN)
        // 读取 CSRF 后再次检查会话，避免用户切换登录后写入另一个账号。
        if (session() != session) fail(WebViewingProblem.SESSION)
        json(call("/ajax/settings/user_x_restrict", session, "POST",
            JSONObject().put("userXRestrict", level).toString().toByteArray(Charsets.UTF_8), token))
        val saved = load(session)
        if (saved.userId != expected.userId || saved.level != level || session() != session) {
            fail(WebViewingProblem.VERIFY)
        }
        return saved
    }

    private fun load(session: String): WebViewingState {
        val user = json(call("/ajax/user/self", session)).getJSONObject("body")
        val settings = json(call("/ajax/settings/self", session)).getJSONObject("body")
        val state = parse(user, settings)
        if (session() != session) fail(WebViewingProblem.SESSION)
        return state
    }

    private fun session(): String = cookie()?.takeIf { value ->
        value.split(';').any { it.trim().startsWith("PHPSESSID=") && it.trim().length > 10 }
    } ?: fail(WebViewingProblem.LOGIN)

    private fun call(path: String, session: String, method: String = "GET", body: ByteArray? = null,
                     token: String? = null): ProxyResponse {
        val headers = linkedMapOf("Cookie" to session, "Referer" to "$ORIGIN/settings/viewing",
            "Origin" to ORIGIN, "Cache-Control" to "no-cache", "Accept" to "application/json")
        if (body != null) headers["Content-Type"] = "application/json; charset=utf-8"
        if (token != null) headers["x-csrf-token"] = token
        return request(path, method, headers, body) ?: fail(WebViewingProblem.NETWORK)
    }

    companion object {
        const val ORIGIN = "https://www.pixiv.net"

        fun parse(user: JSONObject, settings: JSONObject): WebViewingState {
            val id = user.optString("userId")
            if (!id.matches(Regex("[1-9][0-9]*"))) fail(WebViewingProblem.LOGIN)
            val level = user.opt("xRestrict")?.toString()?.toIntOrNull()
                ?.takeIf { it in 0..2 } ?: fail(WebViewingProblem.RESPONSE)
            val age = settings.optJSONObject("user_status")?.opt("age")?.toString()?.toIntOrNull()
                ?.takeIf { it in 0..150 }
            return WebViewingState(id, age, level)
        }

        fun json(response: ProxyResponse): JSONObject {
            if (response.code == 401 || response.code == 403) fail(WebViewingProblem.LOGIN)
            if (response.code !in 200..299) fail(WebViewingProblem.RESPONSE)
            val json = try { JSONObject(response.body) } catch (_: Exception) { fail(WebViewingProblem.RESPONSE) }
            // 未知响应不能当作成功，且不向日志/UI泄露服务端原始响应。
            if (json.opt("error") != false) fail(WebViewingProblem.RESPONSE)
            return json
        }

        fun csrf(html: String): String? {
            // 新版 Next.js 将 Redux api.token 放在二次 JSON 编码的状态字符串中。
            val next = Regex("<script\\b[^>]*\\bid=[\"']__NEXT_DATA__[\"'][^>]*>([\\s\\S]*?)</script>")
                .find(html)?.groupValues?.get(1)
            if (next != null) {
                val token = try {
                    val props = JSONObject(next).getJSONObject("props").getJSONObject("pageProps")
                    JSONObject(props.getString("serverSerializedPreloadedState"))
                        .getJSONObject("api").getString("token")
                } catch (_: Exception) { null }
                if (token != null && token.matches(Regex("[a-fA-F0-9]{32,128}"))) return token
            }
            val decoded = html.replace("&quot;", "\"").replace("&#34;", "\"")
            return Regex("\"token\"\\s*:\\s*\"([a-fA-F0-9]{32,128})\"").find(decoded)?.groupValues?.get(1)
        }

        private fun fail(problem: WebViewingProblem): Nothing = throw WebViewingException(problem)
    }
}
