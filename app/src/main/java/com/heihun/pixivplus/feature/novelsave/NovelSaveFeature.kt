package com.heihun.pixivplus.feature.novelsave

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.CookieManager
import android.widget.Toast
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
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
import org.json.JSONObject
import java.lang.reflect.Method
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 在小说阅读页的工具栏菜单中提供「保存为 PDF」「保存为 TXT」。
 *
 * 正文来自网页版 `ajax/novel/{id}`（含 [newpage] / [chapter] / 插图标记）；
 * PDF 兼容带插图的作品，TXT 只保留文本。首次保存时通过系统文件夹选择器
 * 让用户选择保存位置（SAF tree URI，持久授权），之后沿用该位置。
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

        const val WEB_API_NOVEL = "https://www.pixiv.net/ajax/novel/%d?lang=zh"
        const val WEB_API_ILLUST = "https://www.pixiv.net/ajax/illust/%d?lang=zh"
        const val WEB_REFERER = "https://www.pixiv.net/"
        const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val MAX_JSON_CHARS = 4 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
        const val MAX_IMAGES = 60

        const val MIME_PDF = "application/pdf"
        const val MIME_TXT = "text/plain"

        val NO_ACCESSOR = Any()
    }

    private class SaveException(message: String) : Exception(message)

    private data class NovelData(
        val id: Long,
        val title: String,
        val author: String,
        val createDate: String,
        val content: String,
        val embedded: JSONObject?
    )

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
                if (!isEnabled(ConfigStoreRegistry.store)) return
                for (toolbar in findToolbars(activity.window?.decorView ?: return)) {
                    val menu = readMenu(toolbar) ?: continue
                    var changed = false
                    if (menu.findItem(ID_SAVE_PDF) == null) {
                        menu.add(android.view.Menu.NONE, ID_SAVE_PDF, android.view.Menu.NONE, "保存为 PDF")
                        changed = true
                    }
                    if (menu.findItem(ID_SAVE_TXT) == null) {
                        menu.add(android.view.Menu.NONE, ID_SAVE_TXT, android.view.Menu.NONE, "保存为 TXT")
                        changed = true
                    }
                    if (changed) {
                        ModuleLog.i(displayName, "novel save menu added (total=${menu.size()})")
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
            candidate.parameterCount == 1 && candidate.parameterTypes[0] == android.view.MenuItem::class.java &&
                (candidate.returnType == Boolean::class.javaPrimitiveType || candidate.returnType == Void.TYPE)
        } ?: return
        if (!hookedMethods.add(method)) return
        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val item = param.args?.firstOrNull() as? android.view.MenuItem ?: return
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

    private fun findToolbars(root: android.view.View): List<android.view.View> {
        val result = ArrayList<android.view.View>()
        collectToolbars(root, result)
        return result
    }

    private fun collectToolbars(root: android.view.View, out: MutableList<android.view.View>) {
        if (hasMenuAccessor(root)) {
            out.add(root)
            return
        }
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index) ?: continue
                collectToolbars(child, out)
            }
        }
    }

    private fun hasMenuAccessor(view: android.view.View): Boolean {
        return menuAccessor(view.javaClass) != null
    }

    private fun menuAccessor(clazz: Class<*>): Method? {
        return when (val cached = menuAccessorCache.getOrPut(clazz) {
            try {
                clazz.methods.firstOrNull { candidate ->
                    candidate.parameterCount == 0 && candidate.returnType == android.view.Menu::class.java
                } ?: NO_ACCESSOR
            } catch (_: Throwable) {
                NO_ACCESSOR
            }
        }) {
            NO_ACCESSOR -> null
            else -> cached as Method
        }
    }

    private fun readMenu(toolbar: android.view.View): android.view.Menu? {
        val method = menuAccessor(toolbar.javaClass) ?: return null
        return ReflectUtil.invoke(toolbar, method) as? android.view.Menu
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
        val store = ConfigStoreRegistry.store
        val folder = try {
            store.get(ConfigKeys.NOVEL_SAVE_FOLDER_URI)
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
                    store.put(ConfigKeys.NOVEL_SAVE_FOLDER_URI, uri.toString())
                } catch (t: Throwable) {
                    ModuleLog.w(displayName, "store save folder failed", t)
                }
                toast(resultActivity, "已选择保存位置")
                runSave(resultActivity, id, asPdf)
            }
            return
        }
        runSave(activity, id, asPdf)
    }

    private fun novelId(activity: Activity): Long {
        return try {
            activity.intent?.getLongExtra("NOVEL_ID", 0L) ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun runSave(activity: Activity, id: Long, asPdf: Boolean) {
        toast(activity, "正在保存…")
        val appContext = activity.applicationContext
        Thread({
            var result: String? = null
            var error: String? = null
            try {
                result = save(appContext, id, asPdf)
                ModuleLog.i(displayName, "novel saved id=$id pdf=$asPdf file=$result")
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                ModuleLog.w(displayName, "novel save failed id=$id", t)
            }
            runOnUi(activity) {
                if (result != null) {
                    toast(activity, "已保存：$result")
                } else {
                    toast(activity, "保存失败：$error")
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

    private fun save(context: Context, id: Long, asPdf: Boolean): String {
        val store = ConfigStoreRegistry.store
        val treeUriText = store.get(ConfigKeys.NOVEL_SAVE_FOLDER_URI)
        if (treeUriText.isBlank()) throw SaveException("尚未选择保存位置")
        val treeUri = Uri.parse(treeUriText)

        val novel = fetchNovel(store, id)
            ?: throw SaveException("获取正文失败：${ProxyHttp.lastFailureReason() ?: "网络请求失败"}")
        val blocks = NovelMarkers.parse(novel.content)
        if (blocks.isEmpty()) throw SaveException("正文为空")
        val sourceUrl = "https://www.pixiv.net/novel/show.php?id=$id"
        val fileName = "小说_${NovelMarkers.safeFileName(novel.title, "novel_$id")}_$id"

        if (asPdf) {
            val labels = NovelMarkers.imageLabels(blocks)
            val images = downloadImages(store, novel, blocks)
            val bytes = NovelPdfWriter.write(
                title = novel.title,
                author = novel.author,
                sourceUrl = sourceUrl,
                blocks = blocks,
                images = images,
                labels = labels
            )
            writeDocument(context, treeUri, "$fileName.pdf", MIME_PDF, bytes)
            return "$fileName.pdf"
        }
        val text = NovelMarkers.toPlainText(novel.title, novel.author, sourceUrl, blocks)
        writeDocument(context, treeUri, "$fileName.txt", MIME_TXT, text.toByteArray(Charsets.UTF_8))
        return "$fileName.txt"
    }

    // ------------------------------------------------------------------
    // 数据获取
    // ------------------------------------------------------------------

    private fun webHeaders(cookie: String?): Map<String, String> {
        val headers = linkedMapOf(
            "Referer" to WEB_REFERER,
            "User-Agent" to WEB_UA,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7"
        )
        if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie
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

    private fun fetchNovel(store: com.heihun.pixivplus.core.config.ConfigStore, id: Long): NovelData? {
        val response = ProxyHttp.get(
            store = store,
            url = URL(String.format(Locale.ROOT, WEB_API_NOVEL, id)),
            headers = webHeaders(webCookie()),
            maxBodyChars = MAX_JSON_CHARS
        ) ?: return null
        if (response.code != 200) {
            ModuleLog.w(displayName, "novel fetch http ${response.code} id=$id")
            return null
        }
        val root = JSONObject(response.body)
        if (root.optBoolean("error", false)) {
            ModuleLog.w(displayName, "novel fetch error id=$id message=${root.optString("message")}")
            return null
        }
        val body = root.optJSONObject("body") ?: return null
        val content = body.optString("content").ifBlank { return null }
        return NovelData(
            id = id,
            title = body.optString("title").ifBlank { "novel_$id" },
            author = body.optString("userName").ifBlank { "未知作者" },
            createDate = body.optString("createDate"),
            content = content,
            embedded = body.optJSONObject("textEmbeddedImages")
        )
    }

    private fun downloadImages(
        store: com.heihun.pixivplus.core.config.ConfigStore,
        novel: NovelData,
        blocks: List<NovelBlock>
    ): Map<String, Bitmap?> {
        val result = HashMap<String, Bitmap?>()
        val seen = HashSet<String>()
        var count = 0
        for (block in blocks) {
            if (block !is NovelBlock.Image) continue
            if (count >= MAX_IMAGES) break
            count++
            val key = NovelMarkers.imageKey(block)
            if (!seen.add(key)) continue
            result[key] = try {
                val url = resolveImageUrl(store, novel, block)
                if (url == null) {
                    ModuleLog.w(displayName, "image url resolve failed key=$key")
                    null
                } else {
                    downloadBitmap(store, url)
                }
            } catch (t: Throwable) {
                ModuleLog.w(displayName, "image download failed key=$key", t)
                null
            }
        }
        return result
    }

    private fun resolveImageUrl(
        store: com.heihun.pixivplus.core.config.ConfigStore,
        novel: NovelData,
        block: NovelBlock.Image
    ): String? {
        return when (block.kind) {
            NovelBlock.Image.ImageKind.UPLOADED -> {
                val entry = novel.embedded?.optJSONObject(block.markerId)
                val urls = entry?.optJSONObject("urls")
                urls?.optString("original")?.takeIf { it.isNotBlank() }
                    ?: urls?.optString("1200x1200")?.takeIf { it.isNotBlank() }
                    ?: urls?.optString("480mw")?.takeIf { it.isNotBlank() }
                    ?: urls?.optString("240mw")?.takeIf { it.isNotBlank() }
            }
            NovelBlock.Image.ImageKind.PIXIV -> resolvePixivImageUrl(store, block)
        }
    }

    /** `ajax/illust/{id}` 拿第 1 页 URL；多页时把 `_p0` 替换为目标页码。 */
    private fun resolvePixivImageUrl(
        store: com.heihun.pixivplus.core.config.ConfigStore,
        block: NovelBlock.Image
    ): String? {
        val illustId = block.markerId
        val response = ProxyHttp.get(
            store = store,
            url = URL(String.format(Locale.ROOT, WEB_API_ILLUST, illustId.toLong())),
            headers = webHeaders(webCookie()),
            maxBodyChars = MAX_JSON_CHARS
        ) ?: return null
        if (response.code != 200) return null
        val root = JSONObject(response.body)
        if (root.optBoolean("error", false)) return null
        val body = root.optJSONObject("body") ?: return null
        val base = body.optJSONObject("urls")?.optString("regular")?.takeIf { it.isNotBlank() }
            ?: body.optJSONObject("urls")?.optString("original")?.takeIf { it.isNotBlank() }
            ?: body.optJSONObject("metaSinglePage")?.optString("original_image_url")?.takeIf { it.isNotBlank() }
            ?: return null
        val page = block.page.coerceAtLeast(1)
        if (page <= 1) return base
        return if (base.contains("_p0.")) base.replaceFirst("_p0.", "_p${page - 1}.") else base
    }

    private fun downloadBitmap(
        store: com.heihun.pixivplus.core.config.ConfigStore,
        url: String
    ): Bitmap? {
        val response = ProxyHttp.getBytes(
            store = store,
            url = URL(url),
            headers = webHeaders(webCookie()),
            maxBytes = MAX_IMAGE_BYTES
        ) ?: return null
        if (response.code != 200) {
            ModuleLog.w(displayName, "image http ${response.code} $url")
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(response.bytes, 0, response.bytes.size)
        if (bitmap == null) {
            ModuleLog.w(displayName, "image decode failed $url")
        }
        return bitmap
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
