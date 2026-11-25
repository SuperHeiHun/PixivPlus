package com.heihun.pixivplus.core.net

import org.junit.Assert.*
import org.junit.Test

class WebLoginPolicyTest {
    @Test fun `allows only official https login and return pages`() {
        for (url in listOf(WebLoginPolicy.LOGIN_URL, "https://www.pixiv.net/settings/viewing",
            "https://pixiv.net/", "https://accounts.pixiv.net:443/login", "https://WWW.PIXIV.NET/")) {
            assertTrue(url, WebLoginPolicy.allows(url))
        }
    }

    @Test fun `rejects lookalike hosts credentials local pages and external login`() {
        for (url in listOf("http://accounts.pixiv.net/login", "https://pixiv.net.evil.test/",
            "https://evilpixiv.net/", "https://www.pixiv.net@evil.test/", "https://user@www.pixiv.net/",
            "https://accounts.google.com/", "https://www.pixiv.net:444/", "javascript:alert(1)",
            "intent://login", "file:///sdcard/login.html", "data:text/html,login", "not a url")) {
            assertFalse(url, WebLoginPolicy.allows(url))
        }
    }
}
