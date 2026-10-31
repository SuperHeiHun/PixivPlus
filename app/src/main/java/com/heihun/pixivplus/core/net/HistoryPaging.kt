package com.heihun.pixivplus.core.net

/**
 * 浏览记录桥接的分页参数处理。
 *
 * Pixiv 的浏览记录接口不支持 offset / limit，App 完全依赖响应里的 `next_url`
 * 翻页。桥接需要用本地历史逐页填充响应，因此：
 * - 在服务端 `next_url` 上附加私有参数 `plp_offset`，记录本地记录取到第几条；
 * - 本地记录未取完时继续生成带 `plp_offset` 的 `next_url`，否则保留服务端原值。
 * 如果每页都塞入同一批本地记录，App 去重后只会显示最近约 30 条。
 */
object HistoryPaging {

    const val DEFAULT_LIMIT = 30
    const val MAX_LIMIT = 100

    /** 桥接自己附加的分页参数，避免覆盖服务端 next_url 上的游标 / offset。 */
    private const val OFFSET_PARAM = "plp_offset"

    fun localOffsetOf(url: String?): Int {
        return queryValue(url, OFFSET_PARAM)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    fun limitOf(url: String?): Int {
        val value = queryValue(url, "limit")?.toIntOrNull() ?: return DEFAULT_LIMIT
        if (value <= 0) return DEFAULT_LIMIT
        return value.coerceAtMost(MAX_LIMIT)
    }

    /** 保留原 URL 的其它参数，只替换 / 追加本地的 plp_offset。 */
    fun withLocalOffset(url: String?, offset: Int): String? {
        if (url.isNullOrBlank()) return null
        val queryIndex = url.indexOf('?')
        if (queryIndex < 0) return "$url?$OFFSET_PARAM=${offset.coerceAtLeast(0)}"
        val base = url.substring(0, queryIndex)
        val fragmentIndex = url.indexOf('#', queryIndex)
        val query = if (fragmentIndex >= 0) {
            url.substring(queryIndex + 1, fragmentIndex)
        } else {
            url.substring(queryIndex + 1)
        }
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val parts = if (query.isEmpty()) mutableListOf() else query.split('&').toMutableList()
        var replaced = false
        for (index in parts.indices) {
            if (parts[index].substringBefore('=') == OFFSET_PARAM) {
                parts[index] = "$OFFSET_PARAM=${offset.coerceAtLeast(0)}"
                replaced = true
            }
        }
        if (!replaced) parts += "$OFFSET_PARAM=${offset.coerceAtLeast(0)}"
        return base + "?" + parts.joinToString("&") + fragment
    }

    private fun queryValue(url: String?, name: String): String? {
        if (url.isNullOrBlank()) return null
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            if (part.substringBefore('=') != name) continue
            val raw = part.substringAfter('=', "")
            return try {
                java.net.URLDecoder.decode(raw, "UTF-8")
            } catch (_: Throwable) {
                raw
            }
        }
        return null
    }
}
