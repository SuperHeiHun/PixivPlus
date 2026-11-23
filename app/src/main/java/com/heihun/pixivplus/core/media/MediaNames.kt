package com.heihun.pixivplus.core.media

import com.heihun.pixivplus.core.i18n.DownloadText as D
/** 作者_标题命名；按 UTF-8 字节限制长度，兼容常见文件系统，保留 Unicode 字符。 */
object MediaNames {
    const val DEFAULT_TEMPLATE = "{author}_{title}"

    fun validateTemplate(template: String) {
        require(template.isNotBlank() && template.length <= 200) { D.BAD_NAME_LENGTH() }
        val literals = template.replace("{author}", "").replace("{title}", "").replace("{id}", "")
        require('{' !in literals && '}' !in literals) { D.BAD_NAME_PLACEHOLDERS() }
    }

    fun stem(author: String?, title: String?, id: Long, template: String = DEFAULT_TEMPLATE): String {
        validateTemplate(template)
        val values = mapOf("author" to component(author, "未知作者", 60), "title" to component(title, "作品$id", 120), "id" to id.toString())
        // Android ICU 要求字面量左右花括号都转义，不能依赖桌面 JVM 的宽松语法。
        val resolved = Regex("\\{(author|title|id)\\}").replace(template) { values.getValue(it.groupValues[1]) }
        return component(resolved, "作品$id", 180)
    }

    fun image(stem: String, image: OriginalImage): String =
        "${stem}_p${image.page}.${image.fileName.substringAfterLast('.').lowercase()}"

    private fun component(value: String?, fallback: String, maxBytes: Int): String {
        val cleaned = value.orEmpty().replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_")
            .replace(Regex("\\s+"), " ").trim(' ', '.', '_').ifBlank { fallback }
        val result = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < cleaned.length) {
            val code = cleaned.codePointAt(index)
            val size = when { code <= 0x7f -> 1; code <= 0x7ff -> 2; code <= 0xffff -> 3; else -> 4 }
            if (bytes + size > maxBytes) break
            result.appendCodePoint(code)
            bytes += size
            index += Character.charCount(code)
        }
        return result.toString().trimEnd(' ', '.', '_').ifBlank { fallback }
    }
}
