package com.heihun.pixivplus.feature.illustsave

import android.app.Activity
import android.net.Uri
import android.view.Menu
import android.widget.Toast
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.CommonText as C
import com.heihun.pixivplus.core.i18n.WorkText as W
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.media.DownloadJobs
import com.heihun.pixivplus.core.media.DownloadPlan
import com.heihun.pixivplus.core.media.DownloadFormat
import com.heihun.pixivplus.core.media.MediaRepository
import com.heihun.pixivplus.core.media.MediaInfo
import com.heihun.pixivplus.core.media.MediaNames
import com.heihun.pixivplus.core.media.OriginalImage
import com.heihun.pixivplus.core.util.CurrentWorkHook
import com.heihun.pixivplus.core.util.DetailPagerResolver
import com.heihun.pixivplus.core.util.EntityReader
import com.heihun.pixivplus.core.util.FeatureMenuOrder
import com.heihun.pixivplus.core.util.MenuHooks
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.core.util.WorkPopupHooks
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import com.heihun.pixivplus.ui.panel.ChoiceDialog
import com.heihun.pixivplus.ui.panel.ConfirmDialog
import com.heihun.pixivplus.ui.panel.ImageSelectionDialog
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

    override fun onLanguageChanged(host: Host) {
        currentActivity?.window?.decorView?.let { view ->
            view.removeCallbacks(poll)
            view.post(poll)
        }
    }

    override fun hook(host: Host) {
        CurrentWorkHook.install(host, displayName) { entity, id, _ ->
            if (isIllust(entity)) {
                if (entities.size >= 100) entities.clear()
                entities[id] = entity
            }
        }
        WorkPopupHooks.register(host, { W.SAVE_ORIGINAL_IMAGES() }, { isEnabled(ConfigStoreRegistry.store) && isIllust(it) }) { activity, entity ->
            prepareOriginals(activity, idOf(entity), entity)
        }
        WorkPopupHooks.register(host, { W.SAVE_GIF() }, { isEnabled(ConfigStoreRegistry.store) && isUgoira(it) }) { activity, entity ->
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
                        MenuHooks.ensureItem(menu, SAVE_ORIGINAL, FeatureMenuOrder.SAVE_ORIGINAL, W.SAVE_ORIGINAL_IMAGES())
                        if (entity != null && isUgoira(entity)) {
                            MenuHooks.ensureItem(menu, SAVE_GIF, FeatureMenuOrder.SAVE_GIF, W.SAVE_GIF())
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
        if (id <= 0) { toast(activity, W.WORK_NOT_READY()); return }
        runJob(activity, W.READ_ORIGINALS(), {
            val store = ConfigStoreRegistry.store
            MediaRepository.info(store, id, entity) to MediaRepository.originals(store, id, entity)
        }) { (info, images) ->
            ChoiceDialog.show(activity, W.CHOOSE_IMAGES(images.size), listOf(W.SAVE_ALL_IMAGES(), W.PICK_IMAGES_PREVIEWS()), -1) { which ->
                if (which == 0) chooseFormat(activity, info, images)
                else ImageSelectionDialog.show(activity, images, { it.previewUrl ?: it.url }) { pages ->
                    chooseFormat(activity, info, images.filter { it.page in pages })
                }
            }
        }
    }

    private fun chooseFormat(activity: Activity, info: MediaInfo, images: List<OriginalImage>) {
        ChoiceDialog.show(activity, W.CHOOSE_FORMAT(images.size), listOf(W.ONLY_ORIGINALS(), W.SAVE_ZIP()), -1) { which ->
            val store = ConfigStoreRegistry.store
            val stem = try {
                MediaNames.stem(info.author, info.title, info.id, store.get(ConfigKeys.ILLUST_SAVE_NAME_TEMPLATE))
            } catch (_: IllegalArgumentException) {
                toast(activity, W.FIX_FILENAME())
                return@show
            }
            val linksText = if (store.get(ConfigKeys.ILLUST_SAVE_INCLUDE_LINKS)) info.linksText() else null
            chooseFolder(activity) { folder ->
                val plan = DownloadPlan(info.id, stem, folder.toString(), if (which == 1) DownloadFormat.ZIP else DownloadFormat.IMAGES,
                    images.map { it.page }, linksText)
                DownloadJobs.submit(activity, plan, images = images)
            }
        }
    }

    private fun prepareGif(activity: Activity, id: Long, entity: Any?) {
        if (id <= 0) { toast(activity, W.NO_UGOIRA()); return }
        ConfirmDialog.show(activity, W.SAVE_GIF(), W.GIF_INFO(), C.SAVE()) {
            chooseFolder(activity) { folder ->
                runJob(activity, W.PREPARE_UGOIRA(), {
                    val store = ConfigStoreRegistry.store
                    val info = MediaRepository.info(store, id, entity)
                    val stem = MediaNames.stem(info.author, info.title, id, store.get(ConfigKeys.ILLUST_SAVE_NAME_TEMPLATE))
                    DownloadPlan(id, stem, folder.toString(), DownloadFormat.GIF)
                }) { plan -> DownloadJobs.submit(activity, plan) }
            }
        }
    }

    private fun chooseFolder(activity: Activity, onReady: (Uri) -> Unit) {
        val store = ConfigStoreRegistry.store
        val folder = store.get(ConfigKeys.ILLUST_SAVE_FOLDER_URI)
        if (folder.isNotBlank()) { onReady(Uri.parse(folder)); return }
        onReady(Uri.parse(com.heihun.pixivplus.core.media.OutputPolicy.DEFAULT_FOLDER))
    }

    private fun <T> runJob(activity: Activity, initial: String, work: () -> T, done: (T) -> Unit) {
        if (!busy.compareAndSet(false, true)) { toast(activity, W.SAVE_BUSY()); return }
        toast(activity, initial)
        executor.execute {
            try {
                val result = work()
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) done(result)
                }
            } catch (t: Throwable) {
                if (t !is Exception && t !is OutOfMemoryError) throw t
                ModuleLog.w(displayName, "media save failed", t)
                activity.runOnUiThread {
                    val reason = if (t is OutOfMemoryError) W.SAVE_NO_MEMORY() else t.message ?: t.javaClass.simpleName
                    toast(activity, C.SAVE_FAILED(reason))
                }
            } finally { busy.set(false) }
        }
    }

    private fun toast(activity: Activity, message: String) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
}
