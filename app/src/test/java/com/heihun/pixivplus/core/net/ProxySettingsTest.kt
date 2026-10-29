package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.config.ConfigKey
import com.heihun.pixivplus.core.config.ConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProxySettingsTest {

    @Test
    fun `mode parses known values case insensitively`() {
        assertEquals(ProxyMode.SYSTEM, ProxyMode.fromStorage(null))
        assertEquals(ProxyMode.SYSTEM, ProxyMode.fromStorage(""))
        assertEquals(ProxyMode.SYSTEM, ProxyMode.fromStorage("unknown"))
        assertEquals(ProxyMode.HTTP, ProxyMode.fromStorage("http"))
        assertEquals(ProxyMode.SOCKS4, ProxyMode.fromStorage("SOCKS4"))
        assertEquals(ProxyMode.SOCKS5, ProxyMode.fromStorage(" socks5 "))
    }

    @Test
    fun `custom modes require endpoint`() {
        assertFalse(ProxyMode.SYSTEM.requiresEndpoint())
        assertFalse(ProxyMode.DIRECT.requiresEndpoint())
        assertTrue(ProxyMode.HTTP.requiresEndpoint())
        assertTrue(ProxyMode.SOCKS4.requiresEndpoint())
        assertTrue(ProxyMode.SOCKS5.requiresEndpoint())
    }

    @Test
    fun `valid endpoint is required for custom modes`() {
        assertFalse(ProxySpec(ProxyMode.HTTP, "", 7890).hasValidEndpoint())
        assertFalse(ProxySpec(ProxyMode.HTTP, "127.0.0.1", 0).hasValidEndpoint())
        assertFalse(ProxySpec(ProxyMode.HTTP, "127.0.0.1", 70000).hasValidEndpoint())
        assertTrue(ProxySpec(ProxyMode.SOCKS5, "127.0.0.1", 1080).hasValidEndpoint())
    }

    @Test
    fun `basic auth header encodes utf8 credentials`() {
        val spec = ProxySpec(ProxyMode.HTTP, "127.0.0.1", 7890, "用户", "p@ss: word")
        val expected = "Basic " + Base64.getEncoder()
            .encodeToString("用户:p@ss: word".toByteArray(Charsets.UTF_8))
        assertEquals(expected, spec.basicAuthHeaderOrNull())
        assertTrue(spec.hasCredentials())
    }

    @Test
    fun `empty credentials produce no auth header`() {
        assertNull(ProxySpec(ProxyMode.HTTP, "127.0.0.1", 7890).basicAuthHeaderOrNull())
        assertNull(ProxySpec(ProxyMode.HTTP, "127.0.0.1", 7890, "", "").basicAuthHeaderOrNull())
        assertFalse(ProxySpec(ProxyMode.HTTP, "127.0.0.1", 7890).hasCredentials())
    }

    @Test
    fun `username only is still an auth credential`() {
        val spec = ProxySpec(ProxyMode.SOCKS4, "proxy", 1080, "user-id")
        assertTrue(spec.hasCredentials())
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("user-id:".toByteArray(Charsets.UTF_8)),
            spec.basicAuthHeaderOrNull()
        )
    }

    @Test
    fun `current reads endpoint and credentials`() {
        val store = MapConfigStore(
            mapOf(
                "proxy_mode" to "socks5",
                "proxy_host" to " 192.168.1.2 ",
                "proxy_port" to "1080",
                "proxy_username" to " user ",
                "proxy_password" to " secret "
            )
        )
        val spec = ProxySettings.current(store)
        assertEquals(ProxyMode.SOCKS5, spec.mode)
        assertEquals("192.168.1.2", spec.host)
        assertEquals(1080, spec.port)
        assertEquals("user", spec.username)
        assertEquals(" secret ", spec.password)
    }

    @Test
    fun `describe describes custom endpoint`() {
        assertEquals(
            "http://127.0.0.1:7890",
            ProxySettings.describe(ProxySpec(ProxyMode.HTTP, "127.0.0.1", 7890))
        )
        assertEquals(
            "socks4://proxy:1080",
            ProxySettings.describe(ProxySpec(ProxyMode.SOCKS4, "proxy", 1080))
        )
        assertEquals(
            "socks5://proxy:1080",
            ProxySettings.describe(ProxySpec(ProxyMode.SOCKS5, "proxy", 1080))
        )
        assertEquals("direct", ProxySettings.describe(ProxySpec(ProxyMode.DIRECT, "", 0)))
    }

    @Test
    fun `describe marks auth without exposing credentials`() {
        val spec = ProxySpec(ProxyMode.HTTP, "127.0.0.1", 7890, "alice", "top-secret")
        val text = ProxySettings.describe(spec)
        assertEquals("http://127.0.0.1:7890 (auth)", text)
        assertFalse(text.contains("alice"))
        assertFalse(text.contains("top-secret"))
    }

    private class MapConfigStore(values: Map<String, String>) : ConfigStore {

        private val values = values.toMutableMap()

        override fun <T> get(key: ConfigKey<T>): T {
            val raw = values[key.name] ?: key.encode(key.defaultValue)
            return key.decode(raw)
        }

        override fun <T> put(key: ConfigKey<T>, value: T) {
            values[key.name] = key.encode(value)
        }

        override fun addListener(key: ConfigKey<*>, listener: (Any?) -> Unit) {
        }

        override fun removeListener(listener: (Any?) -> Unit) {
        }
    }
}
