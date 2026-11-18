package com.heihun.pixivplus.core.net

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DownloadTransportTest {
    @Test fun `socket download preserves interruption and sends Range on retry`() {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val failure = AtomicReference<Throwable>()
        val request = AtomicReference<String>()
        val worker = thread(isDaemon = true) {
            try {
                repeat(2) { attempt -> server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val head = StringBuilder()
                    val input = socket.getInputStream()
                    while (!head.endsWith("\r\n\r\n")) { val value = input.read(); if (value < 0) break; head.append(value.toChar()) }
                    if (attempt == 1) request.set(head.toString())
                    val response = if (attempt == 0) "HTTP/1.1 200 OK\r\nContent-Length: 10\r\nETag: \"a\"\r\n\r\n12345"
                        else "HTTP/1.1 206 Partial Content\r\nContent-Length: 5\r\nContent-Range: bytes 5-9/10\r\nETag: \"a\"\r\n\r\n67890"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                } }
            } catch (t: Throwable) { failure.set(t) }
        }
        val directory = Files.createTempDirectory("plp-resume-transport").toFile()
        try {
            val file = directory.resolve("image.jpg")
            val url = URL("http://127.0.0.1:${server.localPort}/image.jpg")
            val spec = ProxySpec(ProxyMode.DIRECT, "", 0)
            try { TunnelHttpClient.downloadFile(spec, url, emptyMap(), file, 1000) { _, _ -> }; fail() }
            catch (_: java.io.IOException) { }
            assertEquals("12345", file.readText())
            TunnelHttpClient.downloadFile(spec, url, emptyMap(), file, 1000) { _, _ -> }
            worker.join(6000)
            assertFalse(worker.isAlive)
            failure.get()?.let { throw AssertionError(it) }
            assertTrue(request.get().contains("Range: bytes=5-\r\n"))
            assertTrue(request.get().contains("If-Range: \"a\"\r\n"))
            assertEquals("1234567890", file.readText())
        } finally { server.close(); directory.deleteRecursively() }
    }
}
