package com.heihun.pixivplus.ui.panel

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslError
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import com.heihun.pixivplus.core.i18n.WebViewingText as W
import com.heihun.pixivplus.core.net.WebLoginPolicy
import com.heihun.pixivplus.ui.UiKit

internal object WebLoginDialog {
    /** JavaScript 仅用于官方登录页面；不注入脚本，不添加 JS bridge，不读取账号密码。 */
    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: Activity, onClosed: () -> Unit) {
        var web: WebView? = null
        try {
            val dialog = UiKit.dialog(activity, fullScreen = true)
            val root = UiKit.dialogSurface(activity)
            root.addView(UiKit.dialogHeader(activity, W.RELOGIN(), null) { dialog.dismiss() })
            root.addView(UiKit.paragraph(activity, W.LOGIN_HELP()))
            val browser = WebView(activity)
            web = browser
            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    return block(request.url.toString())
                }

                @Deprecated("Compatibility callback")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = block(url)

                private fun block(url: String): Boolean {
                    if (WebLoginPolicy.allows(url)) return false
                    Toast.makeText(activity, W.LOGIN_BLOCKED(), Toast.LENGTH_LONG).show()
                    return true
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    Toast.makeText(activity, W.LOGIN_BLOCKED(), Toast.LENGTH_LONG).show()
                }
            }
            root.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(UiKit.filledButtonRow(activity, W.LOGIN_DONE()) { dialog.dismiss() })
            dialog.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_BACK && browser.canGoBack()) {
                    if (event.action == KeyEvent.ACTION_UP) browser.goBack()
                    true
                } else false
            }
            dialog.setOnDismissListener {
                browser.stopLoading()
                (browser.parent as? ViewGroup)?.removeView(browser)
                browser.destroy()
                web = null
                // 不清理宿主全局 Cookie、缓存或网页存储，返回后由设置页重新读取。
                if (!activity.isFinishing && !activity.isDestroyed) onClosed()
            }
            dialog.setContentView(root)
            dialog.show()
            UiKit.styleFullScreenWindow(dialog)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            browser.loadUrl(WebLoginPolicy.LOGIN_URL)
        } catch (_: Exception) {
            web?.destroy()
            Toast.makeText(activity, W.FAILED(), Toast.LENGTH_LONG).show()
        }
    }
}
