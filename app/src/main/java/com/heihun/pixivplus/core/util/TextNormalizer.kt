package com.heihun.pixivplus.core.util

import java.text.Normalizer
import java.util.Locale

object TextNormalizer {

    fun normalize(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        var text = Normalizer.normalize(value, Normalizer.Form.NFKC)
        text = text.lowercase(Locale.ROOT)
        return text.trim()
    }

    fun normalizeTags(tags: Collection<String>): List<String> {
        return tags.map { normalize(it) }.filter { it.isNotEmpty() }.distinct()
    }
}
