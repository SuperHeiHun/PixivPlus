package com.heihun.pixivplus.core.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

class OriginalArchiveTest {
    @Test fun `metadata is opt in and UTF8 encoded`() {
        val temp = Files.createTempDirectory("plp-zip-info-test").toFile()
        try {
            val image = temp.resolve("a.png").apply { writeBytes(byteArrayOf(1, 2)) }
            val output = ByteArrayOutputStream()
            val info = MediaInfo(123, "作者", "标题", 456).linksText()
            OriginalArchive.write(output, "作者_标题", listOf(OriginalImage(1, "https://i.pximg.net/a.png")), listOf(image), info)
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                assertEquals("作者_标题_p1.png", zip.nextEntry.name)
                zip.readBytes()
                assertEquals("作品信息.txt", zip.nextEntry.name)
                assertEquals(info, zip.readBytes().toString(Charsets.UTF_8))
                assertEquals(null, zip.nextEntry)
            }
        } finally { temp.deleteRecursively() }
    }
    @Test fun `zip preserves selected page numbers and original bytes`() {
        val temp = Files.createTempDirectory("plp-zip-test").toFile()
        try {
            val a = temp.resolve("a.png").apply { writeBytes(byteArrayOf(1, 2, 3, 0, -1)) }
            val b = temp.resolve("b.jpg").apply { writeBytes(byteArrayOf(-1, -40, 30, 40)) }
            val output = ByteArrayOutputStream()
            OriginalArchive.write(output, "作者_标题", listOf(
                OriginalImage(2, "https://i.pximg.net/123_p1.png"),
                OriginalImage(5, "https://i.pximg.net/123_p4.jpg")
            ), listOf(a, b))
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                assertEquals("作者_标题_p2.png", zip.nextEntry.name)
                assertArrayEquals(a.readBytes(), zip.readBytes())
                assertEquals("作者_标题_p5.jpg", zip.nextEntry.name)
                assertArrayEquals(b.readBytes(), zip.readBytes())
                assertEquals(null, zip.nextEntry)
            }
        } finally { temp.deleteRecursively() }
    }
}
