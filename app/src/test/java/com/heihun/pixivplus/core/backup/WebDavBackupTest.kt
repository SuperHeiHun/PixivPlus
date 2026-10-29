package com.heihun.pixivplus.core.backup

import com.heihun.pixivplus.core.config.ConfigKey
import com.heihun.pixivplus.core.config.ConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

class WebDavBackupTest {

    @Test
    fun `upload creates fixed folder then puts timestamped file`() {
        val requests = Collections.synchronizedList(ArrayList<CapturedRequest>())
        val server = FakeServer(2) { index, socket ->
            val request = capture(socket)
            requests += request
            respond(socket, if (index == 0) "201 Created" else "201 Created")
        }
        try {
            val store = directStore()
            val payload = """{"format":"pixivplus-backup","version":1}"""
            val name = "pixiv-backup-26-10-29_14-05.json"
            val result = WebDavBackup.upload(
                store = store,
                host = "127.0.0.1",
                port = server.port,
                https = false,
                username = "alice",
                password = "s3cret",
                fileName = name,
                data = payload.toByteArray(Charsets.UTF_8)
            )
            server.await()

            assertTrue(result.message, result.ok)
            assertEquals(2, requests.size)
            val mkcol = requests[0]
            assertTrue(mkcol.head.startsWith("MKCOL /PixivPlus/ HTTP/1.1\r\n"))
            assertTrue(mkcol.head.contains("\r\nContent-Length: 0\r\n"))
            assertTrue(mkcol.head.contains("\r\nAuthorization: Basic YWxpY2U6czNjcmV0\r\n"))

            val put = requests[1]
            assertTrue(put.head.startsWith("PUT /PixivPlus/$name HTTP/1.1\r\n"))
            assertTrue(put.head.contains("\r\nContent-Type: application/json; charset=utf-8\r\n"))
            assertTrue(put.head.contains("\r\nAuthorization: Basic YWxpY2U6czNjcmV0\r\n"))
            assertEquals(payload, put.body)
        } finally {
            server.close()
        }
    }

    @Test
    fun `latest sends propfind and selects newest backup`() {
        val requests = Collections.synchronizedList(ArrayList<CapturedRequest>())
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/PixivPlus/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/PixivPlus/pixiv-backup-26-10-28_10-00.json</d:href>
                <d:propstat><d:prop>
                  <d:getlastmodified>Wed, 28 Oct 2026 10:00:00 GMT</d:getlastmodified>
                  <d:getcontentlength>2048</d:getcontentlength>
                </d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/PixivPlus/pixiv-backup-26-10-29_11-30.json</d:href>
                <d:propstat><d:prop>
                  <d:getlastmodified>Thu, 29 Oct 2026 11:30:00 GMT</d:getlastmodified>
                  <d:getcontentlength>4096</d:getcontentlength>
                </d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/PixivPlus/other.json</d:href>
                <d:propstat><d:prop><d:getcontentlength>1</d:getcontentlength></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val server = FakeServer(1) { _, socket ->
            requests += capture(socket)
            respond(socket, "207 Multi-Status", xml, "application/xml; charset=utf-8")
        }
        try {
            val result = WebDavBackup.latest(
                store = directStore(),
                host = "127.0.0.1",
                port = server.port,
                https = false,
                username = "alice",
                password = "s3cret"
            )
            server.await()

            assertTrue(result.message, result.ok)
            val backup = result.backup
            assertEquals("pixiv-backup-26-10-29_11-30.json", backup?.name)
            assertEquals(
                "http://127.0.0.1:${server.port}/PixivPlus/pixiv-backup-26-10-29_11-30.json",
                backup?.url?.toString()
            )
            assertEquals(4096L, backup?.size)
            assertEquals(1, requests.size)
            val head = requests[0].head
            assertTrue(head.startsWith("PROPFIND /PixivPlus/ HTTP/1.1\r\n"))
            assertTrue(head.contains("\r\nDepth: 1\r\n"))
            assertTrue(head.contains("\r\nAuthorization: Basic YWxpY2U6czNjcmV0\r\n"))
            assertTrue(requests[0].body.contains("getlastmodified"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `parse latest ignores collection and non backup files`() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/PixivPlus/</D:href></D:response>
              <D:response>
                <D:href>/PixivPlus/pixiv-backup-26-10-27_08-15.json</D:href>
                <D:propstat><D:prop><D:getlastmodified>Mon, 27 Oct 2026 08:15:00 GMT</D:getlastmodified></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/PixivPlus/readme.txt</D:href>
                <D:propstat><D:prop><D:getlastmodified>Tue, 28 Oct 2026 09:00:00 GMT</D:getlastmodified></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val file = WebDavBackup.parseLatest(xml, URL("https://dav.example.com/PixivPlus/"))
        assertEquals("pixiv-backup-26-10-27_08-15.json", file?.name)
        assertEquals(
            "https://dav.example.com/PixivPlus/pixiv-backup-26-10-27_08-15.json",
            file?.url?.toString()
        )
    }

    @Test
    fun `download sends get with basic auth`() {
        val requests = Collections.synchronizedList(ArrayList<CapturedRequest>())
        val payload = """{"format":"pixivplus-backup","version":1}"""
        val server = FakeServer(1) { _, socket ->
            requests += capture(socket)
            respond(socket, "200 OK", payload, "application/json")
        }
        try {
            val remote = WebDavBackup.RemoteBackup(
                url = URL("http://127.0.0.1:${server.port}/PixivPlus/pixiv-backup-26-10-29_11-30.json"),
                name = "pixiv-backup-26-10-29_11-30.json",
                lastModified = 0L,
                size = payload.length.toLong()
            )
            val result = WebDavBackup.download(
                store = directStore(),
                remote = remote,
                username = "alice",
                password = "s3cret"
            )
            server.await()

            assertTrue(result.message, result.ok)
            assertEquals(payload, result.body)
            val head = requests[0].head
            assertTrue(head.startsWith("GET /PixivPlus/pixiv-backup-26-10-29_11-30.json HTTP/1.1\r\n"))
            assertTrue(head.contains("\r\nAuthorization: Basic YWxpY2U6czNjcmV0\r\n"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `folder and file urls use fixed root path`() {
        assertEquals(
            "http://192.168.10.12:8080/PixivPlus/",
            WebDavBackup.folderUrl("192.168.10.12", 8080, false)?.toString()
        )
        assertEquals(
            "https://dav.example.com:5006/PixivPlus/",
            WebDavBackup.folderUrl("https://dav.example.com/", 5006, true)?.toString()
        )
        assertEquals(
            "https://192.168.10.12:5006/PixivPlus/pixiv-backup-26-10-29_14-05.json",
            WebDavBackup.fileUrl(
                "192.168.10.12",
                5006,
                true,
                "pixiv-backup-26-10-29_14-05.json"
            )?.toString()
        )
        assertNull(WebDavBackup.folderUrl("", 80, false))
        assertNull(WebDavBackup.folderUrl("host", 0, false))
        assertNull(WebDavBackup.folderUrl("host", 70000, false))
        assertFalse(WebDavBackup.folderPath().contains("//"))
    }

    private fun directStore(): MapConfigStore {
        return MapConfigStore(mutableMapOf("proxy_mode" to "direct"))
    }

    private class MapConfigStore(private val values: MutableMap<String, String>) : ConfigStore {

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

    private data class CapturedRequest(val head: String, val body: String)

    private class FakeServer(
        private val connections: Int,
        private val handler: (Int, Socket) -> Unit
    ) : AutoCloseable {

        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 8000
        }
        val port: Int = server.localPort
        private val worker = thread(isDaemon = true, name = "fake-webdav") {
            try {
                for (index in 0 until connections) {
                    server.accept().use { socket -> handler(index, socket) }
                }
            } catch (_: Throwable) {
            }
        }

        fun await() {
            worker.join(8000)
        }

        override fun close() {
            try {
                server.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {

        private fun capture(socket: Socket): CapturedRequest {
            val input = socket.getInputStream()
            val head = readHead(input)
            val length = head.split("\r\n")
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            if (length > 0) {
                DataInputStream(input).readFully(body)
            }
            return CapturedRequest(head, String(body, Charsets.UTF_8))
        }

        private fun respond(
            socket: Socket,
            status: String,
            body: String = "",
            contentType: String = "text/plain"
        ) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val output = socket.getOutputStream()
            output.write(
                (
                    "HTTP/1.1 $status\r\nContent-Type: $contentType\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
            output.write(bytes)
            output.flush()
        }

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
    }
}
