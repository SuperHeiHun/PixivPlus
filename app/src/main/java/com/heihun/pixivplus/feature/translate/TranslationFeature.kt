package com.heihun.pixivplus.feature.translate

import android.app.Activity
import android.app.Dialog
import android.text.Html
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.novel.NovelExtractor
import com.heihun.pixivplus.core.translate.Translator
import com.heihun.pixivplus.core.util.EntityReader
import com.heihun.pixivplus.core.util.MenuHooks
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import com.heihun.pixivplus.ui.UiKit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONTokener
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 简介与小说翻译。
 *
 * - 详情页（插画 / 小说）菜单「翻译简介」：读取当前作品简介，弹窗对照原文与译文；
 * - 小说阅读页菜单「翻译小说 / 显示原文」：整篇翻译后替换阅读页内容，可随时还原。
 *
 * 翻译使用 Google 翻译免费端点（沿用模块代理设置），功能默认关闭。
 */
class TranslationFeature : ConfigurableFeature(
    "translate",
    "简介与小说翻译",
    ConfigKeys.TRANSLATE_ENABLED
) {

    private companion object {
        const val NOVEL_TEXT_ACTIVITY =
            "jp.pxv.android.feature.novelviewer.noveltext.NovelTextActivity"
        const val CAPTION_VIEW_CLASS =
            "jp.pxv.android.feature.component.androidview.DetailCaptionAndTagsView"
        const val POLL_INTERVAL_MS = 700L

        const val ID_TRANSLATE_CAPTION = 0x00f30001
        const val ID_TRANSLATE_NOVEL = 0x00f30002
        const val ID_RESTORE_NOVEL = 0x00f30003

        val DETAIL_ACTIVITIES = listOf(
            "jp.pxv.android.feature.illustviewer.detail.IllustDetailSingleActivity",
            "jp.pxv.android.feature.illustviewer.detail.IllustDetailPagerActivity",
            NOVEL_TEXT_ACTIVITY
        )
    }

    @Volatile
    private var currentActivity: Activity? = null

    @Volatile
    private var progressToast: Toast? = null

    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())

    private val translating = AtomicBoolean(false)

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "plp-translate").apply { isDaemon = true }
    }

    override fun hook(host: Host) {
        MenuHooks.installClickHook(host, hookedMethods, displayName) { item ->
            when (item.itemId) {
                ID_TRANSLATE_CAPTION -> {
                    translateCaption(currentActivity)
                    true
                }
                ID_TRANSLATE_NOVEL -> {
                    translateNovel(currentActivity)
                    true
                }
                ID_RESTORE_NOVEL -> {
                    restoreNovel(currentActivity)
                    true
                }
                else -> false
            }
        }
        for (className in DETAIL_ACTIVITIES) {
            hookActivityLifecycle(host, className)
        }
        ModuleLog.i(displayName, "translation hooks installed (enabled=${isEnabled(ConfigStoreRegistry.store)})")
    }

    // ------------------------------------------------------------------
    // 菜单注入
    // ------------------------------------------------------------------

    private val pollTask = object : Runnable {
        override fun run() {
            val activity = currentActivity ?: return
            try {
                if (isEnabled(ConfigStoreRegistry.store)) {
                    val novel = activity.javaClass.name == NOVEL_TEXT_ACTIVITY
                    for (toolbar in MenuHooks.findToolbars(activity.window?.decorView ?: return)) {
                        val menu = MenuHooks.readMenu(toolbar) ?: continue
                        var changed = false
                        if (menu.findItem(ID_TRANSLATE_CAPTION) == null) {
                            menu.add(Menu.NONE, ID_TRANSLATE_CAPTION, Menu.NONE, "翻译简介")
                            changed = true
                        }
                        if (novel) {
                            if (menu.findItem(ID_TRANSLATE_NOVEL) == null) {
                                menu.add(Menu.NONE, ID_TRANSLATE_NOVEL, Menu.NONE, "翻译小说")
                                changed = true
                            }
                            if (menu.findItem(ID_RESTORE_NOVEL) == null) {
                                menu.add(Menu.NONE, ID_RESTORE_NOVEL, Menu.NONE, "显示原文")
                                changed = true
                            }
                        }
                        if (changed) {
                            ModuleLog.i(displayName, "translate menu added (total=${menu.size()})")
                        }
                    }
                }
            } catch (t: Throwable) {
                ModuleLog.w(displayName, "menu injection failed", t)
            }
            activity.window?.decorView?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun hookActivityLifecycle(host: Host, className: String) {
        val clazz = ReflectUtil.loadClass(host.classLoader, className) ?: return
        val onResume = ReflectUtil.findMethod(clazz, "onResume")
        if (onResume != null && hookedMethods.add(onResume)) {
            XposedBridge.hookMethod(onResume, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name !in DETAIL_ACTIVITIES) return
                    currentActivity = activity
                    activity.window?.decorView?.removeCallbacks(pollTask)
                    activity.window?.decorView?.postDelayed(pollTask, 400L)
                }
            })
        }
        val onPause = ReflectUtil.findMethod(clazz, "onPause")
        if (onPause != null && hookedMethods.add(onPause)) {
            XposedBridge.hookMethod(onPause, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name !in DETAIL_ACTIVITIES) return
                    activity.window?.decorView?.removeCallbacks(pollTask)
                    if (currentActivity === activity) currentActivity = null
                }
            })
        }
    }

    // ------------------------------------------------------------------
    // 简介翻译
    // ------------------------------------------------------------------

    private fun translateCaption(activity: Activity?) {
        if (activity == null) return
        if (!isEnabled(ConfigStoreRegistry.store)) return
        val caption = currentCaption(activity)
        if (caption.isNullOrBlank()) {
            toast(activity, "未获取到当前简介")
            return
        }
        val dialog = captionDialog(activity, "正在翻译…")
        executor.execute {
            val store = ConfigStoreRegistry.store
            val result = Translator.translate(store, caption)
            runOnUi(activity) {
                try {
                    val body = dialog.findViewById<TextView>(android.R.id.text1)
                    body?.text = if (result.text != null) {
                        "【原文】\n$caption\n\n【译文】\n${result.text}"
                    } else {
                        "翻译失败：${result.error ?: "未知错误"}"
                    }
                    body?.setTextIsSelectable(true)
                } catch (t: Throwable) {
                    ModuleLog.w(displayName, "update caption dialog failed", t)
                }
            }
        }
    }

    /** 读取当前详情页简介（去除 HTML 标记），失败返回 null。 */
    private fun currentCaption(activity: Activity): String? {
        return try {
            val view = findCaptionView(activity.window?.decorView) ?: return null
            val entity = ReflectUtil.readField(view, "f") ?: return null
            val raw = EntityReader.caption(entity) ?: return null
            stripHtml(raw).trim().ifEmpty { null }
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "read caption failed", t)
            null
        }
    }

    private fun stripHtml(raw: String): String {
        return try {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        } catch (_: Throwable) {
            raw
        }
    }

    private fun findCaptionView(root: View?): View? {
        if (root == null) return null
        if (root.javaClass.name == CAPTION_VIEW_CLASS) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findCaptionView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /** 简介翻译弹窗：占位 TextView 的 id 固定为 android.R.id.text1，便于后续更新。 */
    private fun captionDialog(activity: Activity, initial: String): Dialog {
        val dialog = UiKit.dialog(activity)
        val surface = UiKit.dialogSurface(activity)
        surface.addView(UiKit.dialogHeader(activity, "简介翻译", null) { dialog.dismiss() })
        surface.addView(UiKit.divider(activity))
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
        }
        val body = TextView(activity).apply {
            id = android.R.id.text1
            text = initial
            textSize = 14f
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
            setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 16), UiKit.dp(activity, 20), UiKit.dp(activity, 20))
        }
        scroll.addView(body)
        surface.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(activity, 380)
            )
        )
        dialog.setContentView(surface)
        dialog.show()
        return dialog
    }

    // ------------------------------------------------------------------
    // 小说翻译
    // ------------------------------------------------------------------

    private fun translateNovel(activity: Activity?) {
        if (activity == null) return
        if (!isEnabled(ConfigStoreRegistry.store)) return
        if (!translating.compareAndSet(false, true)) {
            toast(activity, "正在翻译中，请稍候…")
            return
        }
        val webView = findWebView(activity.window?.decorView)
        if (webView == null) {
            translating.set(false)
            toast(activity, "未找到小说内容，请返回后重新进入阅读页")
            return
        }
        toast(activity, "正在读取小说内容…")
        try {
            webView.evaluateJavascript(NovelExtractor.EXTRACT_SCRIPT) { raw ->
                val text = try {
                    val decoded = JSONTokener(raw ?: "null").nextValue()
                    val tokens = when (decoded) {
                        is JSONArray -> decoded
                        is String -> try {
                            JSONArray(decoded)
                        } catch (_: Throwable) {
                            null
                        }
                        else -> null
                    }
                    if (tokens == null) "" else NovelExtractor.toPlainText(NovelExtractor.blocksFromTokens(tokens))
                } catch (t: Throwable) {
                    ModuleLog.w(displayName, "parse novel content failed", t)
                    ""
                }
                if (text.isBlank()) {
                    translating.set(false)
                    toast(activity, "未读取到小说内容，请等页面加载完成后再试")
                    return@evaluateJavascript
                }
                startNovelTranslation(activity, webView, text)
            }
        } catch (t: Throwable) {
            translating.set(false)
            ModuleLog.w(displayName, "extract novel failed", t)
            toast(activity, "读取小说内容失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun startNovelTranslation(activity: Activity, webView: WebView, text: String) {
        showProgress(activity, "正在翻译小说…")
        executor.execute {
            val store = ConfigStoreRegistry.store
            val result = Translator.translate(store, text) { done, total ->
                showProgress(activity, "正在翻译小说… (${done + 1}/$total)")
            }
            hideProgress()
            runOnUi(activity) {
                translating.set(false)
                if (result.text == null) {
                    toast(activity, "翻译失败：${result.error ?: "未知错误"}")
                    return@runOnUi
                }
                try {
                    webView.evaluateJavascript(NovelExtractor.applyTranslationScript(result.text)) { value ->
                        if (value?.trim('"') == "true") {
                            toast(activity, "已显示译文，可在菜单选择「显示原文」还原")
                        } else {
                            toast(activity, "译文写入失败")
                        }
                    }
                } catch (t: Throwable) {
                    ModuleLog.w(displayName, "apply translation failed", t)
                    toast(activity, "译文写入失败：${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun restoreNovel(activity: Activity?) {
        if (activity == null) return
        val webView = findWebView(activity.window?.decorView)
        if (webView == null) {
            toast(activity, "未找到小说内容")
            return
        }
        try {
            webView.evaluateJavascript(NovelExtractor.RESTORE_SCRIPT) { value ->
                if (value?.trim('"') == "true") {
                    toast(activity, "已显示原文")
                } else {
                    toast(activity, "当前没有译文")
                }
            }
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "restore novel failed", t)
        }
    }

    private fun findWebView(root: View?): WebView? {
        if (root == null) return null
        if (root is WebView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findWebView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun showProgress(activity: Activity, message: String) {
        runOnUi(activity) {
            try {
                progressToast?.cancel()
                progressToast = Toast.makeText(activity, message, Toast.LENGTH_LONG).also { it.show() }
            } catch (_: Throwable) {
            }
        }
    }

    private fun hideProgress() {
        try {
            progressToast?.cancel()
        } catch (_: Throwable) {
        }
        progressToast = null
    }

    private fun runOnUi(activity: Activity, block: () -> Unit) {
        try {
            activity.runOnUiThread(block)
        } catch (_: Throwable) {
        }
    }

    private fun toast(activity: Activity, message: String) {
        runOnUi(activity) {
            try {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }
}
