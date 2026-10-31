package com.heihun.pixivplus.core.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelMarkersTest {

    @Test
    fun `parses page break and chapter`() {
        val blocks = NovelMarkers.parse("[chapter:序章]\n正文第一行\n\n[newpage]\n正文第二行")
        assertEquals(
            listOf(
                NovelBlock.Chapter("序章"),
                NovelBlock.Text("正文第一行"),
                NovelBlock.Text(""),
                NovelBlock.PageBreak,
                NovelBlock.Text("正文第二行")
            ),
            blocks
        )
    }

    @Test
    fun `parses uploaded and pixiv images`() {
        val blocks = NovelMarkers.parse("[uploadedimage:25695760]\n[pixivimage:92241210-3]\n[pixivimage:92241210]")
        assertEquals(
            listOf(
                NovelBlock.Image("25695760", NovelBlock.Image.ImageKind.UPLOADED),
                NovelBlock.Image("92241210", NovelBlock.Image.ImageKind.PIXIV, 3),
                NovelBlock.Image("92241210", NovelBlock.Image.ImageKind.PIXIV, 1)
            ),
            blocks
        )
        assertEquals("up:25695760", NovelMarkers.imageKey(blocks[0] as NovelBlock.Image))
        assertEquals("px:92241210:3", NovelMarkers.imageKey(blocks[1] as NovelBlock.Image))
    }

    @Test
    fun `parses standalone jumpuri as link`() {
        val blocks = NovelMarkers.parse("[[jumpuri:官方页面 > https://example.com/a]]")
        assertEquals(
            listOf(NovelBlock.Link("官方页面", "https://example.com/a")),
            blocks
        )
    }

    @Test
    fun `strips inline markers and keeps text`() {
        assertEquals("漢字（かな）つづき", NovelMarkers.cleanInline("[[rb:漢字（かな） > かんじ（かな）]]つづき"))
        assertEquals("粗体文字", NovelMarkers.cleanInline("[b:粗体文字]"))
        assertEquals("斜体", NovelMarkers.cleanInline("[i:斜体]"))
        assertEquals("删除线", NovelMarkers.cleanInline("[s:删除线]"))
        assertEquals("看这里", NovelMarkers.cleanInline("[[jump:2]]看这里"))
        assertEquals(
            "前缀 说明 (https://x.test) 后缀",
            NovelMarkers.cleanInline("前缀 [[jumpuri:说明 > https://x.test]] 后缀")
        )
    }

    @Test
    fun `plain text contains header divider chapter and image placeholder`() {
        val blocks = NovelMarkers.parse("[chapter:第一章]\n你好\n[uploadedimage:100]\n[newpage]\n世界")
        val text = NovelMarkers.toPlainText("标题一", "作者A", "https://u.test/1", blocks)
        assertTrue(text.startsWith("标题：标题一\n"))
        assertTrue(text.contains("作者：作者A"))
        assertTrue(text.contains("来源：https://u.test/1"))
        assertTrue(text.contains("【第一章】"))
        assertTrue(text.contains("[插图 1]"))
        assertTrue(text.contains("第 2 页"))
        assertTrue(text.contains("世界"))
    }

    @Test
    fun `image labels are sequential by appearance`() {
        val blocks = NovelMarkers.parse(
            "[pixivimage:1]\n文本\n[uploadedimage:200]\n[newpage]\n[pixivimage:1-2]"
        )
        val labels = NovelMarkers.imageLabels(blocks)
        assertEquals(1, labels[blocks[0]])
        assertEquals(2, labels[blocks[2]])
        assertEquals(3, labels[blocks[4]])
    }

    @Test
    fun `trims blank lines at both ends`() {
        val blocks = NovelMarkers.parse("\n\n\n  \n正文\n\n\n")
        assertEquals(listOf(NovelBlock.Text("正文")), blocks)
    }

    @Test
    fun `safe file name strips invalid chars and caps length`() {
        assertEquals("a_b_c", NovelMarkers.safeFileName("a/b\\:c", "fb"))
        assertEquals("fb", NovelMarkers.safeFileName("   ", "fb"))
        assertEquals(80, NovelMarkers.safeFileName("标".repeat(200), "fb").length)
        assertEquals("标题 作品", NovelMarkers.safeFileName(" 标题  作品 ", "fb"))
    }
}
