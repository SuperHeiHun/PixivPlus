package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.util.PageImage
import org.json.JSONArray
import org.json.JSONObject

object PageImageCodec {

    fun encode(images: List<PageImage>): String? {
        if (images.isEmpty()) return null
        return toJson(images).toString()
    }

    fun decode(raw: String?): List<PageImage> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            fromJson(JSONArray(raw))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun toJson(images: List<PageImage>): JSONArray {
        val array = JSONArray()
        for (image in images) {
            val urls = JSONObject()
            put(urls, "original", image.original)
            put(urls, "large", image.large)
            put(urls, "medium", image.medium)
            put(urls, "square_medium", image.medium)
            array.put(JSONObject().put("image_urls", urls))
        }
        return array
    }

    fun fromJson(array: JSONArray?): List<PageImage> {
        if (array == null || array.length() == 0) return emptyList()
        val result = ArrayList<PageImage>()
        for (index in 0 until array.length()) {
            val page = array.optJSONObject(index) ?: continue
            val urls = page.optJSONObject("image_urls") ?: continue
            val original = stringOrNull(urls, "original")
            val large = stringOrNull(urls, "large")
            val medium = stringOrNull(urls, "medium")
            if (original == null && large == null && medium == null) continue
            result.add(
                PageImage(
                    original = original,
                    large = large,
                    medium = medium,
                    width = page.optInt("width", 0),
                    height = page.optInt("height", 0)
                )
            )
        }
        return result
    }

    fun stringOrNull(target: JSONObject, key: String): String? {
        if (!target.has(key) || target.isNull(key)) return null
        return target.optString(key).trim().ifEmpty { null }
    }

    private fun put(target: JSONObject, key: String, value: String?) {
        if (value.isNullOrBlank()) target.put(key, JSONObject.NULL) else target.put(key, value)
    }
}
