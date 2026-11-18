package com.heihun.pixivplus.core.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

class OriginalArchiveTest {
    @Test fun `zip preserves selected page numbers and original bytes`() {
        val temp = Files.createTempDirectory("plp-zip-test").toFile()
        try {
            val a = temp.resolve("a.png").apply { writeBytes(byteArrayOf(1, 2, 3, 0, -1)) }
            val b = temp.resolve("b.jpg").apply { writeBytes(byteArrayOf(-1, -40, 30, 40)) }
            val output = ByteArrayOutputStream()
            OriginalArchive.write(output, 123, listOf(
                OriginalImage(2, "https://i.pximg.net/123_p1.png"),
                OriginalImage(5, "https://i.pximg.net/123_p4.jpg")
            ), listOf(a, b))
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                assertEquals("123_p1.png", zip.nextEntry.name)
                assertArrayEquals(a.readBytes(), zip.readBytes())
                assertEquals("123_p4.jpg", zip.nextEntry.name)
                assertArrayEquals(b.readBytes(), zip.readBytes())
                assertEquals(null, zip.nextEntry)
            }
        } finally { temp.deleteRecursively() }
    }
}
