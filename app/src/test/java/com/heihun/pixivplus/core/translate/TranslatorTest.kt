package com.heihun.pixivplus.core.translate

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {

    @Test
    fun `parses google translate response segments`() {
        val json = JSONArray()
            .put(
                JSONArray()
                    .put(JSONArray().put("早上好。").put("おはよう。"))
                    .put(JSONArray().put("天气不错。").put("天気がいい。"))
            )
            .toString()
        assertEquals("早上好。天气不错。", Translator.parseResponse(json))
    }

    @Test
    fun `parse response returns null for invalid json`() {
        assertEquals(null, Translator.parseResponse("not json"))
        assertEquals(null, Translator.parseResponse("[]"))
        assertEquals("", Translator.parseResponse("[[]]"))
    }

    @Test
    fun `splits text into chunks no longer than limit`() {
        val paragraph = "あ".repeat(120)
        val text = (1..30).joinToString("\n") { "$it $paragraph" }
        val chunks = Translator.splitChunks(text, 1000)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue("chunk too long: ${chunk.length}", chunk.length <= 1000)
        }
        assertEquals(text.replace("\r", "").trim('\n').length, chunks.sumOf { it.length } + chunks.size - 1)
    }

    @Test
    fun `splits overlong single paragraph at sentence marks`() {
        val sentence = "これはテストです。"
        val text = sentence.repeat(100)
        val chunks = Translator.splitChunks(text, 300)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(chunk.length <= 300)
            assertTrue(chunk.endsWith("。"))
        }
        assertEquals(text.length, chunks.sumOf { it.length })
    }

    @Test
    fun `keeps short text in one chunk`() {
        val text = "第一段\n\n第二段"
        val chunks = Translator.splitChunks(text, 3500)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun `normalizes crlf`() {
        val text = "a\r\nb\rc"
        val chunks = Translator.splitChunks(text, 3500)
        assertEquals(listOf("a\nb\nc"), chunks)
    }
}
