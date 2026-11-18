package com.heihun.pixivplus.core.novel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelExtractorTest {

    private fun tokens(vararg tokens: JSONObject): JSONArray {
        val array = JSONArray()
        tokens.forEach { array.put(it) }
        return array
    }

    private fun x(text: String) = JSONObject().put("t", "x").put("s", text)
    private fun h(text: String) = JSONObject().put("t", "h").put("s", text)
    private fun n() = JSONObject().put("t", "n")
    private fun p() = JSONObject().put("t", "p")
    private fun i(url: String) = JSONObject().put("t", "i").put("s", url)

    @Test
    fun `keeps blank lines between paragraphs`() {
        val blocks = NovelExtractor.blocksFromTokens(
            tokens(x("第一行"), n(), n(), x("第二行"), n())
        )
        assertEquals(3, blocks.size)
        assertEquals("第一行", (blocks[0] as NovelBlock.Text).line)
        assertEquals(NovelBlock.Text(""), blocks[1])
        assertEquals("第二行", (blocks[2] as NovelBlock.Text).line)
    }

    @Test
    fun `heading token becomes chapter block`() {
        val blocks = NovelExtractor.blocksFromTokens(
            tokens(n(), h("第一章"), n(), x("本文第一行"), n())
        )
        assertEquals(2, blocks.size)
        assertEquals(NovelBlock.Chapter("第一章"), blocks[0])
        assertEquals("本文第一行", (blocks[1] as NovelBlock.Text).line)
    }

    @Test
    fun `page tokens become page breaks and merge consecutive`() {
        val blocks = NovelExtractor.blocksFromTokens(
            tokens(p(), x("A"), n(), p(), p(), x("B"), n(), p())
        )
        assertEquals(3, blocks.size)
        assertEquals("A", (blocks[0] as NovelBlock.Text).line)
        assertEquals(NovelBlock.PageBreak, blocks[1])
        assertEquals("B", (blocks[2] as NovelBlock.Text).line)
    }

    @Test
    fun `image token becomes picture block between text lines`() {
        val blocks = NovelExtractor.blocksFromTokens(
            tokens(x("前文"), n(), i("https://embed.example/i.jpg"), n(), x("后文"), n())
        )
        assertEquals(3, blocks.size)
        assertEquals(NovelBlock.Picture("https://embed.example/i.jpg"), blocks[1])
    }

    @Test
    fun `leading and trailing blanks are trimmed`() {
        val blocks = NovelExtractor.blocksFromTokens(
            tokens(n(), n(), x("正文"), n(), n(), n())
        )
        assertEquals(1, blocks.size)
        assertEquals("正文", (blocks[0] as NovelBlock.Text).line)
    }

    @Test
    fun `multi-line text token splits into lines`() {
        val blocks = NovelExtractor.blocksFromTokens(tokens(x("一行\n二行"), n()))
        assertEquals(2, blocks.size)
        assertEquals("一行", (blocks[0] as NovelBlock.Text).line)
        assertEquals("二行", (blocks[1] as NovelBlock.Text).line)
    }

    @Test
    fun `plain text blocks export with blank lines via markers`() {
        val blocks = NovelExtractor.blocksFromTokens(
            tokens(x("第一段"), n(), n(), x("第二段"), n())
        )
        val plain = NovelMarkers.toPlainText("标题", "作者", "", blocks)
        assertTrue(plain.contains("第一段\n\n第二段"))
    }

    @Test
    fun `author blank line after image survives`() {
        val blocks = NovelExtractor.blocksFromTokens(
            tokens(x("前文"), n(), i("https://embed.example/i.jpg"), n(), n(), x("后文"), n())
        )
        assertEquals(4, blocks.size)
        assertEquals("前文", (blocks[0] as NovelBlock.Text).line)
        assertEquals(NovelBlock.Picture("https://embed.example/i.jpg"), blocks[1])
        assertEquals(NovelBlock.Text(""), blocks[2])
        assertEquals("后文", (blocks[3] as NovelBlock.Text).line)
    }

    @Test
    fun `empty heading text is skipped`() {
        val blocks = NovelExtractor.blocksFromTokens(tokens(h(""), n(), x("正文"), n()))
        assertEquals(1, blocks.size)
        assertEquals("正文", (blocks[0] as NovelBlock.Text).line)
    }
}
