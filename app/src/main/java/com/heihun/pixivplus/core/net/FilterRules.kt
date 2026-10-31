package com.heihun.pixivplus.core.net

import org.json.JSONObject

class FilterRules(
    val keywords: List<String>,
    val tags: List<String>,
    val authors: List<String>,
    val caseSensitive: Boolean
) {

    val hasRules: Boolean = keywords.isNotEmpty() || tags.isNotEmpty() || authors.isNotEmpty()

    fun matches(item: JSONObject): Boolean {
        if (!hasRules) return false
        try {
            if (keywords.isNotEmpty()) {
                val title = item.optString("title")
                val caption = item.optString("caption")
                val description = item.optString("description")
                if (keywords.any { contains(title, it) || contains(caption, it) || contains(description, it) }) {
                    return true
                }
            }
            if (tags.isNotEmpty()) {
                val array = item.optJSONArray("tags")
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val tag = array.optJSONObject(index) ?: continue
                        val name = tag.optString("name")
                        val translated = tag.optString("translated_name")
                        if (tags.any { contains(name, it) || contains(translated, it) }) return true
                    }
                }
            }
            if (authors.isNotEmpty()) {
                val user = item.optJSONObject("user")
                // 纯数字规则按作者 UID 精确匹配，避免误伤昵称里的数字。
                val uid = user?.optLong("id", 0L)?.takeIf { it > 0L }
                    ?: item.optLong("user_id", 0L).takeIf { it > 0L }
                    ?: 0L
                val uidText = if (uid > 0L) uid.toString() else ""
                val name = user?.optString("name").orEmpty()
                val account = user?.optString("account").orEmpty()
                val flatName = item.optString("user_name")
                if (authors.any { rule ->
                        if (rule.all { it.isDigit() }) {
                            uidText.isNotEmpty() && uidText == rule
                        } else {
                            contains(name, rule) || contains(account, rule) || contains(flatName, rule)
                        }
                    }
                ) {
                    return true
                }
            }
        } catch (_: Throwable) {
        }
        return false
    }

    fun matchesContent(content: JSONObject): Boolean {
        if (!hasRules) return false
        val thumbnails = content.optJSONArray("thumbnails")
        if (thumbnails == null) return matches(content)
        for (index in 0 until thumbnails.length()) {
            val thumbnail = thumbnails.optJSONObject(index) ?: continue
            if (matches(thumbnail)) return true
        }
        return false
    }

    fun describe(): String {
        return "keyword=${keywords.size}, tag=${tags.size}, author=${authors.size}, caseSensitive=$caseSensitive"
    }

    private fun contains(haystack: String, needle: String): Boolean {
        if (haystack.isEmpty() || needle.isEmpty()) return false
        return if (caseSensitive) {
            haystack.contains(needle)
        } else {
            haystack.lowercase().contains(needle.lowercase())
        }
    }

    companion object {
        val EMPTY = FilterRules(emptyList(), emptyList(), emptyList(), false)

        fun parse(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            val result = LinkedHashSet<String>()
            for (line in raw.split('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    val quoted = trimmed.substring(1, trimmed.length - 1).trim()
                    if (quoted.isNotEmpty()) result.add(quoted)
                    continue
                }
                for (part in trimmed.split(',', '，', '、', ';', '；')) {
                    val value = part.trim()
                    if (value.isNotEmpty()) result.add(value)
                }
            }
            return result.toList()
        }
    }
}
