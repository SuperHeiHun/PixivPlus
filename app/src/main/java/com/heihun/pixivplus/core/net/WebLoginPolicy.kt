package com.heihun.pixivplus.core.net

import java.net.URI

internal object WebLoginPolicy {
    const val LOGIN_URL = "https://accounts.pixiv.net/login?return_to=https%3A%2F%2Fwww.pixiv.net%2Fsettings%2Fviewing"

    /** 登录窗口只允许官方 HTTPS 顶层页面；不接受任意 intent、文件或相似域名。 */
    fun allows(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host?.lowercase(java.util.Locale.ROOT) in setOf("accounts.pixiv.net", "www.pixiv.net", "pixiv.net")
    } catch (_: Exception) { false }
}
