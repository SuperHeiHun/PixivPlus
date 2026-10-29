package com.heihun.pixivplus.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TunnelHttpClientTest {

    @Test
    fun `http proxy sends basic auth in absolute form request`() {
        val captured = AtomicReference<String>()
        val server = OneShotServer { socket ->
            captured.set(readHead(socket.getInputStream()))
            writeOk(socket)
        }
        try {
            val spec = ProxySpec(ProxyMode.HTTP, "127.0.0.1", server.port, "user", "pass")
            val response = TunnelHttpClient.get(
                spec = spec,
                url = URL("http://example.test/path?q=1"),
                headers = mapOf("Accept" to "*/*"),
                maxBodyChars = 4096,
                connectTimeoutMs = 5000,
                readTimeoutMs = 5000
            )
            server.await()

            assertEquals(200, response?.code)
            assertEquals("ok", response?.body)
            val head = captured.get()
            assertNotNull(head)
            assertTrue(head.startsWith("GET http://example.test/path?q=1 HTTP/1.1\r\n"))
            assertTrue(head.contains("\r\nHost: example.test\r\n"))
            assertTrue(head.contains("\r\nProxy-Authorization: Basic dXNlcjpwYXNz\r\n"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `socks4 sends user id during handshake`() {
        val capturedUser = AtomicReference<String>()
        val capturedHost = AtomicReference<String>()
        val capturedRequest = AtomicReference<String>()
        val server = OneShotServer { socket ->
            val input = DataInputStream(socket.getInputStream())
            val head = ByteArray(8)
            input.readFully(head)
            val user = readCString(input)
            capturedUser.set(user)
            val ip = head.copyOfRange(4, 8)
            if (ip.contentEquals(byteArrayOf(0, 0, 0, 1))) {
                capturedHost.set(readCString(input))
            }
            val output = socket.getOutputStream()
            output.write(byteArrayOf(0, 0x5A, 0, 0, 0, 0, 0, 0))
            output.flush()
            capturedRequest.set(readHead(input))
            writeOk(socket)
        }
        try {
            val spec = ProxySpec(ProxyMode.SOCKS4, "127.0.0.1", server.port, "alice")
            val response = TunnelHttpClient.get(
                spec = spec,
                url = URL("http://example.test/path"),
                headers = emptyMap(),
                maxBodyChars = 4096,
                connectTimeoutMs = 5000,
                readTimeoutMs = 5000
            )
            server.await()

            assertEquals(200, response?.code)
            assertEquals("ok", response?.body)
            assertEquals("alice", capturedUser.get())
            assertEquals("example.test", capturedHost.get())
            assertTrue(
                "request=${capturedRequest.get().take(120)}",
                capturedRequest.get().startsWith("GET /path HTTP/1.1\r\n")
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `socks5 performs username password authentication`() {
        val capturedUser = AtomicReference<String>()
        val capturedPassword = AtomicReference<String>()
        val capturedRequest = AtomicReference<String>()
        val server = OneShotServer { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val greeting = ByteArray(2)
            input.readFully(greeting)
            val methods = ByteArray(greeting[1].toInt() and 0xFF)
            input.readFully(methods)
            assertTrue(methods.any { (it.toInt() and 0xFF) == 0x02 })
            output.write(byteArrayOf(0x05, 0x02))
            output.flush()

            val authVersion = input.read()
            val userLength = input.read()
            val user = ByteArray(userLength)
            input.readFully(user)
            val passwordLength = input.read()
            val password = ByteArray(passwordLength)
            input.readFully(password)
            assertEquals(0x01, authVersion)
            capturedUser.set(String(user, Charsets.UTF_8))
            capturedPassword.set(String(password, Charsets.UTF_8))
            output.write(byteArrayOf(0x01, 0x00))
            output.flush()

            val requestHead = ByteArray(4)
            input.readFully(requestHead)
            assertEquals(0x05, requestHead[0].toInt())
            assertEquals(0x01, requestHead[1].toInt())
            assertEquals(0x03, requestHead[3].toInt())
            val hostLength = input.read()
            input.readFully(ByteArray(hostLength))
            input.readFully(ByteArray(2))
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            capturedRequest.set(readHead(input))
            writeOk(socket)
        }
        try {
            val spec = ProxySpec(ProxyMode.SOCKS5, "127.0.0.1", server.port, "alice", "s3cret")
            val response = TunnelHttpClient.get(
                spec = spec,
                url = URL("http://example.test/path"),
                headers = emptyMap(),
                maxBodyChars = 4096,
                connectTimeoutMs = 5000,
                readTimeoutMs = 5000
            )
            server.await()

            assertEquals(200, response?.code)
            assertEquals("ok", response?.body)
            assertEquals("alice", capturedUser.get())
            assertEquals("s3cret", capturedPassword.get())
            assertTrue(capturedRequest.get().startsWith("GET /path HTTP/1.1\r\n"))
        } finally {
            server.close()
        }
    }

    private class OneShotServer(private val handler: (Socket) -> Unit) : AutoCloseable {

        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        private val worker = thread(isDaemon = true, name = "fake-proxy") {
            try {
                server.accept().use { socket -> handler(socket) }
            } catch (_: Throwable) {
            }
        }

        fun await() {
            worker.join(5000)
        }

        override fun close() {
            try {
                server.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {

        private fun readHead(input: InputStream): String {
            val output = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val value = input.read()
                if (value < 0) break
                output.write(value)
                matched = when {
                    value == '\r'.code && matched == 0 -> 1
                    value == '\n'.code && matched == 1 -> 2
                    value == '\r'.code && matched == 2 -> 3
                    value == '\n'.code && matched == 3 -> 4
                    value == '\r'.code -> 1
                    else -> 0
                }
            }
            return output.toString("ISO-8859-1")
        }

        private fun readCString(input: InputStream): String {
            val output = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0 || value == 0) break
                output.write(value)
            }
            return output.toString("UTF-8")
        }

        private fun writeOk(socket: Socket) {
            val output: OutputStream = socket.getOutputStream()
            output.write(
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    .toByteArray(Charsets.US_ASCII)
            )
            output.flush()
        }
    }
}
