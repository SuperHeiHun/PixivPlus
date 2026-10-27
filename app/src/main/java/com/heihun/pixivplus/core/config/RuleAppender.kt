package com.heihun.pixivplus.core.config

object RuleAppender {

    fun append(current: String?, values: List<String>): String {
        val lines = ArrayList<String>()
        val existing = current.orEmpty().trim()
        if (existing.isNotEmpty()) lines.add(existing)
        for (value in values) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) continue
            val needsQuote = trimmed.contains(',') || trimmed.contains('\n') || trimmed.contains(';')
            lines.add(if (needsQuote) quote(trimmed) else trimmed)
        }
        return lines.joinToString("\n")
    }

    fun quote(value: String): String {
        return "\"" + value.replace("\"", "'") + "\""
    }
}
