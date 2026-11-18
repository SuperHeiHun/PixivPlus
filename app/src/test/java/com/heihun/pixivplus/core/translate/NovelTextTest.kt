package com.heihun.pixivplus.core.translate

import com.heihun.pixivplus.core.novel.NovelBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTextTest {

    private fun text(value: String) = NovelBlock.Text(value)

    @Test
    fun `translated export retains chapters blanks images and pages`() {
        val blocks = NovelText.toBlocks(listOf(
            NovelText.Line.Chapter("译文章节"), NovelText.Line.Text("译文正文"),
            NovelText.Line.Blank, NovelText.Line.Picture("https://example.com/i.jpg"),
            NovelText.Line.PageBreak
        ))
        assertEquals(listOf(
            NovelBlock.Chapter("译文章节"), text("译文正文"), text(""),
            NovelBlock.Picture("https://example.com/i.jpg"), NovelBlock.PageBreak
        ), blocks)
    }

    @Test
    fun `many short paragraphs use one request and retain local blanks`() {
        val blocks = (1..100).flatMap { listOf(text("段落$it"), text("")) }
        val lines = NovelText.buildLines(blocks)
        val groups = NovelText.group(lines)
        assertEquals(1, groups.size)
        groups[0].outputs.addAll((1..100).map { "译文$it" })
        val rendered = NovelText.render(lines, groups)
        assertEquals(100, rendered.count { it is NovelText.Line.Text })
        assertEquals(99, rendered.count { it is NovelText.Line.Blank })
    }

    @Test
    fun `build lines keeps blank lines and page breaks`() {
        val lines = NovelText.buildLines(
            listOf(
                text("第一行"),
                text(""),
                text("第二行"),
                NovelBlock.PageBreak,
                text(""),
                NovelBlock.Picture("https://embed.example/i.jpg")
            )
        )
        assertEquals(6, lines.size)
        assertTrue(lines[0] is NovelText.Line.Text)
        assertTrue(lines[1] is NovelText.Line.Blank)
        assertTrue(lines[2] is NovelText.Line.Text)
        assertTrue(lines[3] is NovelText.Line.PageBreak)
        assertTrue(lines[4] is NovelText.Line.Blank)
        assertTrue(lines[5] is NovelText.Line.Picture)
    }

    @Test
    fun `build lines splits overlong single line`() {
        val longLine = "あ".repeat(100)
        val lines = NovelText.buildLines(listOf(text(longLine)), maxChars = 30)
        assertEquals(4, lines.size)
        assertTrue(lines.all { it is NovelText.Line.Text })
    }

    @Test
    fun `group batches across blanks and stops at images`() {
        val lines = NovelText.buildLines(
            listOf(text("A"), text("B"), text(""), text("C"), NovelBlock.Picture("https://x/i.jpg"), text("D"))
        )
        val groups = NovelText.group(lines, maxChars = 100)
        assertEquals(2, groups.size)
        assertEquals("A\nB\n\nC", groups[0].text)
        assertEquals(0, groups[0].startIndex)
        assertEquals(4, groups[0].lineCount)
        assertEquals("D", groups[1].text)
        assertEquals(5, groups[1].startIndex)
    }

    @Test
    fun `group splits when chunk limit reached`() {
        val lines = NovelText.buildLines(listOf(text("あ".repeat(30)), text("い".repeat(30))), maxChars = 50)
        val groups = NovelText.group(lines, maxChars = 50)
        assertEquals(2, groups.size)
    }

    @Test
    fun `render maps output lines one to one keeping structure`() {
        val lines = NovelText.buildLines(
            listOf(text("A"), text(""), text("B"), NovelBlock.Picture("https://x/i.jpg"), text("C"))
        )
        val groups = NovelText.group(lines)
        assertEquals(2, groups.size)
        groups[0].outputs.addAll(listOf("甲", "", "乙"))
        groups[1].outputs.add("丙")
        val rendered = NovelText.render(lines, groups)
        assertEquals(5, rendered.size)
        assertTrue((rendered[0] as NovelText.Line.Text).value == "甲")
        assertTrue(rendered[1] is NovelText.Line.Blank)
        assertTrue((rendered[2] as NovelText.Line.Text).value == "乙")
        assertTrue(rendered[3] is NovelText.Line.Picture)
        assertTrue((rendered[4] as NovelText.Line.Text).value == "丙")
    }

    @Test
    fun `render degrades to raw output lines when count mismatches`() {
        val lines = NovelText.buildLines(listOf(text("A"), text("B"), text("C")))
        val groups = NovelText.group(lines)
        groups[0].outputs.addAll(listOf("合并的一行"))
        val rendered = NovelText.render(lines, groups)
        assertEquals(1, rendered.size)
        assertEquals("合并的一行", (rendered[0] as NovelText.Line.Text).value)
    }

    @Test
    fun `render trims leading and trailing blanks`() {
        val lines = NovelText.buildLines(
            listOf(text(""), text("A"), text(""), NovelBlock.PageBreak, text(""))
        )
        val groups = NovelText.group(lines)
        groups[0].outputs.add("甲")
        val rendered = NovelText.render(lines, groups)
        assertEquals(1, rendered.size)
        assertTrue(rendered[0] is NovelText.Line.Text)
    }

    @Test
    fun `render translates chapter title`() {
        val lines = NovelText.buildLines(listOf(NovelBlock.Chapter("第一章"), text("本文")))
        val groups = NovelText.group(lines)
        assertEquals(1, groups.size)
        assertEquals("第一章\n本文", groups[0].text)
        groups[0].outputs.addAll(listOf("第一章", "正文"))
        val rendered = NovelText.render(lines, groups)
        assertEquals("第一章", (rendered[0] as NovelText.Line.Chapter).value)
        assertEquals("正文", (rendered[1] as NovelText.Line.Text).value)
    }

    @Test
    fun `to html escapes text and inlines images`() {
        val html = NovelText.toHtml(
            listOf(
                NovelText.Line.Text("<b> & 特殊"),
                NovelText.Line.Blank,
                NovelText.Line.Picture("https://x/i.jpg?a=1&b=2"),
                NovelText.Line.PageBreak,
                NovelText.Line.Chapter("章节")
            )
        )
        assertTrue(html.contains("&lt;b&gt; &amp; 特殊"))
        assertTrue(html.contains("height:0.9em"))
        assertTrue(html.contains("src=\"https://x/i.jpg?a=1&amp;b=2\""))
        assertTrue(html.contains("dashed"))
        assertTrue(html.contains(">章节</div>"))
    }
}
