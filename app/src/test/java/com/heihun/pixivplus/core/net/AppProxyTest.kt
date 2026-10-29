package com.heihun.pixivplus.core.net

import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProxyTest {

    @Test
    fun `proxy mapping follows mode`() {
        assertEquals(Proxy.NO_PROXY, AppProxy.proxyFor(ProxySpec(ProxyMode.DIRECT, "", 0)))
        assertNull(AppProxy.proxyFor(ProxySpec(ProxyMode.SYSTEM, "", 0)))
        assertNull(AppProxy.proxyFor(ProxySpec(ProxyMode.SOCKS4, "10.0.0.2", 1080)))

        val http = AppProxy.proxyFor(ProxySpec(ProxyMode.HTTP, "192.168.10.12", 7890))
        assertEquals(Proxy.Type.HTTP, http?.type())
        assertEquals("192.168.10.12", (http?.address() as? java.net.InetSocketAddress)?.hostString)
        assertEquals(7890, (http?.address() as? java.net.InetSocketAddress)?.port)

        val socks5 = AppProxy.proxyFor(ProxySpec(ProxyMode.SOCKS5, "10.0.0.2", 1080))
        assertEquals(Proxy.Type.SOCKS, socks5?.type())

        assertNull(AppProxy.proxyFor(ProxySpec(ProxyMode.HTTP, "", 7890)))
        assertNull(AppProxy.proxyFor(ProxySpec(ProxyMode.HTTP, "host", 0)))
        assertNull(AppProxy.proxyFor(ProxySpec(ProxyMode.HTTP, "host", 70000)))
    }

    @Test
    fun `basic credentials use base64`() {
        assertNull(AppProxy.basicCredentials(ProxySpec(ProxyMode.HTTP, "h", 1)))
        assertEquals(
            "Basic " + java.util.Base64.getEncoder().encodeToString("alice:".toByteArray()),
            AppProxy.basicCredentials(ProxySpec(ProxyMode.HTTP, "h", 1, "alice", ""))
        )
        assertEquals(
            "Basic " + java.util.Base64.getEncoder().encodeToString("alice:s3cret".toByteArray()),
            AppProxy.basicCredentials(ProxySpec(ProxyMode.HTTP, "h", 1, "alice", "s3cret"))
        )
    }

    @Test
    fun `install replaces selector and restore brings original back`() {
        val original = ProxySelector.getDefault()
        try {
            AppProxy.install(ProxySpec(ProxyMode.HTTP, "127.0.0.1", 7890))
            assertTrue(AppProxy.isInstalled())
            assertEquals(original, AppProxy.originalSelector)

            val uri = URI("https://www.pixiv.net/ajax/illust/1")
            val selected = ProxySelector.getDefault().select(uri)
            assertEquals(1, selected.size)
            assertEquals(Proxy.Type.HTTP, selected.first().type())

            AppProxy.install(ProxySpec(ProxyMode.DIRECT, "", 0))
            assertEquals(original, AppProxy.originalSelector)
            assertEquals(Proxy.NO_PROXY, ProxySelector.getDefault().select(uri).first())

            AppProxy.restore()
            assertFalse(AppProxy.isInstalled())
            assertEquals(original, ProxySelector.getDefault())
        } finally {
            AppProxy.restore()
            ProxySelector.setDefault(original)
        }
    }
}
