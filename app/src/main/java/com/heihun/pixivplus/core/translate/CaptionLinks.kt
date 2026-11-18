package com.heihun.pixivplus.core.translate

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

    private val NOVEL_SHOW = Regex("(?i)pixiv\\.net/novel/show\\.php\\?id=(\\d+)")
    private val ILLUST = Regex("(?i)\\b(?:il{1,2}usts?|artworks?)\\s*[/／]\\s*(\\d+)")
    private val NOVEL = Regex("(?i)\\bnovels?\\s*[/／]\\s*(\\d+)")
    private val USER = Regex("(?i)\\busers?\\s*[/／]\\s*(\\d+)")
    private val URL = Regex("https?://[^\\s\\u3000-\\u303F\\uFF01-\\uFF5E<>\"']+")

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
            val deepLink = deepLinkOf(raw) ?: raw
            claim(start, end, deepLink, raw)
        }
        // 2) 纯文本引用（无 scheme），完整 URL 已占用的区间跳过。
        for (match in NOVEL_SHOW.findAll(text)) {
            val id = match.groupValues[1]
            claim(
                match.range.first,
                match.range.last + 1,
                "pixiv://novels/$id",
                "https://www.pixiv.net/novel/show.php?id=$id"
            )
        }
        for (match in ILLUST.findAll(text)) {
            val id = match.groupValues[1]
            claim(
                match.range.first,
                match.range.last + 1,
                "pixiv://illusts/$id",
                "https://www.pixiv.net/artworks/$id"
            )
        }
        for (match in NOVEL.findAll(text)) {
            val id = match.groupValues[1]
            claim(
                match.range.first,
                match.range.last + 1,
                "pixiv://novels/$id",
                "https://www.pixiv.net/novel/show.php?id=$id"
            )
        }
        for (match in USER.findAll(text)) {
            val id = match.groupValues[1]
            claim(
                match.range.first,
                match.range.last + 1,
                "pixiv://users/$id",
                "https://www.pixiv.net/users/$id"
            )
        }
        return result.sortedBy { it.start }
    }

    /** pixiv 页面地址 → 对应深链；非 pixiv 域名返回 null（保持原 URL 行为）。 */
    private fun deepLinkOf(url: String): String? {
        if (!url.contains("pixiv.net", ignoreCase = true)) return null
        NOVEL_SHOW.find(url)?.let { return "pixiv://novels/${it.groupValues[1]}" }
        ILLUST.find(url)?.let { return "pixiv://illusts/${it.groupValues[1]}" }
        NOVEL.find(url)?.let { return "pixiv://novels/${it.groupValues[1]}" }
        USER.find(url)?.let { return "pixiv://users/${it.groupValues[1]}" }
        return null
    }
}
