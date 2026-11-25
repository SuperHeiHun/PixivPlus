package com.heihun.pixivplus.core.translate

import java.net.URI
import java.util.Locale

/**
 * 从简介 / 译文文本中识别 pixiv 风格引用并转为可点击链接。
 *
 * 支持：
 * - `ilust/123`、`illust/123`、`illusts/123`、`artworks/123`、`artwork/123`（含全角斜杠）
 * - `novel/456`、`novels/456`
 * - `user/789`、`users/789`
 * - `pixiv.net/novel/show.php?id=456` 等 pixiv 页面地址
 * - 任意 `http(s)://` 链接
 *
 * pixiv 引用点击时优先走 `pixiv://` 深链（在宿主 App 内直接打开对应作品），
 * 深链无法处理时退回网页地址。同一段文本只生成一个链接（先到先得），
 * 完整 URL 的优先级高于其中包含的 `artworks/123` 片段。
 */
object CaptionLinks {

    /** 文本区间 [start, end) 上的链接；deepLink 优先尝试，失败退回 webUrl。 */
    data class Link(val start: Int, val end: Int, val deepLink: String, val webUrl: String)
    data class Range(val start: Int, val end: Int)

    private val NOVEL_SHOW = Regex("(?i)(?<![\\w.@/-])(?:www\\.)?pixiv\\.net/novel/show\\.php\\?id=(\\d+)")
    private val ILLUST = Regex("(?i)\\b(?:il{1,2}usts?|artworks?)\\s*[/／]\\s*(\\d+)")
    private val NOVEL = Regex("(?i)\\bnovels?\\s*[/／]\\s*(\\d+)")
    private val USER = Regex("(?i)\\busers?\\s*[/／]\\s*(\\d+)")
    private val URL = Regex("(?:https?|pixiv)://[^\\s\\u3000-\\u303F\\uFF01-\\uFF5E<>\"']+", RegexOption.IGNORE_CASE)
    private val PIXIV_PATH = Regex("^/(?:[a-zA-Z]{2}(?:-[a-zA-Z]{2})?/)?(artworks?|il{1,2}usts?|novels?|users?)/(\\d+)/?$", RegexOption.IGNORE_CASE)
    private val BARE_PIXIV = Regex("^(?:www\\.)?pixiv\\.net/", RegexOption.IGNORE_CASE)
    private val BARE_REFERENCE = Regex("^(?:il{1,2}usts?|artworks?|novels?|users?)/\\d+$", RegexOption.IGNORE_CASE)
    private val NOVEL_SHOW_PATH = Regex("^/(?:[a-zA-Z]{2}/)?novel/show\\.php$", RegexOption.IGNORE_CASE)
    private val NOVEL_ID_QUERY = Regex("(?:^|&)id=(\\d+)(?:&|$)", RegexOption.IGNORE_CASE)
    private val PIXIV_BASE = URI("https://www.pixiv.net/")

    /** 链接收尾需要剥离的标点（中文 / 英文常见）。 */
    private const val TRAILING = "。、，；：！？）】」』》.,;:!?)]}>\"'’”"

    /** 找出文本中的全部链接，按出现位置排序。 */
    fun find(text: String): List<Link> {
        if (text.length < 6) return emptyList()
        val occupied = BooleanArray(text.length)
        val result = ArrayList<Link>()

        fun claim(start: Int, end: Int, deepLink: String, webUrl: String) {
            if (start < 0 || end > text.length || start >= end) return
            for (index in start until end) {
                if (occupied[index]) return
            }
            for (index in start until end) {
                occupied[index] = true
            }
            result.add(Link(start, end, deepLink, webUrl))
        }

        // 1) 完整 URL 优先，pixiv 地址在此基础上升级为深链。
        for (match in URL.findAll(text)) {
            var start = match.range.first
            var end = match.range.last + 1
            while (end > start + 1) {
                val last = text[end - 1]
                if (last !in TRAILING) break
                if (last == ')') {
                    // 括号配对的收尾括号属于 URL 本身（如 example.com/a(b)），不剥离。
                    val segment = text.substring(start, end)
                    if (segment.count { it == '(' } >= segment.count { it == ')' }) break
                }
                end--
            }
            val raw = text.substring(start, end)
            val link = fromUrl(raw, start, end)
            if (link != null) claim(start, end, link.deepLink, link.webUrl)
            else for (index in start until end) occupied[index] = true
        }
        // 2) 纯文本引用（无 scheme），完整 URL 已占用的区间跳过。
        for (match in NOVEL_SHOW.findAll(text)) {
            val id = match.groupValues[1]
            if (!validId(id)) continue
            claim(
                match.range.first,
                match.range.last + 1,
                "pixiv://novels/$id",
                "https://www.pixiv.net/novel/show.php?id=$id"
            )
        }
        for (match in ILLUST.findAll(text)) {
            val id = match.groupValues[1]
            if (!validId(id) || embeddedReference(text, match.range.first)) continue
            claim(
                match.range.first,
                match.range.last + 1,
                "pixiv://illusts/$id",
                "https://www.pixiv.net/artworks/$id"
            )
        }
        for (match in NOVEL.findAll(text)) {
            val id = match.groupValues[1]
            if (!validId(id) || embeddedReference(text, match.range.first)) continue
            claim(
                match.range.first,
                match.range.last + 1,
                "pixiv://novels/$id",
                "https://www.pixiv.net/novel/show.php?id=$id"
            )
        }
        for (match in USER.findAll(text)) {
            val id = match.groupValues[1]
            if (!validId(id) || embeddedReference(text, match.range.first)) continue
            claim(
                match.range.first,
                match.range.last + 1,
                "pixiv://users/$id",
                "https://www.pixiv.net/users/$id"
            )
        }
        return result.sortedBy { it.start }
    }

    /** HTML href 与完整 URL 共用；仅允许网页地址或已知 Pixiv 作品/用户引用。 */
    fun fromUrl(value: String, start: Int, end: Int): Link? {
        if (start < 0 || end <= start) return null
        val raw = value.trim()
        val url = try {
            when {
                raw.startsWith("/") -> PIXIV_BASE.resolve(raw).toString()
                BARE_PIXIV.containsMatchIn(raw) -> "https://$raw"
                BARE_REFERENCE.matches(raw) -> "https://www.pixiv.net/$raw"
                else -> raw
            }
        } catch (_: Exception) { return null }
        val uri = try { URI(url) } catch (_: Exception) { return null }
        if (uri.scheme.equals("pixiv", true)) {
            val type = uri.host?.lowercase(Locale.ROOT) ?: return null
            val id = uri.path?.removePrefix("/") ?: return null
            if (type !in setOf("illusts", "novels", "users") || !validId(id) || uri.userInfo != null || uri.port != -1) return null
            return reference(start, end, type, id)
        }
        if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null
        if (uri.host.isNullOrBlank() || (uri.port != -1 && uri.port !in 1..65535)) return null
        var deepLink = url
        if (uri.userInfo == null && uri.host.lowercase(Locale.ROOT) in setOf("pixiv.net", "www.pixiv.net") && uri.port in listOf(-1, 80, 443)) {
            val path = uri.path.orEmpty()
            val matched = PIXIV_PATH.matchEntire(path)
            if (matched != null && validId(matched.groupValues[2])) {
                val type = when (matched.groupValues[1].lowercase(Locale.ROOT)) {
                    "novel", "novels" -> "novels"
                    "user", "users" -> "users"
                    else -> "illusts"
                }
                deepLink = "pixiv://$type/${matched.groupValues[2]}"
            } else if (NOVEL_SHOW_PATH.matches(path)) {
                val id = NOVEL_ID_QUERY.find(uri.rawQuery.orEmpty())?.groupValues?.get(1)
                if (id != null && validId(id)) deepLink = "pixiv://novels/$id"
            }
        }
        return Link(start, end, deepLink, url)
    }

    /** 原有可点击 span、HTML anchor 优先，新增引用不得覆盖或交叉。 */
    fun excluding(links: List<Link>, occupied: List<Range>): List<Link> = links.filter { link ->
        occupied.none { it.start < link.end && link.start < it.end }
    }

    private fun validId(id: String): Boolean = id.all { it in '0'..'9' } && (id.toLongOrNull() ?: 0L) > 0L
    private fun embeddedReference(text: String, start: Int): Boolean = start > 0 && text[start - 1] in "/.@\\"

    private fun reference(start: Int, end: Int, type: String, id: String): Link {
        val web = when (type) {
            "illusts" -> "https://www.pixiv.net/artworks/$id"
            "novels" -> "https://www.pixiv.net/novel/show.php?id=$id"
            else -> "https://www.pixiv.net/users/$id"
        }
        return Link(start, end, "pixiv://$type/$id", web)
    }
}
