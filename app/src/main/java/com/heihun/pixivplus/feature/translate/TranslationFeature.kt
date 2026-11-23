package com.heihun.pixivplus.feature.translate

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.WorkContext
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.CommonText as C
import com.heihun.pixivplus.core.i18n.WorkText as W
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.PayloadProcessor
import com.heihun.pixivplus.core.novel.NovelBlock
import com.heihun.pixivplus.core.novel.NovelExtractor
import com.heihun.pixivplus.core.translate.CaptionLinks
import com.heihun.pixivplus.core.translate.NovelText
import com.heihun.pixivplus.core.translate.NovelTranslation
import com.heihun.pixivplus.core.translate.Translator
import com.heihun.pixivplus.core.task.TaskManager
import com.heihun.pixivplus.core.task.TaskKind
import com.heihun.pixivplus.core.task.TaskExecution
import com.heihun.pixivplus.core.task.TaskCancelledException
import com.heihun.pixivplus.core.util.EntityReader
import com.heihun.pixivplus.core.util.FeatureMenuOrder
import com.heihun.pixivplus.core.util.DetailPagerResolver
import com.heihun.pixivplus.core.util.MenuHooks
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import com.heihun.pixivplus.ui.UiKit
import com.heihun.pixivplus.ui.theme.Md3
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
 *   简介中的 `illust/123`、`user/456` 等引用与 URL 自动转为可点击链接（优先 pixiv 深链）。
 * - 小说阅读页菜单「翻译小说 / 显示原文」：按结构行翻译后以覆盖层显示，可随时还原；
 *   空行 / 插图 / 分页 / 章节标题由本地保留，原文 DOM 移入隐藏包装层（不销毁），
 *   配图与宿主交互不受影响，还原后原样恢复。
 *
 * 翻译使用 Google 翻译免费端点（沿用模块代理设置），功能默认关闭。
 */
class TranslationFeature : ConfigurableFeature(
    "translate",
    "简介与小说翻译",
    ConfigKeys.TRANSLATE_ENABLED
) {

    override fun onLanguageChanged(host: Host) {
        currentActivity?.window?.decorView?.let { view ->
            view.removeCallbacks(pollTask)
            view.post(pollTask)
        }
    }

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
                        var changed = MenuHooks.ensureItem(menu, ID_TRANSLATE_CAPTION, FeatureMenuOrder.TRANSLATE_CAPTION, W.TRANSLATE_CAPTION())
                        if (novel) {
                            changed = MenuHooks.ensureItem(menu, ID_TRANSLATE_NOVEL, FeatureMenuOrder.TRANSLATE_NOVEL, W.TRANSLATE_NOVEL()) || changed
                            changed = MenuHooks.ensureItem(menu, ID_RESTORE_NOVEL, FeatureMenuOrder.RESTORE_NOVEL, W.SHOW_ORIGINAL()) || changed
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
            toast(activity, W.CAPTION_NOT_READY())
            return
        }
        val holder = captionDialog(activity, W.TRANSLATING())
        val task = TaskManager.begin(TaskKind.TRANSLATE, W.CAPTION_TRANSLATION())
        holder.dialog.setOnDismissListener { TaskManager.cancel(task.record.id) }
        executor.execute {
            val store = ConfigStoreRegistry.store
            val result = try {
                task.execute {
                    val translated = Translator.translate(store, caption) { done, total ->
                        TaskExecution.progress(W.TRANSLATING_CAPTION_PROGRESS(done + 1, total), done + 1, total)
                    }
                    check(translated.text != null) { translated.error ?: W.TRANSLATION_REQUEST_FAILED() }
                    translated
                }
            } catch (t: Exception) {
                Translator.Result(null, if (t is TaskCancelledException) C.CANCELLED() else t.message ?: C.UNKNOWN_ERROR())
            }
            runOnUi(activity) {
                try {
                    holder.body.text = if (result.text != null) {
                        W.CAPTION_RESULT(caption, result.text)
                    } else {
                        W.TRANSLATION_FAILED(result.error ?: C.UNKNOWN_ERROR())
                    }
                    holder.body.setTextIsSelectable(true)
                    applyCaptionLinks(holder.body)
                    fitCaptionDialog(activity, holder)
                } catch (t: Throwable) {
                    ModuleLog.w(displayName, "update caption dialog failed", t)
                }
            }
        }
    }

    /**
     * 读取当前详情页简介（去除 HTML 标记），失败返回 null。
     *
     * 三段回退：
     * 1. 翻页详情优先取 ViewPager 当前页的实体简介；
     * 2. 被屏蔽简介的作品实体里 caption 为空（简介修复只把恢复文本渲染到
     *    TextView，不回写实体），因此再读视图上已渲染的文本；
     * 3. 仍为空时查本地简介索引（简介修复的缓存）。
     */
    private fun currentCaption(activity: Activity): String? {
        return try {
            val pagerEntity = DetailPagerResolver.currentItem(activity)
            if (pagerEntity != null) {
                EntityReader.caption(pagerEntity)?.let { raw ->
                    stripHtml(raw).trim().ifEmpty { null }?.let { return it }
                }
            }
            val currentId = WorkContext.current?.id ?: DetailPagerResolver.currentIllustId(activity)
            for (view in findCaptionViews(activity.window?.decorView)) {
                val entityId = ReflectUtil.readField(view, "f")?.let { entity -> idOf(entity) }
                if (currentId != null && entityId != null && entityId != currentId) continue
                val rendered = captionTextFromView(view) ?: continue
                stripHtml(rendered).trim().ifEmpty { null }?.let { return it }
            }
            if (currentId != null && currentId > 0L) {
                PayloadProcessor.lookupCaption(currentId)?.let { raw ->
                    stripHtml(raw).trim().ifEmpty { null }?.let { return it }
                }
            }
            null
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "read caption failed", t)
            null
        }
    }

    private fun idOf(entity: Any): Long? {
        val method = ReflectUtil.findMethod(entity.javaClass, "getId") ?: return null
        return ReflectUtil.invoke(entity, method) as? Long
    }

    /** 读取简介控件上已渲染的文本（含简介修复恢复后的内容）。 */
    private fun captionTextFromView(view: View): String? {
        return try {
            val textId = view.resources.getIdentifier(
                "caption_text_view",
                "id",
                view.context.packageName
            )
            if (textId != 0) {
                view.findViewById<TextView>(textId)?.text?.toString()?.let { text ->
                    if (text.isNotBlank()) return text
                }
            }
            val holder = ReflectUtil.readField(view, "e") ?: return null
            val textView = ReflectUtil.readField(holder, "e") as? TextView ?: return null
            textView.text?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findCaptionViews(root: View?): List<View> {
        val result = ArrayList<View>()
        collectCaptionViews(root, result)
        return result
    }

    private fun collectCaptionViews(root: View?, out: MutableList<View>) {
        if (root == null) return
        if (root.javaClass.name == CAPTION_VIEW_CLASS) {
            out.add(root)
            return
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                collectCaptionViews(root.getChildAt(index), out)
            }
        }
    }

    private fun stripHtml(raw: String): String {
        return try {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        } catch (_: Throwable) {
            raw
        }
    }

    /**
     * 把简介弹窗文本中的 `illust/123`、`user/456`、URL 等引用转为可点击链接
     * （点击优先 pixiv 深链在宿主内打开，失败退回浏览器打开网页地址）。
     */
    private fun applyCaptionLinks(textView: TextView) {
        val text = textView.text?.toString() ?: return
        val links = CaptionLinks.find(text)
        if (links.isEmpty()) return
        val spannable = SpannableString(text)
        val context = textView.context
        val color = Md3.colors(context).primary
        for (link in links) {
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        openCaptionLink(context, link)
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = color
                        ds.isUnderlineText = false
                    }
                },
                link.start,
                link.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        textView.text = spannable
        textView.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun openCaptionLink(context: Context, link: CaptionLinks.Link) {
        for (url in listOf(link.deepLink, link.webUrl)) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return
            } catch (_: Throwable) {
            }
        }
        ModuleLog.w(displayName, "open caption link failed: ${link.deepLink}")
    }

    private class CaptionDialogHolder(
        val dialog: Dialog,
        val scroll: ScrollView,
        val body: TextView
    )

    /** 简介翻译弹窗：占位 TextView 持有在 holder 中；内容更新后调用 [fitCaptionDialog] 收纳高度。 */
    private fun captionDialog(activity: Activity, initial: String): CaptionDialogHolder {
        val dialog = UiKit.dialog(activity)
        val surface = UiKit.dialogSurface(activity)
        surface.addView(UiKit.dialogHeader(activity, W.CAPTION_TRANSLATION(), null) { UiKit.dismissAnimated(dialog, surface) })
        surface.addView(UiKit.divider(activity))
        val scroll = ScrollView(activity)
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
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        dialog.setContentView(surface)
        dialog.show()
        // 宽度与其他弹窗一致（不再是全屏宽），高度由 fitCaptionDialog 按内容收敛。
        UiKit.applyDialogWidth(dialog, 0.92f, 520)
        UiKit.animateDialogIn(surface)
        return CaptionDialogHolder(dialog, scroll, body)
    }

    /** 弹窗高度跟随内容（含译文），超过 420dp 后在弹窗内滚动，避免大片空白。 */
    private fun fitCaptionDialog(activity: Activity, holder: CaptionDialogHolder) {
        holder.body.post {
            try {
                // 必须按最终布局宽度测量，否则长译文不换行导致高度被低估；
                // 弹窗宽度收敛后以 ScrollView 实际宽度为准，未布局完成时退回屏幕宽度。
                val laidOut = holder.scroll.width.takeIf { it > 0 }
                val contentWidth = (laidOut ?: activity.resources.displayMetrics.widthPixels) -
                    holder.body.paddingLeft - holder.body.paddingRight
                val widthSpec = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.AT_MOST)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                holder.body.measure(widthSpec, heightSpec)
                val desired = holder.body.measuredHeight
                val maxHeight = UiKit.dp(activity, 420)
                val params = holder.scroll.layoutParams as? LinearLayout.LayoutParams ?: return@post
                params.height = minOf(desired, maxHeight)
                holder.scroll.requestLayout()
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 小说翻译
    // ------------------------------------------------------------------

    private fun translateNovel(activity: Activity?) {
        if (activity == null) return
        if (!isEnabled(ConfigStoreRegistry.store)) return
        if (!translating.compareAndSet(false, true)) {
            toast(activity, W.TRANSLATION_BUSY())
            return
        }
        val webView = findWebView(activity.window?.decorView)
        if (webView == null) {
            translating.set(false)
            toast(activity, W.NOVEL_CONTENT_MISSING())
            return
        }
        toast(activity, W.READ_NOVEL())
        try {
            webView.evaluateJavascript(NovelExtractor.EXTRACT_SCRIPT) { raw ->
                val blocks = try {
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
                    if (tokens == null) emptyList() else NovelExtractor.blocksFromTokens(tokens)
                } catch (t: Throwable) {
                    ModuleLog.w(displayName, "parse novel content failed", t)
                    emptyList()
                }
                if (blocks.isEmpty()) {
                    translating.set(false)
                    toast(activity, W.NOVEL_WAIT_FOR_LOAD())
                    return@evaluateJavascript
                }
                startNovelTranslation(activity, webView, blocks)
            }
        } catch (t: Throwable) {
            translating.set(false)
            ModuleLog.w(displayName, "extract novel failed", t)
            toast(activity, W.NOVEL_READ_FAILED(t.message ?: t.javaClass.simpleName))
        }
    }

    /**
     * 结构保留的小说翻译：
     * 空行 / 插图 / 分页 / 章节标题不参与翻译、位置原样保留，
     * 连续文本行合并为组翻译后逐行回填；渲染为 HTML 走覆盖层显示。
     */
    private fun startNovelTranslation(activity: Activity, webView: WebView, blocks: List<NovelBlock>) {
        val task = TaskManager.begin(TaskKind.TRANSLATE, W.NOVEL_TRANSLATION(WorkContext.current?.title.orEmpty()))
        showProgress(activity, W.TRANSLATING_NOVEL())
        executor.execute {
            val store = ConfigStoreRegistry.store
            val html = try {
                task.execute {
                    val translated = NovelTranslation.translate(store, blocks) { done, total ->
                        showProgress(activity, W.TRANSLATING_NOVEL_PROGRESS(done, total), done, total)
                    }
                    NovelText.toHtml(translated)
                }
            } catch (t: Exception) {
                ModuleLog.w(displayName, "novel translation failed", t)
                runOnUi(activity) {
                    translating.set(false)
                    toast(activity, if (t is TaskCancelledException) W.NOVEL_TRANSLATION_CANCELLED() else W.TRANSLATION_FAILED(t.message ?: t.javaClass.simpleName))
                }
                return@execute
            } finally {
                hideProgress()
            }
            runOnUi(activity) {
                translating.set(false)
                try {
                    webView.evaluateJavascript(NovelExtractor.applyTranslationScript(html)) { value ->
                        if (value?.trim('"') == "true") {
                            toast(activity, W.TRANSLATION_SHOWN())
                        } else {
                            toast(activity, W.APPLY_TRANSLATION_FAILED_SHORT())
                        }
                    }
                } catch (t: Throwable) {
                    ModuleLog.w(displayName, "apply translation failed", t)
                    toast(activity, W.APPLY_TRANSLATION_FAILED(t.message ?: t.javaClass.simpleName))
                }
            }
        }
    }

    private fun restoreNovel(activity: Activity?) {
        if (activity == null) return
        val webView = findWebView(activity.window?.decorView)
        if (webView == null) {
            toast(activity, W.NOVEL_CONTENT_MISSING())
            return
        }
        try {
            webView.evaluateJavascript(NovelExtractor.RESTORE_SCRIPT) { value ->
                if (value?.trim('"') == "true") {
                    toast(activity, W.ORIGINAL_SHOWN())
                } else {
                    toast(activity, W.NO_TRANSLATION())
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

    private fun showProgress(activity: Activity, message: String, done: Int = 0, total: Int = 0) {
        TaskExecution.progress(message, done, total)
        if (ConfigStoreRegistry.store.get(ConfigKeys.TASK_NOTIFICATIONS_ENABLED)) return
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
