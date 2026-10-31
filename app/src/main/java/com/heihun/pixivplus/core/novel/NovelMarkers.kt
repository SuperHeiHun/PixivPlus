package com.heihun.pixivplus.core.novel

/**
 * pixiv 小说正文的标记块模型。
 *
 * 正文来自网页版 `ajax/novel/{id}` 的 `content` 字段，常用标记：
 * - `[newpage]` 分页
 * - `[chapter:标题]` 章节标题
 * - `[uploadedimage:图片ID]` 随作品上传的插图（URL 在 `textEmbeddedImages`）
 * - `[pixivimage:作品ID-页码]` 引用的已有插画作品
 * - `[[jumpuri:标题 > URL]]` 外部链接
 * - `[[rb:汉字 > 假名]]` 注音
 * - `[b:粗体]` / `[i:斜体]` / `[s:删除线]`
 */
sealed class NovelBlock {

    /** 分页标记。 */
    object PageBreak : NovelBlock()

    /** 章节标题。 */
    data class Chapter(val title: String) : NovelBlock()

    /** 普通文本行（已剥离行内标记）。 */
    data class Text(val line: String) : NovelBlock()

    /** 插图。kind=UPLOADED 时 markerId 是 novelImageId；kind=PIXIV 时是插画作品 ID。 */
    data class Image(
        val markerId: String,
        val kind: ImageKind,
        val page: Int = 1
    ) : NovelBlock() {
        enum class ImageKind { UPLOADED, PIXIV }
    }

    /** 外部链接。 */
    data class Link(val title: String, val url: String) : NovelBlock()
}

object NovelMarkers {

    private val NEWPAGE = Regex("^\\[newpage\\]$")
    private val CHAPTER = Regex("^\\[chapter:(.+?)]$")
    private val UPLOADED = Regex("^\\[uploadedimage:(\\d+)]$")
    private val PIXIV = Regex("^\\[pixivimage:(\\d+)(?:-(\\d+))?]$")
    private val JUMP_URI_LINE = Regex("^\\[\\[jumpuri:(.+?) > (.+?)]]$")
    private val RUBY = Regex("\\[\\[rb:(.+?) > (.+?)(?: > .*?)?]]")
    private val JUMP_URI_INLINE = Regex("\\[\\[jumpuri:(.+?) > (.+?)(?: > .*?)?]]")
    private val JUMP = Regex("\\[\\[jump:\\d+]]")
    private val STYLE = Regex("\\[([bis]):((?:[^\\[\\]]|\\[[^\\]]*\\])*)]")

    /** 把原始正文解析为块列表；无法识别的行内标记一律剥离为纯文本。 */
    fun parse(content: String): List<NovelBlock> {
        val blocks = ArrayList<NovelBlock>()
        for (rawLine in content.split('\n')) {
            val line = rawLine.trim('\r')
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> blocks += NovelBlock.Text("")
                NEWPAGE.matches(trimmed) -> blocks += NovelBlock.PageBreak
                else -> {
                    val chapter = CHAPTER.matchEntire(trimmed)?.groupValues?.get(1)
                    if (chapter != null) {
                        blocks += NovelBlock.Chapter(chapter.trim())
                        continue
                    }
                    val uploaded = UPLOADED.matchEntire(trimmed)
                    if (uploaded != null) {
                        blocks += NovelBlock.Image(uploaded.groupValues[1], NovelBlock.Image.ImageKind.UPLOADED)
                        continue
                    }
                    val pixiv = PIXIV.matchEntire(trimmed)
                    if (pixiv != null) {
                        blocks += NovelBlock.Image(
                            pixiv.groupValues[1],
                            NovelBlock.Image.ImageKind.PIXIV,
                            pixiv.groupValues[2].toIntOrNull() ?: 1
                        )
                        continue
                    }
                    val jumpUri = JUMP_URI_LINE.matchEntire(trimmed)
                    if (jumpUri != null) {
                        blocks += NovelBlock.Link(jumpUri.groupValues[1].trim(), jumpUri.groupValues[2].trim())
                        continue
                    }
                    blocks += NovelBlock.Text(cleanInline(line))
                }
            }
        }
        // 去掉开头 / 结尾的空行。
        while (blocks.firstOrNull() == NovelBlock.Text("")) blocks.removeAt(0)
        while (blocks.lastOrNull() == NovelBlock.Text("")) blocks.removeAt(blocks.size - 1)
        return blocks
    }

    /** 剥离行内标记：注音保留汉字，链接转成「标题 (URL)」，跳转删除，样式标签取内容。 */
    fun cleanInline(line: String): String {
        var text = line
        text = text.replace(RUBY) { match -> match.groupValues[1] }
        text = text.replace(JUMP_URI_INLINE) { match -> "${match.groupValues[1]} (${match.groupValues[2]})" }
        text = text.replace(JUMP, "")
        text = text.replace(STYLE) { match -> match.groupValues[2] }
        return text
    }

    /** 渲染为纯文本。 */
    fun toPlainText(title: String, author: String, sourceUrl: String, blocks: List<NovelBlock>): String {
        val builder = StringBuilder()
        builder.append("标题：").append(title).append('\n')
        if (author.isNotBlank()) builder.append("作者：").append(author).append('\n')
        if (sourceUrl.isNotBlank()) builder.append("来源：").append(sourceUrl).append('\n')
        builder.append("导出：PixivPlus").append("\n\n")

        var pageNumber = 1
        for (block in blocks) {
            when (block) {
                is NovelBlock.PageBreak -> {
                    pageNumber++
                    builder.append("\n").append("―".repeat(16)).append(" 第 ").append(pageNumber)
                        .append(" 页 ").append("―".repeat(16)).append("\n")
                }
                is NovelBlock.Chapter -> builder.append("【").append(block.title).append("】\n")
                is NovelBlock.Text -> builder.append(block.line).append('\n')
                is NovelBlock.Link -> builder.append(block.title).append(" (").append(block.url).append(")\n")
                is NovelBlock.Image -> builder.append("[插图 ").append(imageLabel(blocks, block)).append("]\n")
            }
        }
        return builder.toString().trimEnd() + "\n"
    }

    /** 插图序号（按出现顺序从 1 开始），用于 TXT 占位与 PDF 图注。 */
    fun imageLabels(blocks: List<NovelBlock>): Map<NovelBlock.Image, Int> {
        val labels = HashMap<NovelBlock.Image, Int>()
        var next = 1
        for (block in blocks) {
            if (block is NovelBlock.Image) {
                labels[block] = next
                next++
            }
        }
        return labels
    }

    private fun imageLabel(blocks: List<NovelBlock>, target: NovelBlock.Image): Int =
        imageLabels(blocks)[target] ?: 1

    /** 清理文件名中的非法字符并限制长度。 */
    fun safeFileName(raw: String, fallback: String, maxLength: Int = 80): String {
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .replace(Regex("_{2,}"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('_', ' ')
            .take(maxLength)
            .trim('_', ' ')
        return cleaned.ifEmpty { fallback }
    }

    /** 插图缓存键：随作品上传用 novelImageId，引用插画用作品 ID + 页码。 */
    fun imageKey(block: NovelBlock.Image): String = when (block.kind) {
        NovelBlock.Image.ImageKind.UPLOADED -> "up:${block.markerId}"
        NovelBlock.Image.ImageKind.PIXIV -> "px:${block.markerId}:${block.page}"
    }
}
