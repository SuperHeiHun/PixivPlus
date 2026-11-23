package com.heihun.pixivplus.core.media

import com.heihun.pixivplus.core.i18n.DownloadText as D
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** 流式 GIF89a 编码；RGB332 256 色全局调色板，逐帧 LZW，不保留整部动图位图。 */
class GifWriter(private val output: OutputStream, private val width: Int, private val height: Int) {
    private var finished = false
    init {
        require(width in 1..65535 && height in 1..65535 && width.toLong() * height <= 4_000_000) { D.GIF_PIXEL_LIMIT() }
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        word(width); word(height)
        output.write(byteArrayOf(0xf7.toByte(), 0, 0))
        for (index in 0..255) {
            output.write((index ushr 5) * 255 / 7)
            output.write((index ushr 2 and 7) * 255 / 7)
            output.write((index and 3) * 255 / 3)
        }
        output.write(byteArrayOf(0x21, 0xff.toByte(), 11))
        output.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        output.write(byteArrayOf(3, 1, 0, 0, 0)) // 无限循环。
    }

    fun addFrame(rgb: IntArray, delayMs: Int) {
        com.heihun.pixivplus.core.task.TaskExecution.check()
        check(!finished)
        require(rgb.size == width * height)
        require(delayMs in 1..655350)
        output.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 4)) // disposal=1，无透明。
        word(((delayMs + 5) / 10).coerceIn(1, 65535))
        output.write(byteArrayOf(0, 0, 0x2c))
        word(0); word(0); word(width); word(height)
        output.write(0)
        output.write(8) // LZW 最小码长。
        val encoded = compress(rgb)
        var index = 0
        while (index < encoded.size) {
            val size = minOf(255, encoded.size - index)
            output.write(size)
            output.write(encoded, index, size)
            index += size
        }
        output.write(0)
    }

    fun finish() {
        if (!finished) {
            output.write(0x3b)
            output.flush()
            finished = true
        }
    }

    private fun word(value: Int) { output.write(value and 255); output.write(value ushr 8 and 255) }

    private fun compress(rgb: IntArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        var buffer = 0
        var bufferedBits = 0
        var bits = 9
        var next = 258
        val dictionary = HashMap<Int, Int>(4096)
        fun emit(code: Int) {
            buffer = buffer or (code shl bufferedBits)
            bufferedBits += bits
            while (bufferedBits >= 8) {
                bytes.write(buffer and 255)
                buffer = buffer ushr 8
                bufferedBits -= 8
            }
        }
        fun palette(color: Int): Int = (color ushr 16 and 0xe0) or (color ushr 11 and 0x1c) or (color ushr 6 and 3)
        emit(256)
        var prefix = palette(rgb[0])
        for (index in 1 until rgb.size) {
            if (index % 4096 == 0) com.heihun.pixivplus.core.task.TaskExecution.check()
            val value = palette(rgb[index])
            val key = prefix shl 8 or value
            val known = dictionary[key]
            if (known != null) {
                prefix = known
            } else {
                emit(prefix)
                if (next < 4096) {
                    dictionary[key] = next++
                    // 编码器比解码器提前建立一项，超过边界后才增加码长。
                    if (next > (1 shl bits) && bits < 12) bits++
                } else {
                    emit(256)
                    dictionary.clear()
                    bits = 9
                    next = 258
                }
                prefix = value
            }
        }
        emit(prefix)
        if (next == (1 shl bits) && bits < 12) bits++
        emit(257)
        if (bufferedBits > 0) bytes.write(buffer and 255)
        return bytes.toByteArray()
    }
}
