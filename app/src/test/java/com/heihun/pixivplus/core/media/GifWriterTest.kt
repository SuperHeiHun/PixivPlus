package com.heihun.pixivplus.core.media

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 反射调用 JVM ImageIO 独立解码校验，避免把桌面图形 API 加入 Android 编译依赖。 */
class GifWriterTest {
    private val imageIO = Class.forName("javax.imageio.ImageIO")
    private val imageReader = Class.forName("javax.imageio.ImageReader")
    private val bufferedImage = Class.forName("java.awt.image.BufferedImage")
    private fun rgb(image: Any, x: Int, y: Int) = bufferedImage.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .invoke(image, x, y) as Int and 0xffffff

    @Test fun `gif decodes multiple frames and timings`() {
        val bytes = ByteArrayOutputStream()
        GifWriter(bytes, 20, 10).apply {
            addFrame(IntArray(200) { 0xff0000 }, 60)
            addFrame(IntArray(200) { 0x00ff00 }, 100)
            finish()
        }
        val input = imageIO.getMethod("createImageInputStream", Any::class.java)
            .invoke(null, ByteArrayInputStream(bytes.toByteArray()))
        val readers = imageIO.getMethod("getImageReadersByFormatName", String::class.java).invoke(null, "gif") as Iterator<*>
        val reader = readers.next()!!
        try {
            imageReader.getMethod("setInput", Any::class.java).invoke(reader, input)
            assertEquals(2, imageReader.getMethod("getNumImages", Boolean::class.javaPrimitiveType).invoke(reader, true))
            assertEquals(0xff0000, rgb(imageReader.getMethod("read", Int::class.javaPrimitiveType).invoke(reader, 0), 1, 1))
            assertEquals(0x00ff00, rgb(imageReader.getMethod("read", Int::class.javaPrimitiveType).invoke(reader, 1), 1, 1))
            val metadata = imageReader.getMethod("getImageMetadata", Int::class.javaPrimitiveType).invoke(reader, 1)
            val tree = Class.forName("javax.imageio.metadata.IIOMetadata").getMethod("getAsTree", String::class.java)
                .invoke(metadata, "javax_imageio_gif_image_1.0") as Node
            val gce = (0 until tree.childNodes.length).map { tree.childNodes.item(it) }.first { it.nodeName == "GraphicControlExtension" }
            assertEquals("10", gce.attributes.getNamedItem("delayTime").nodeValue)
        } finally {
            imageReader.getMethod("dispose").invoke(reader)
            Class.forName("javax.imageio.stream.ImageInputStream").getMethod("close").invoke(input)
        }
    }

    @Test fun `lzw growth and resets decode all pixels`() {
        val random = java.util.Random(41)
        val pixels = IntArray(300 * 300) { random.nextInt(0x1000000) }
        val bytes = ByteArrayOutputStream()
        GifWriter(bytes, 300, 300).apply { addFrame(pixels, 80); finish() }
        val image = imageIO.getMethod("read", InputStream::class.java).invoke(null, ByteArrayInputStream(bytes.toByteArray()))
        for (index in pixels.indices) {
            val source = pixels[index]
            val expected = ((source ushr 21 and 7) * 255 / 7 shl 16) or
                ((source ushr 13 and 7) * 255 / 7 shl 8) or ((source ushr 6 and 3) * 255 / 3)
            assertEquals("pixel $index", expected, rgb(image, index % 300, index / 300))
        }
    }
}
