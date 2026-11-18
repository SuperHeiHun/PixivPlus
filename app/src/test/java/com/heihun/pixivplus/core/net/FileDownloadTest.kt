package com.heihun.pixivplus.core.net

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class FileDownloadTest {
    private fun run(response: String, file: File) = FileDownload.read(response.byteInputStream(), file, "https://i.pximg.net/a.jpg", 1000) { _, _ -> }
    private fun withFile(work: (File) -> Unit) {
        val directory = Files.createTempDirectory("plp-file-download").toFile()
        try { work(File(directory, "a.jpg")) } finally { directory.deleteRecursively() }
    }
    @Test fun `interrupted transfer persists bytes then valid range appends`() = withFile { file ->
        try { run("HTTP/1.1 200 OK\r\nContent-Length: 10\r\nETag: \"a\"\r\n\r\n12345", file); fail() } catch (_: IOException) { }
        assertEquals("12345", file.readText())
        assertEquals("bytes=5-", FileDownload.headers(file)["Range"])
        assertEquals("\"a\"", FileDownload.headers(file)["If-Range"])
        assertTrue(run("HTTP/1.1 206 Partial\r\nContent-Length: 5\r\nContent-Range: bytes 5-9/10\r\nETag: \"a\"\r\n\r\n67890", file).complete)
        assertEquals("1234567890", file.readText())
        assertTrue(FileDownload.complete(file, "https://i.pximg.net/a.jpg"))
    }
    @Test fun `server ignoring range restarts rather than concatenates`() = withFile { file ->
        file.writeText("old")
        run("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nnew", file)
        assertEquals("new", file.readText())
    }
    @Test fun `invalid range cannot corrupt previous partial bytes`() = withFile { file ->
        file.writeText("12345")
        try { run("HTTP/1.1 206 Partial\r\nContent-Range: bytes 3-9/10\r\n\r\nbad", file); fail() } catch (_: IOException) { }
        assertEquals("12345", file.readText())
    }
    @Test fun `chunked transfers are streamed completely`() = withFile { file ->
        assertTrue(run("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n", file).complete)
        assertEquals("abcde", file.readText())
    }
    @Test fun `oversized response does not truncate old data`() = withFile { file ->
        file.writeText("old")
        try { run("HTTP/1.1 200 OK\r\nContent-Length: 1001\r\n\r\n", file); fail() } catch (_: IOException) { }
        assertEquals("old", file.readText())
    }
    @Test fun `source URL mismatch clears unusable checkpoint`() = withFile { file ->
        run("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nold", file)
        FileDownload.prepare(file, "https://i.pximg.net/changed.jpg")
        assertEquals(0L, file.length())
        assertFalse(FileDownload.complete(file, "https://i.pximg.net/changed.jpg"))
    }
    @Test fun `changed validator is rejected before appending`() = withFile { file ->
        try { run("HTTP/1.1 200 OK\r\nContent-Length: 10\r\nETag: \"a\"\r\n\r\n12345", file) } catch (_: IOException) { }
        try { run("HTTP/1.1 206 Partial\r\nContent-Range: bytes 5-9/10\r\nETag: \"b\"\r\n\r\n67890", file); fail() } catch (_: IOException) { }
        assertEquals("12345", file.readText())
    }
    @Test fun `gzip response is rejected rather than mixing encoded bytes`() = withFile { file ->
        file.writeText("old")
        try { run("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 3\r\n\r\nbad", file); fail() } catch (_: IOException) { }
        assertEquals("old", file.readText())
    }
}
