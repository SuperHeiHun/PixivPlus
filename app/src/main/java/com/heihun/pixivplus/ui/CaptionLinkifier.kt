package com.heihun.pixivplus.ui

import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.WorkText as W
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.translate.CaptionLinks
import com.heihun.pixivplus.core.util.DetailPagerResolver
import com.heihun.pixivplus.feature.ConfigStoreRegistry

/** 仅增强指定简介控件，不更改原文本、格式、选择功能或宿主已有可点击 span。 */
internal object CaptionLinkifier {
    fun apply(textView: TextView, sourceHtml: String? = null, enabled: Boolean = true,
              color: Int = textView.linkTextColors.defaultColor, underline: Boolean = true) {
        val original = textView.text ?: return
        if (original.length > 128 * 1024) return
        val text = original.toString()
        val result = SpannableStringBuilder(original)
        val old = result.getSpans(0, result.length, ManagedSpan::class.java)
        for (span in old) result.removeSpan(span)
        val occupied = result.getSpans(0, result.length, ClickableSpan::class.java).map {
            CaptionLinks.Range(result.getSpanStart(it), result.getSpanEnd(it))
        }.toMutableList()
        var added = 0
        fun add(link: CaptionLinks.Link) {
            if (link.start < 0 || link.end > result.length || link.end <= link.start) return
            if (CaptionLinks.excluding(listOf(link), occupied).isEmpty()) return
            result.setSpan(ManagedSpan(link, color, underline), link.start, link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            occupied.add(CaptionLinks.Range(link.start, link.end))
            added++
        }
        if (enabled) {
            // 宿主会删除非 pixiv:// URLSpan。仅在解析文本与实际显示逐字相同的情况下补回 href。
            // 这样 <a href="…">点击这里</a> 不需要在界面里暴露 URL，也不会错位覆盖正文。
            if (!sourceHtml.isNullOrEmpty()) {
                val html = try { Html.fromHtml(sourceHtml, Html.FROM_HTML_MODE_LEGACY) } catch (_: Exception) { null }
                if (html != null && html.toString() == text) {
                    for (span in html.getSpans(0, html.length, URLSpan::class.java)) {
                        CaptionLinks.fromUrl(span.url, html.getSpanStart(span), html.getSpanEnd(span))?.let(::add)
                    }
                }
            }
            for (link in CaptionLinks.find(text)) add(link)
        }
        if (old.isNotEmpty() || added > 0) textView.text = result
        if (result.getSpans(0, result.length, ClickableSpan::class.java).isNotEmpty()) {
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.linksClickable = true
        }
    }

    private class ManagedSpan(val link: CaptionLinks.Link, val color: Int, val underline: Boolean) : ClickableSpan() {
        override fun onClick(widget: View) {
            if (!ConfigStoreRegistry.store.get(ConfigKeys.CAPTION_LINKS_ENABLED)) return
            val owner = DetailPagerResolver.activityOf(widget.context)
            val context = owner ?: widget.context
            for (url in listOf(link.deepLink, link.webUrl).distinct()) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    if (url.startsWith("pixiv://")) intent.setPackage(Host.PACKAGE)
                    if (owner == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                } catch (_: Exception) { }
            }
            ModuleLog.w("CaptionLinks", "no viewer available for caption link")
            Toast.makeText(context, W.CAPTION_LINK_OPEN_FAILED(), Toast.LENGTH_LONG).show()
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = underline
        }
    }
}
