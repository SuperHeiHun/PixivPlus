package com.heihun.pixivplus.feature.illustsave

import android.app.Activity
import android.net.Uri
import android.view.Menu
import android.widget.Toast
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.media.MediaExporter
import com.heihun.pixivplus.core.media.MediaRepository
import com.heihun.pixivplus.core.media.MediaInfo
import com.heihun.pixivplus.core.media.MediaNames
import com.heihun.pixivplus.core.media.OriginalImage
import com.heihun.pixivplus.core.util.CurrentWorkHook
import com.heihun.pixivplus.core.util.DetailPagerResolver
import com.heihun.pixivplus.core.util.EntityReader
import com.heihun.pixivplus.core.util.MenuHooks
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.core.util.WorkPopupHooks
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import com.heihun.pixivplus.ui.ActivityResultBridge
import com.heihun.pixivplus.ui.panel.ChoiceDialog
import com.heihun.pixivplus.ui.panel.ConfirmDialog
import com.heihun.pixivplus.ui.panel.PageSelectionDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class IllustSaveFeature : ConfigurableFeature("illust_save", "插画与动图保存", ConfigKeys.ILLUST_SAVE_ENABLED) {
    private companion object {
        const val SAVE_ORIGINAL = 0x00f40001
        const val SAVE_GIF = 0x00f40002
        val ACTIVITIES = listOf(
            "jp.pxv.android.feature.illustviewer.detail.IllustDetailSingleActivity",
            "jp.pxv.android.feature.illustviewer.detail.IllustDetailPagerActivity"
        )
    }
    private var currentActivity: Activity? = null
    private val methods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val entities = ConcurrentHashMap<Long, Any>()
    private val busy = AtomicBoolean(false)
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "plp-media-save").apply { isDaemon = true }
    }
    private var progressToast: Toast? = null
    private var lastProgress = 0L

    override fun hook(host: Host) {
        CurrentWorkHook.install(host, displayName) { entity, id, _ ->
            if (isIllust(entity)) {
                if (entities.size >= 100) entities.clear()
                entities[id] = entity
            }
        }
        WorkPopupHooks.register(host, "保存原图 / ZIP", { isEnabled(ConfigStoreRegistry.store) && isIllust(it) }) { activity, entity ->
            prepareOriginals(activity, idOf(entity), entity)
        }
        WorkPopupHooks.register(host, "保存 GIF", { isEnabled(ConfigStoreRegistry.store) && isUgoira(it) }) { activity, entity ->
            prepareGif(activity, idOf(entity), entity)
        }
        MenuHooks.installClickHook(host, methods, displayName) { item ->
            val activity = currentActivity
            if (activity == null || !isEnabled(ConfigStoreRegistry.store)) false
            else when (item.itemId) {
                SAVE_ORIGINAL -> { val (id, entity) = currentWork(activity); prepareOriginals(activity, id, entity); true }
                SAVE_GIF -> { val (id, entity) = currentWork(activity); prepareGif(activity, id, entity); true }
                else -> false
            }
        }
        for (name in ACTIVITIES) {
            val clazz = ReflectUtil.loadClass(host.classLoader, name) ?: continue
            val resume = ReflectUtil.findMethod(clazz, "onResume")
            if (resume != null && methods.add(resume)) XposedBridge.hookMethod(resume, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name !in ACTIVITIES) return
                    currentActivity = activity
                    activity.window.decorView.removeCallbacks(poll)
                    activity.window.decorView.postDelayed(poll, 400)
                }
            })
            val pause = ReflectUtil.findMethod(clazz, "onPause")
            if (pause != null && methods.add(pause)) XposedBridge.hookMethod(pause, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name !in ACTIVITIES) return
                    activity.window.decorView.removeCallbacks(poll)
                    if (currentActivity === activity) currentActivity = null
                }
            })
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val activity = currentActivity ?: return
            try {
                val (_, entity) = currentWork(activity)
                for (toolbar in MenuHooks.findToolbars(activity.window.decorView)) {
                    val menu = MenuHooks.readMenu(toolbar) ?: continue
                    if (isEnabled(ConfigStoreRegistry.store)) {
                        if (menu.findItem(SAVE_ORIGINAL) == null) menu.add(Menu.NONE, SAVE_ORIGINAL, Menu.NONE, "保存原图 / ZIP")
                        if (entity != null && isUgoira(entity)) {
                            if (menu.findItem(SAVE_GIF) == null) menu.add(Menu.NONE, SAVE_GIF, Menu.NONE, "保存 GIF")
                        } else menu.removeItem(SAVE_GIF)
                    } else { menu.removeItem(SAVE_ORIGINAL); menu.removeItem(SAVE_GIF) }
                }
            } catch (t: Exception) { ModuleLog.w(displayName, "media menu update failed", t) }
            activity.window.decorView.postDelayed(this, 700)
        }
    }

    private fun idOf(entity: Any?): Long = entity?.let {
        ReflectUtil.invoke(it, ReflectUtil.findMethod(it.javaClass, "getId")) as? Long
    } ?: 0L
    private fun isIllust(entity: Any): Boolean = entity.javaClass.name == "jp.pxv.android.domain.commonentity.PixivIllust"
    private fun isUgoira(entity: Any): Boolean = isIllust(entity) && ReflectUtil.readField(entity, "type") == "ugoira"

    private fun currentWork(activity: Activity): Pair<Long, Any?> {
        val pager = DetailPagerResolver.currentItem(activity)
        if (pager != null && isIllust(pager)) return idOf(pager) to pager
        // 翻页活动无法确定当前页时不借用全局作品上下文，宁可提示重试也不保存错作品。
        val id = if (activity.javaClass.name.endsWith("IllustDetailSingleActivity"))
            activity.intent?.getLongExtra("ILLUST_ID", 0L) ?: 0L else 0L
        return id to entities[id]
    }

    private fun prepareOriginals(activity: Activity, id: Long, entity: Any?) {
        if (id <= 0) { toast(activity, "未获取到当前作品，请等详情加载后重试"); return }
        runJob(activity, "正在读取原图信息…", {
            val store = ConfigStoreRegistry.store
            MediaRepository.info(store, id, entity) to MediaRepository.originals(store, id, entity)
        }) { (info, images) ->
            ChoiceDialog.show(activity, "选择保存图片（共 ${images.size} 张）", listOf("全部保存", "自定义页码"), -1) { which ->
                if (which == 0) chooseFormat(activity, info, images)
                else PageSelectionDialog.show(activity, images.size) { pages ->
                    chooseFormat(activity, info, images.filter { it.page in pages })
                }
            }
        }
    }

    private fun chooseFormat(activity: Activity, info: MediaInfo, images: List<OriginalImage>) {
        ChoiceDialog.show(activity, "保存格式（${images.size} 张）", listOf("保存原图到作品文件夹", "打包为 ZIP"), -1) { which ->
            val store = ConfigStoreRegistry.store
            val stem = MediaNames.stem(info.author, info.title, info.id, store.get(ConfigKeys.ILLUST_SAVE_NAME_TEMPLATE))
            val linksText = if (store.get(ConfigKeys.ILLUST_SAVE_INCLUDE_LINKS)) info.linksText() else null
            chooseFolder(activity) { folder ->
                runJob(activity, "正在保存原图…", {
                    MediaExporter.saveOriginals(activity.applicationContext, store, folder, stem, images, which == 1, linksText) {
                        progress(activity, it)
                    }
                }) { names -> toast(activity, "已保存 ${names.size} 个文件：${names.first()}") }
            }
        }
    }

    private fun prepareGif(activity: Activity, id: Long, entity: Any?) {
        if (id <= 0) { toast(activity, "未获取到当前动图"); return }
        ConfirmDialog.show(activity, "保存 GIF", "将下载 ugoira 帧并转换为循环 GIF，保留每帧时长。\nGIF 使用 256 色，色彩会与原图有所差异。", "保存") {
            chooseFolder(activity) { folder ->
                runJob(activity, "正在准备动图…", {
                    val store = ConfigStoreRegistry.store
                    val info = MediaRepository.info(store, id, entity)
                    val stem = MediaNames.stem(info.author, info.title, id, store.get(ConfigKeys.ILLUST_SAVE_NAME_TEMPLATE))
                    MediaExporter.saveGif(activity.applicationContext, store, folder, id, stem) { progress(activity, it) }
                }) { name -> toast(activity, "已保存：$name") }
            }
        }
    }

    private fun chooseFolder(activity: Activity, onReady: (Uri) -> Unit) {
        val store = ConfigStoreRegistry.store
        val folder = store.get(ConfigKeys.ILLUST_SAVE_FOLDER_URI)
        if (folder.isNotBlank()) { onReady(Uri.parse(folder)); return }
        toast(activity, "首次保存请选择保存位置")
        ActivityResultBridge.pickFolder(activity) { resultActivity, uri ->
            if (uri == null) toast(resultActivity, "未选择保存位置，已取消")
            else { store.put(ConfigKeys.ILLUST_SAVE_FOLDER_URI, uri.toString()); onReady(uri) }
        }
    }

    private fun <T> runJob(activity: Activity, initial: String, work: () -> T, done: (T) -> Unit) {
        if (!busy.compareAndSet(false, true)) { toast(activity, "正在保存中，请稍候…"); return }
        lastProgress = 0
        toast(activity, initial)
        executor.execute {
            try {
                val result = work()
                activity.runOnUiThread {
                    progressToast?.cancel()
                    if (!activity.isFinishing && !activity.isDestroyed) done(result)
                }
            } catch (t: Throwable) {
                if (t !is Exception && t !is OutOfMemoryError) throw t
                ModuleLog.w(displayName, "media save failed", t)
                activity.runOnUiThread {
                    progressToast?.cancel()
                    val reason = if (t is OutOfMemoryError) "内存不足，请减少保存页数或选择较小的动图" else t.message ?: t.javaClass.simpleName
                    toast(activity, "保存失败：$reason")
                }
            } finally { busy.set(false) }
        }
    }

    private fun progress(activity: Activity, message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastProgress < 800) return
        lastProgress = now
        activity.runOnUiThread {
            progressToast?.cancel()
            progressToast = Toast.makeText(activity, message, Toast.LENGTH_SHORT).apply { show() }
        }
    }
    private fun toast(activity: Activity, message: String) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
}
