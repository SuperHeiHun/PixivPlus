package com.heihun.pixivplus.core.translate

import com.heihun.pixivplus.core.novel.NovelBlock

/**
 * 小说翻译的结构保留管线（纯 Kotlin，无 Android 依赖，可单测）。
 *
 * 排版修复的核心思路：正文按「结构行」建模，空行、插图、分页、章节标题
 * 完全由本地保留，只把连续的文本行分组送翻译；译文按组回填，组内行数
 * 对不上时整组降级为原始译文行，保证任何情况下都不丢内容。
 *
 * 渲染端输出 HTML（逐行 div），配合阅读页覆盖层脚本实现：
 * - 原文 DOM 不销毁（隐藏在包装层里），配图与宿主交互完整保留；
 * - 译文中插图按原位置内联显示。
 */
object NovelText {

    /** 结构行。 */
    sealed class Line {
        /** 空行（作者排版留白）。 */
        object Blank : Line()

        /** 普通文本行；翻译后 [value] 被覆盖。 */
        class Text(var value: String) : Line()

        /** 章节标题行；翻译后 [value] 被覆盖。 */
        class Chapter(var value: String) : Line()

        /** 正文插图（阅读页 DOM 提取的图片 URL）。 */
        class Picture(val url: String) : Line()

        /** 分页（[newpage]）。 */
        object PageBreak : Line()
    }

    /**
     * 一次翻译请求覆盖的行组：[startIndex] 起连续 [lineCount] 行（文本 / 标题 / 空行），
     * [text] 为组内原文按换行拼接；[outputs] 为译文行（通常与行数一致）。
     */
    class Group(val startIndex: Int, val lineCount: Int, val text: String) {
        val outputs = ArrayList<String>()
    }

    /** 排版块 → 结构行；超长单行按单请求上限硬切（罕见，仅防单请求超限）。 */
    fun buildLines(blocks: List<NovelBlock>, maxChars: Int = Translator.MAX_CHUNK_CHARS): List<Line> {
        val lines = ArrayList<Line>()
        for (block in blocks) {
            when (block) {
                is NovelBlock.Text ->
                    if (block.line.isBlank()) lines.add(Line.Blank) else lines.add(Line.Text(block.line))
                is NovelBlock.Chapter -> lines.add(Line.Chapter(block.title))
                is NovelBlock.Picture -> lines.add(Line.Picture(block.url))
                is NovelBlock.PageBreak -> lines.add(Line.PageBreak)
                else -> Unit
            }
        }
        val prepared = ArrayList<Line>(lines.size)
        for (line in lines) {
            if (line is Line.Text && line.value.length > maxChars) {
                line.value.chunked(maxChars).forEach { prepared.add(Line.Text(it)) }
            } else {
                prepared.add(line)
            }
        }
        return prepared
    }

    /**
     * 把连续的文本 / 标题行合并为翻译组（单组不超过 [maxChars]），
     * 跨空行合并，减少短段落小说的请求数；插图 / 分页仍是分隔点。
     */
    fun group(lines: List<Line>, maxChars: Int = Translator.MAX_CHUNK_CHARS): List<Group> {
        val groups = ArrayList<Group>()
        var index = 0
        while (index < lines.size) {
            val first = lines[index]
            if (first !is Line.Text && first !is Line.Chapter) {
                index++
                continue
            }
            val start = index
            val builder = StringBuilder()
            var count = 0
            while (index < lines.size) {
                val value = when (val line = lines[index]) {
                    is Line.Text -> line.value
                    is Line.Chapter -> line.value
                    is Line.Blank -> ""
                    else -> break
                }
                if (builder.isNotEmpty() && builder.length + value.length + 1 > maxChars) break
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(value)
                count++
                index++
            }
            groups.add(Group(start, count, builder.toString()))
        }
        return groups
    }

    /**
     * 用译文回填结构行：
     * - 译文行数与组行数一致 → 逐行对应（空行 / 插图 / 分页位置不动）；
     * - 不一致（Google 合并 / 拆行了）→ 整组降级为译文行原样输出，保内容不保行距。
     */
    fun render(lines: List<Line>, groups: List<Group>): List<Line> {
        val startMap = HashMap<Int, Group>()
        for (group in groups) startMap[group.startIndex] = group
        val rendered = ArrayList<Line>(lines.size)
        var index = 0
        while (index < lines.size) {
            val group = startMap[index]
            if (group == null) {
                rendered.add(lines[index])
                index++
                continue
            }
            val sourceLines = lines.subList(index, index + group.lineCount)
            val outputs = group.outputs.filter { it.isNotBlank() }
            val textCount = sourceLines.count { it is Line.Text || it is Line.Chapter }
            if (outputs.size == textCount) {
                var outputIndex = 0
                for (offset in 0 until group.lineCount) {
                    when (val source = sourceLines[offset]) {
                        is Line.Chapter -> rendered.add(Line.Chapter(outputs[outputIndex++]))
                        is Line.Text -> rendered.add(Line.Text(outputs[outputIndex++]))
                        else -> rendered.add(source)
                    }
                }
            } else {
                // 行数对不上：整组降级为译文行原样输出，保内容不保行距。
                for (value in group.outputs) rendered.add(Line.Text(value))
            }
            index += group.lineCount
        }
        // 去掉首尾空行 / 分页，译文版面从正文开始。
        fun isEdge(line: Line): Boolean = when (line) {
            is Line.Text -> line.value.isBlank()
            else -> line is Line.Blank || line is Line.PageBreak
        }
        while (rendered.isNotEmpty() && isEdge(rendered.first())) rendered.removeAt(0)
        while (rendered.isNotEmpty() && isEdge(rendered.last())) rendered.removeAt(rendered.size - 1)
        return rendered
    }

    /** 结构行 → 阅读页译文 HTML（逐行 div；空行用定高 div 占位，插图内联）。 */
    fun toHtml(lines: List<Line>): String {
        val builder = StringBuilder()
        for (line in lines) {
            when (line) {
                is Line.Blank -> builder.append(BLANK_DIV)
                is Line.Text ->
                    if (line.value.isBlank()) builder.append(BLANK_DIV)
                    else builder.append("<div>").append(escapeHtml(line.value)).append("</div>")
                is Line.Chapter -> builder
                    .append("<div style=\"font-weight:700;font-size:1.15em;margin:0.6em 0;text-align:center\">")
                    .append(escapeHtml(line.value))
                    .append("</div>")
                is Line.Picture -> builder
                    .append("<div style=\"margin:0.6em 0;text-align:center\"><img src=\"")
                    .append(escapeAttr(line.url))
                    .append("\" alt=\"\" style=\"max-width:100%;height:auto\"/></div>")
                is Line.PageBreak -> builder
                    .append("<div style=\"margin:1.2em 0;border-top:1px dashed currentColor;opacity:0.4\"></div>")
            }
        }
        return builder.toString()
    }

    private const val BLANK_DIV = "<div style=\"height:0.9em\"></div>"

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttr(text: String): String = escapeHtml(text).replace("\"", "%22")
}
