package com.heihun.pixivplus.feature.novelsave

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.WorkContext
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.novel.NovelBlock
import com.heihun.pixivplus.core.novel.NovelMarkers
import com.heihun.pixivplus.core.novel.NovelPdfWriter
import com.heihun.pixivplus.core.net.ProxyHttp
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import com.heihun.pixivplus.ui.ActivityResultBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONTokener
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 在小说阅读页的工具栏菜单中提供「保存为 PDF」「保存为 TXT」。
 *
 * 正文直接从阅读页已加载的 WebView DOM 中提取（不依赖额外网络请求，
 * 登录限定 / R-18 作品同样可用）；PDF 兼容带插图的作品，TXT 只保留文本。
 * 首次保存时通过系统文件夹选择器让用户选择保存位置（SAF tree URI，
 * 持久授权），之后沿用该位置。
 */
class NovelSaveFeature : ConfigurableFeature(
    "novel_save",
    "小说保存",
    ConfigKeys.NOVEL_SAVE_ENABLED
) {

    private companion object {
        const val NOVEL_TEXT_ACTIVITY =
            "jp.pxv.android.feature.novelviewer.noveltext.NovelTextActivity"
        const val TOOLBAR_CLASS = "com.google.android.material.appbar.MaterialToolbar"
        const val POLL_INTERVAL_MS = 700L

        const val ID_SAVE_PDF = 0x00f20001
        const val ID_SAVE_TXT = 0x00f20002

        const val WEB_REFERER = "https://www.pixiv.net/"
        const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
        const val MAX_IMAGES = 60

        const val MIME_PDF = "application/pdf"
        const val MIME_TXT = "text/plain"

        /**
         * 从阅读页 DOM 提取有序内容 token：
         * `x` 文本节点、`i` 图片、`n` 块级换行（<br> 与块元素边界）。
         * 遍历整个 body，跳过隐藏元素（display:none）与注音 / 导航节点。
         */
        const val EXTRACT_SCRIPT = """
(function(){
  var out=[];
  function visible(el){
    try{return el.getClientRects().length>0;}catch(e){return true;}
  }
  function walk(node){
    var children=node.childNodes;
    for(var j=0;j<children.length;j++){
      var n=children[j];
      if(n.nodeType===3){
        var t=n.textContent;
        if(t&&t.trim())out.push({t:'x',s:t});
      }else if(n.nodeType===1){
        var tag=n.tagName;
        if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT'||tag==='RT'||tag==='RP'||tag==='NAV'||tag==='HEADER'||tag==='FOOTER')continue;
        if(!visible(n))continue;
        if(tag==='IMG'){
          var s=n.currentSrc||n.getAttribute('src')||n.getAttribute('data-src')||'';
          if(s)out.push({t:'i',s:s});
          continue;
        }
        if(tag==='BR'){out.push({t:'n'});continue;}
        var block=(tag==='P'||tag==='DIV'||tag==='SECTION'||tag==='FIGURE'||tag==='LI'||tag==='BLOCKQUOTE'||(tag.charAt(0)==='H'&&tag.length===2));
        if(block)out.push({t:'n'});
        walk(n);
        if(block)out.push({t:'n'});
      }
    }
  }
  walk(document.body);
  return JSON.stringify(out);
})()
"""

        val NO_ACCESSOR = Any()
    }

    private class SaveException(message: String) : Exception(message)

    @Volatile
    private var currentActivity: Activity? = null

    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val menuAccessorCache = ConcurrentHashMap<Class<*>, Any>()

    override fun hook(host: Host) {
        hookToolbarListener(host)
        hookActivityLifecycle(host)
        ModuleLog.i(displayName, "novel save hooks installed (enabled=${isEnabled(ConfigStoreRegistry.store)})")
    }

    // ------------------------------------------------------------------
    // 菜单注入
    // ------------------------------------------------------------------

    private val pollTask = object : Runnable {
        override fun run() {
            val activity = currentActivity ?: return
            try {
                if (isEnabled(ConfigStoreRegistry.store)) {
                    for (toolbar in findToolbars(activity.window?.decorView ?: return)) {
                        val menu = readMenu(toolbar) ?: continue
                        var changed = false
                        if (menu.findItem(ID_SAVE_PDF) == null) {
                            menu.add(Menu.NONE, ID_SAVE_PDF, Menu.NONE, "保存为 PDF")
                            changed = true
                        }
                        if (menu.findItem(ID_SAVE_TXT) == null) {
                            menu.add(Menu.NONE, ID_SAVE_TXT, Menu.NONE, "保存为 TXT")
                            changed = true
                        }
                        if (changed) {
                            ModuleLog.i(displayName, "novel save menu added (total=${menu.size()})")
                        }
                    }
                }
            } catch (t: Throwable) {
                ModuleLog.w(displayName, "menu injection failed", t)
            }
            activity.window?.decorView?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun hookActivityLifecycle(host: Host) {
        val clazz = ReflectUtil.loadClass(host.classLoader, NOVEL_TEXT_ACTIVITY)
        if (clazz == null) {
            ModuleLog.w(displayName, "NovelTextActivity not found, novel save disabled")
            return
        }
        val onResume = ReflectUtil.findMethod(clazz, "onResume")
        if (onResume != null && hookedMethods.add(onResume)) {
            XposedBridge.hookMethod(onResume, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
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
                    activity.window?.decorView?.removeCallbacks(pollTask)
                    if (currentActivity === activity) currentActivity = null
                }
            })
        }
    }

    private fun hookToolbarListener(host: Host) {
        val toolbarClass = ReflectUtil.loadClass(host.classLoader, TOOLBAR_CLASS) ?: run {
            ModuleLog.w(displayName, "toolbar class not found")
            return
        }
        val setter = toolbarClass.methods.firstOrNull { method ->
            method.name == "setOnMenuItemClickListener" && method.parameterCount == 1
        }
        if (setter == null) {
            ModuleLog.w(displayName, "setOnMenuItemClickListener not found")
            return
        }
        if (!hookedMethods.add(setter)) return
        try {
            XposedBridge.hookMethod(setter, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args?.firstOrNull() ?: return
                    hookListenerClick(listener.javaClass)
                }
            })
            ModuleLog.i(displayName, "novel save toolbar listener hook installed")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "hook toolbar listener failed", t)
        }
    }

    private fun hookListenerClick(listenerClass: Class<*>) {
        val method = listenerClass.methods.firstOrNull { candidate ->
            candidate.parameterCount == 1 && candidate.parameterTypes[0] == MenuItem::class.java &&
                (candidate.returnType == Boolean::class.javaPrimitiveType || candidate.returnType == Void.TYPE)
        } ?: return
        if (!hookedMethods.add(method)) return
        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val item = param.args?.firstOrNull() as? MenuItem ?: return
                    when (item.itemId) {
                        ID_SAVE_PDF -> {
                            onClick(currentActivity, asPdf = true)
                            param.result = true
                        }
                        ID_SAVE_TXT -> {
                            onClick(currentActivity, asPdf = false)
                            param.result = true
                        }
                    }
                }
            })
            ModuleLog.i(displayName, "novel save click hook installed on ${listenerClass.name}.${method.name}")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "hook menu click failed", t)
        }
    }

    private fun findToolbars(root: View): List<View> {
        val result = ArrayList<View>()
        collectToolbars(root, result)
        return result
    }

    private fun collectToolbars(root: View, out: MutableList<View>) {
        if (hasMenuAccessor(root)) {
            out.add(root)
            return
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index) ?: continue
                collectToolbars(child, out)
            }
        }
    }

    private fun hasMenuAccessor(view: View): Boolean {
        return menuAccessor(view.javaClass) != null
    }

    private fun menuAccessor(clazz: Class<*>): Method? {
        return when (val cached = menuAccessorCache.getOrPut(clazz) {
            try {
                clazz.methods.firstOrNull { candidate ->
                    candidate.parameterCount == 0 && candidate.returnType == Menu::class.java
                } ?: NO_ACCESSOR
            } catch (_: Throwable) {
                NO_ACCESSOR
            }
        }) {
            NO_ACCESSOR -> null
            else -> cached as Method
        }
    }

    private fun readMenu(toolbar: View): Menu? {
        val method = menuAccessor(toolbar.javaClass) ?: return null
        return ReflectUtil.invoke(toolbar, method) as? Menu
    }

    // ------------------------------------------------------------------
    // 保存流程
    // ------------------------------------------------------------------

    private fun onClick(activity: Activity?, asPdf: Boolean) {
        if (activity == null) return
        if (!isEnabled(ConfigStoreRegistry.store)) return
        val id = novelId(activity)
        if (id <= 0L) {
            toast(activity, "未获取到当前小说 ID")
            return
        }
        val webView = findWebView(activity.window?.decorView)
        if (webView == null) {
            toast(activity, "未找到小说内容，请返回后重新进入阅读页")
            return
        }
        val folder = try {
            ConfigStoreRegistry.store.get(ConfigKeys.NOVEL_SAVE_FOLDER_URI)
        } catch (_: Throwable) {
            ""
        }
        if (folder.isBlank()) {
            toast(activity, "首次保存请选择保存位置")
            ActivityResultBridge.pickFolder(activity) { resultActivity, uri ->
                if (uri == null) {
                    toast(resultActivity, "未选择保存位置，已取消")
                    return@pickFolder
                }
                try {
                    ConfigStoreRegistry.store.put(ConfigKeys.NOVEL_SAVE_FOLDER_URI, uri.toString())
                } catch (t: Throwable) {
                    ModuleLog.w(displayName, "store save folder failed", t)
                }
                toast(resultActivity, "已选择保存位置")
                extractAndSave(resultActivity, id, asPdf)
            }
            return
        }
        extractAndSave(activity, id, asPdf)
    }

    private fun novelId(activity: Activity): Long {
        return try {
            activity.intent?.getLongExtra("NOVEL_ID", 0L) ?: 0L
        } catch (_: Throwable) {
            0L
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

    private fun extractAndSave(activity: Activity, id: Long, asPdf: Boolean) {
        val webView = findWebView(activity.window?.decorView)
        if (webView == null) {
            toast(activity, "未找到小说内容，请返回后重新进入阅读页")
            return
        }
        toast(activity, "正在读取小说内容…")
        try {
            webView.evaluateJavascript(EXTRACT_SCRIPT) { raw ->
                try {
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
                    val blocks = if (tokens != null) blocksFromTokens(tokens) else emptyList()
                    if (blocks.isEmpty()) {
                        toast(activity, "未读取到小说内容，请等页面加载完成后再试")
                        return@evaluateJavascript
                    }
                    startSave(activity, id, blocks, asPdf)
                } catch (t: Throwable) {
                    ModuleLog.w(displayName, "parse extracted content failed", t)
                    toast(activity, "解析小说内容失败：${t.message ?: t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "extract content failed", t)
            toast(activity, "读取小说内容失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** DOM token → 排版块：文本合并为行，图片保持原位置顺序。 */
    private fun blocksFromTokens(tokens: JSONArray): List<NovelBlock> {
        val blocks = ArrayList<NovelBlock>()
        val line = StringBuilder()
        fun flush() {
            val text = line.toString().trim()
            line.setLength(0)
            if (text.isNotEmpty()) blocks.add(NovelBlock.Text(text))
        }
        for (index in 0 until tokens.length()) {
            val token = tokens.optJSONObject(index) ?: continue
            when (token.optString("t")) {
                "x" -> {
                    val parts = token.optString("s").split('\n')
                    for ((partIndex, part) in parts.withIndex()) {
                        if (partIndex > 0) flush()
                        line.append(part)
                    }
                }
                "n" -> flush()
                "i" -> {
                    flush()
                    val url = token.optString("s")
                    if (url.startsWith("http")) blocks.add(NovelBlock.Picture(url))
                }
            }
        }
        flush()
        return blocks
    }

    private fun startSave(activity: Activity, id: Long, blocks: List<NovelBlock>, asPdf: Boolean) {
        toast(activity, "正在生成${if (asPdf) " PDF" else " TXT"}…")
        val appContext = activity.applicationContext
        val snapshot = WorkContext.current?.takeIf { it.id == id }
        val title = snapshot?.title?.takeIf { it.isNotBlank() } ?: "novel_$id"
        val author = snapshot?.authorName.orEmpty()
        Thread({
            var result: String? = null
            var error: String? = null
            try {
                result = save(appContext, id, title, author, blocks, asPdf)
                ModuleLog.i(displayName, "novel saved id=$id pdf=$asPdf file=$result")
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                ModuleLog.w(displayName, "novel save failed id=$id", t)
            }
            runOnUi(activity) {
                if (result != null) {
                    toast(activity, "已保存：$result")
                } else {
                    toast(activity, "保存失败：${error ?: "未知错误"}")
                }
            }
        }, "plp-novel-save").apply { isDaemon = true }.start()
    }

    private fun runOnUi(activity: Activity, block: () -> Unit) {
        try {
            activity.runOnUiThread(block)
        } catch (_: Throwable) {
        }
    }

    private fun save(
        context: Context,
        id: Long,
        title: String,
        author: String,
        blocks: List<NovelBlock>,
        asPdf: Boolean
    ): String {
        val store = ConfigStoreRegistry.store
        val treeUriText = store.get(ConfigKeys.NOVEL_SAVE_FOLDER_URI)
        if (treeUriText.isBlank()) throw SaveException("尚未选择保存位置")
        val treeUri = Uri.parse(treeUriText)

        val sourceUrl = "https://www.pixiv.net/novel/show.php?id=$id"
        val fileName = "小说_${NovelMarkers.safeFileName(title, "novel_$id")}_$id"

        if (asPdf) {
            val labels = NovelMarkers.imageLabels(blocks)
            val images = downloadImages(store, blocks)
            val bytes = NovelPdfWriter.write(
                title = title,
                author = author,
                sourceUrl = sourceUrl,
                blocks = blocks,
                images = images,
                labels = labels
            )
            writeDocument(context, treeUri, "$fileName.pdf", MIME_PDF, bytes)
            return "$fileName.pdf"
        }
        val text = NovelMarkers.toPlainText(title, author, sourceUrl, blocks)
        writeDocument(context, treeUri, "$fileName.txt", MIME_TXT, text.toByteArray(Charsets.UTF_8))
        return "$fileName.txt"
    }

    // ------------------------------------------------------------------
    // 图片下载（PDF 用）
    // ------------------------------------------------------------------

    private fun downloadImages(store: ConfigStore, blocks: List<NovelBlock>): Map<String, Bitmap?> {
        val result = HashMap<String, Bitmap?>()
        val seen = HashSet<String>()
        var count = 0
        for (block in blocks) {
            if (block !is NovelBlock.Picture) continue
            if (count >= MAX_IMAGES) break
            count++
            val key = NovelMarkers.imageKey(block)
            if (!seen.add(key)) continue
            result[key] = downloadBitmapAny(store, block.url)
        }
        return result
    }

    /** 优先走模块网络栈（含代理设置），失败时退回宿主网络栈（系统 / App 代理）。 */
    private fun downloadBitmapAny(store: ConfigStore, url: String): Bitmap? {
        downloadBitmap(store, url)?.let { return it }
        return downloadBitmapHost(url)
    }

    private fun downloadBitmap(store: ConfigStore, url: String): Bitmap? {
        val response = try {
            ProxyHttp.getBytes(
                store = store,
                url = URL(url),
                headers = imageHeaders(),
                maxBytes = MAX_IMAGE_BYTES
            )
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "module image request failed: ${t.javaClass.simpleName}")
            null
        } ?: return null
        if (response.code != 200) {
            ModuleLog.w(displayName, "module image http ${response.code} $url")
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(response.bytes, 0, response.bytes.size)
        if (bitmap == null) {
            ModuleLog.w(displayName, "module image decode failed $url")
        }
        return bitmap
    }

    private fun downloadBitmapHost(url: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 20000
            connection.setRequestProperty("Referer", WEB_REFERER)
            connection.setRequestProperty("User-Agent", WEB_UA)
            webCookie()?.let { connection.setRequestProperty("Cookie", it) }
            if (connection.responseCode !in 200..299) {
                ModuleLog.w(displayName, "host image http ${connection.responseCode} $url")
                null
            } else {
                val bytes = connection.inputStream.use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "host image download failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun imageHeaders(): Map<String, String> {
        val headers = linkedMapOf(
            "Referer" to WEB_REFERER,
            "User-Agent" to WEB_UA,
            "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        )
        webCookie()?.let { headers["Cookie"] = it }
        return headers
    }

    private fun webCookie(): String? {
        return try {
            CookieManager.getInstance().getCookie("https://www.pixiv.net")
        } catch (t: Throwable) {
            ModuleLog.d(displayName, "read web cookie failed: ${t.javaClass.simpleName}")
            null
        }
    }

    // ------------------------------------------------------------------
    // 文件写入（SAF）
    // ------------------------------------------------------------------

    private fun writeDocument(
        context: Context,
        treeUri: Uri,
        name: String,
        mime: String,
        bytes: ByteArray
    ) {
        val resolver = context.contentResolver
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (t: Throwable) {
            throw SaveException("保存位置授权已失效，请重新选择")
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)

        // 同名文件先删除再创建，避免部分 DocumentsProvider 忽略截断标记。
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        var existing: Uri? = null
        try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == name) {
                        existing = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                        break
                    }
                }
            }
            existing?.let { uri ->
                DocumentsContract.deleteDocument(resolver, uri)
            }
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "query / delete existing file failed: ${t.message}")
        }

        val documentUri = try {
            DocumentsContract.createDocument(resolver, parentUri, mime, name)
        } catch (t: Throwable) {
            throw SaveException("无法在所选文件夹创建文件：${t.message ?: t.javaClass.simpleName}")
        } ?: throw SaveException("无法在所选文件夹创建文件")

        try {
            resolver.openOutputStream(documentUri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw SaveException("无法写入文件")
        } catch (t: SaveException) {
            throw t
        } catch (t: Throwable) {
            throw SaveException("写入文件失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun toast(activity: Activity, message: String) {
        try {
            activity.runOnUiThread {
                try {
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }
}
