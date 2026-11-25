package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.LinearLayout
import com.heihun.pixivplus.core.i18n.WebViewingText as W
import com.heihun.pixivplus.core.net.WebViewingException
import com.heihun.pixivplus.core.net.WebViewingProblem
import com.heihun.pixivplus.core.net.WebViewingSettings
import com.heihun.pixivplus.core.net.WebViewingState
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.ui.UiKit
import java.util.concurrent.Executors

internal object WebViewingPanel {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "plp-web-viewing").apply { isDaemon = true }
    }

    fun build(activity: Activity, body: LinearLayout) {
        val client = WebViewingSettings(ConfigStoreRegistry.store) {
            CookieManager.getInstance().getCookie(WebViewingSettings.ORIGIN)
        }
        val card = UiKit.card(activity)
        body.addView(UiKit.paragraph(activity, W.HELP()))
        body.addView(card)
        var state: WebViewingState? = null
        var busy = false
        var attached = true
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { attached = true }
            override fun onViewDetachedFromWindow(v: View) { attached = false }
        })
        fun disable(view: View) {
            view.isEnabled = false
            if (view is ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i))
        }
        lateinit var render: (String?) -> Unit
        fun perform(level: Int? = null) {
            if (busy || !attached) return
            busy = true
            val expected = state
            render(if (level == null) W.LOADING() else W.SAVING())
            worker.execute {
                var next: WebViewingState? = null
                var message: String? = null
                try {
                    next = if (level == null) client.load() else client.save(requireNotNull(expected), level)
                    if (level != null) message = W.SAVED()
                } catch (e: WebViewingException) {
                    message = when (e.problem) {
                        WebViewingProblem.LOGIN -> W.LOGIN()
                        WebViewingProblem.AGE -> W.AGE()
                        WebViewingProblem.SESSION -> W.SESSION()
                        WebViewingProblem.VERIFY -> W.VERIFY()
                        else -> W.FAILED()
                    }
                } catch (_: Exception) {
                    message = W.FAILED()
                }
                activity.runOnUiThread {
                    busy = false
                    // 失败后清除旧状态，必须重新读取才能继续修改。
                    state = next
                    if (attached && !activity.isFinishing && !activity.isDestroyed) render(message)
                }
            }
        }
        render = { message ->
            card.removeAllViews()
            if (message != null) card.addView(UiKit.paragraph(activity, message))
            state?.let { current ->
                card.addView(UiKit.infoRow(activity, W.ACCOUNT(), current.userId))
                val r18 = UiKit.switchRow(activity, W.R18(), null, current.r18) { enabled ->
                    perform(current.withR18(enabled))
                }
                val r18g = UiKit.switchRow(activity, W.R18G(), W.DEPENDENCY(), current.r18g) { enabled ->
                    perform(current.withR18g(enabled))
                }
                card.addView(r18)
                card.addView(r18g)
                if (busy || !current.canEdit) {
                    disable(r18)
                    disable(r18g)
                }
                if (!current.canEdit) card.addView(UiKit.paragraph(activity, W.AGE()))
            }
            card.addView(UiKit.filledButtonRow(activity, W.REFRESH()) { perform() }.also {
                if (busy) disable(it)
            })
            card.addView(UiKit.filledButtonRow(activity, W.OPEN_WEB()) {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("${WebViewingSettings.ORIGIN}/settings/viewing")))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(activity, W.FAILED(), android.widget.Toast.LENGTH_LONG).show()
                }
            })
        }
        perform()
    }
}
