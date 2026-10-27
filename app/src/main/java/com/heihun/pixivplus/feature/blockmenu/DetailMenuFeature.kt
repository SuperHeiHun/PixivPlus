package com.heihun.pixivplus.feature.blockmenu

import android.app.Activity
import android.app.AlertDialog
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.WorkContext
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.config.RuleAppender
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.CurrentWorkHook
import com.heihun.pixivplus.core.util.EntityReader
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class DetailMenuFeature : ConfigurableFeature("blockmenu", "详情页快捷屏蔽", ConfigKeys.FILTER_ENABLED) {

    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())

    private companion object {
        const val ID_BLOCK_BY_TAG = 0x00f10001
        const val ID_BLOCK_AUTHOR = 0x00f10002
        const val ID_BLOCK_TITLE = 0x00f10003
        const val POLL_INTERVAL_MS = 700L
        const val CAPTURE_FALLBACK_MS = 5000L
        const val TOOLBAR_CLASS = "com.google.android.material.appbar.MaterialToolbar"
        const val DETAIL_TAGS_VIEW_CLASS =
            "jp.pxv.android.feature.component.androidview.DetailCaptionAndTagsView"
        val NO_ACCESSOR = Any()
    }

    @Volatile
    private var viewCaptureReported = false

    @Volatile
    private var menuReady = false

    @Volatile
    private var lastCaptionCaptureAt = 0L

    private val menuAccessorCache = ConcurrentHashMap<Class<*>, Any>()

    private val menuFlagCache = ConcurrentHashMap<Class<*>, Boolean>()

    private val detailActivities = listOf(
        "jp.pxv.android.feature.illustviewer.detail.IllustDetailSingleActivity",
        "jp.pxv.android.feature.illustviewer.detail.IllustDetailPagerActivity",
        "jp.pxv.android.feature.novelviewer.noveltext.NovelTextActivity"
    )

    @Volatile
    private var currentActivity: Activity? = null

    private val detailDepth = java.util.concurrent.atomic.AtomicInteger(0)

    override fun hook(host: Host) {
        hookToolbarListener(host)
        hookCaptionView(host)
        for (className in detailActivities) {
            hookActivityLifecycle(host, className)
        }
        ModuleLog.i(displayName, "detail quick-block menu hooks installed")
    }

    private fun hookCaptionView(host: Host) {
        val installed = CurrentWorkHook.install(host, displayName) { entity, id, defaultType ->
            lastCaptionCaptureAt = System.currentTimeMillis()
            capture(entity, id, defaultType)
        }
        ModuleLog.i(displayName, "caption view hooks installed: $installed")
    }

    private fun capture(entity: Any, id: Long, defaultType: String) {
        try {
            val work = com.heihun.pixivplus.core.net.PayloadProcessor.lookupWork(id)
            val entityTags = EntityReader.tags(entity)
            val snapshot = com.heihun.pixivplus.core.WorkSnapshot(
                id = id,
                type = EntityReader.type(entity, defaultType),
                title = EntityReader.title(entity) ?: work?.title,
                authorName = EntityReader.authorName(entity) ?: work?.authorName,
                authorAccount = EntityReader.authorAccount(entity),
                tags = entityTags.ifEmpty { work?.tags.orEmpty() }
            )
            WorkContext.update(snapshot)
            if (entityTags.isNotEmpty()) {
                ModuleLog.d(
                    displayName,
                    "captured work id=$id type=${snapshot.type} tags=${entityTags.size} title=${snapshot.title}"
                )
            }
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "capture failed", t)
        }
    }

    private fun hookToolbarListener(host: Host) {
        val toolbarClass = ReflectUtil.loadClass(host.classLoader, TOOLBAR_CLASS)
        if (toolbarClass == null) {
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
            ModuleLog.i(displayName, "toolbar listener hook installed")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "toolbar listener hook failed", t)
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
                    if (item.itemId == ID_BLOCK_BY_TAG || item.itemId == ID_BLOCK_AUTHOR || item.itemId == ID_BLOCK_TITLE) {
                        ModuleLog.i(displayName, "quick-block item clicked id=${item.itemId} title=${item.title}")
                    }
                    when (item.itemId) {
                        ID_BLOCK_BY_TAG -> {
                            showTagDialog()
                            param.result = true
                        }
                        ID_BLOCK_AUTHOR -> {
                            blockAuthor()
                            param.result = true
                        }
                        ID_BLOCK_TITLE -> {
                            blockTitle()
                            param.result = true
                        }
                    }
                }
            })
            ModuleLog.i(displayName, "menu click hook installed on ${listenerClass.name}.${method.name}")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "menu click hook failed", t)
        }
    }

    private fun hookActivityLifecycle(host: Host, className: String) {
        val clazz = ReflectUtil.loadClass(host.classLoader, className) ?: return
        val onResume = ReflectUtil.findMethod(clazz, "onResume") ?: return
        if (hookedMethods.add(onResume)) {
            XposedBridge.hookMethod(onResume, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!isDetailActivity(activity)) return
                    detailDepth.incrementAndGet()
                    startPolling(activity)
                }
            })
        }
        val onPause = ReflectUtil.findMethod(clazz, "onPause")
        if (onPause != null && hookedMethods.add(onPause)) {
            XposedBridge.hookMethod(onPause, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!isDetailActivity(activity)) return
                    activity.window?.decorView?.removeCallbacks(pollTask)
                    if (currentActivity === activity) currentActivity = null
                    val remaining = detailDepth.updateAndGet { value -> if (value > 0) value - 1 else 0 }
                    if (remaining == 0) WorkContext.clear()
                }
            })
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            val activity = currentActivity ?: return
            if (menuReady) return
            try {
                injectInto(activity)
            } catch (_: Throwable) {
            }
            if (menuReady) return
            activity.window?.decorView?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun startPolling(activity: Activity) {
        currentActivity = activity
        menuReady = false
        activity.window?.decorView?.removeCallbacks(pollTask)
        activity.window?.decorView?.postDelayed(pollTask, 400L)
    }

    private fun isDetailActivity(activity: Activity): Boolean {
        return detailActivities.contains(activity.javaClass.name)
    }

    private fun injectInto(activity: Activity): Boolean {
        return try {
            captureFromViews(activity)
            val toolbar = findToolbar(activity.window?.decorView ?: return false) ?: return false
            val menu = readMenu(toolbar) ?: return false
            addMenuItems(menu)
            menuReady = true
            true
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "menu injection failed", t)
            false
        }
    }

    private fun captureFromViews(activity: Activity) {
        try {
            if (System.currentTimeMillis() - lastCaptionCaptureAt < CAPTURE_FALLBACK_MS) return
            val root = activity.window?.decorView ?: return
            val holder = findDetailView(root)
            if (holder == null) {
                if (!viewCaptureReported) {
                    viewCaptureReported = true
                    ModuleLog.d(displayName, "caption view not in tree yet")
                }
                return
            }
            val entity = ReflectUtil.readField(holder, "f")
            if (entity == null) {
                if (!viewCaptureReported) {
                    viewCaptureReported = true
                    ModuleLog.d(displayName, "caption view entity field empty")
                }
                return
            }
            val idMethod = ReflectUtil.findMethod(entity.javaClass, "getId") ?: return
            val id = ReflectUtil.invoke(entity, idMethod) as? Long ?: return
            capture(entity, id, "ILLUST")
        } catch (_: Throwable) {
        }
    }

    private fun findDetailView(root: View): View? {
        if (root.javaClass.name == DETAIL_TAGS_VIEW_CLASS) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index) ?: continue
                val found = findDetailView(child) ?: continue
                return found
            }
        }
        return null
    }

    private fun findToolbar(root: View): View? {
        if (hasMenuAccessor(root)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index) ?: continue
                val found = findToolbar(child) ?: continue
                return found
            }
        }
        return null
    }

    private fun hasMenuAccessor(view: View): Boolean {
        return menuFlagCache.getOrPut(view.javaClass) {
            try {
                view.javaClass.methods.any { method ->
                    method.parameterCount == 0 && method.returnType == Menu::class.java
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun readMenu(toolbar: View): Menu? {
        val cached = menuAccessorCache.getOrPut(toolbar.javaClass) {
            try {
                toolbar.javaClass.methods.firstOrNull { candidate ->
                    candidate.parameterCount == 0 && candidate.returnType == Menu::class.java
                } ?: NO_ACCESSOR
            } catch (_: Throwable) {
                NO_ACCESSOR
            }
        }
        if (cached === NO_ACCESSOR) return null
        return ReflectUtil.invoke(toolbar, cached as Method) as? Menu
    }

    private fun addMenuItems(menu: Menu): Boolean {
        var changed = false
        if (menu.findItem(ID_BLOCK_BY_TAG) == null) {
            menu.add(Menu.NONE, ID_BLOCK_BY_TAG, Menu.NONE, "按Tag屏蔽")
            changed = true
        }
        if (menu.findItem(ID_BLOCK_AUTHOR) == null) {
            menu.add(Menu.NONE, ID_BLOCK_AUTHOR, Menu.NONE, "屏蔽当前作者")
            changed = true
        }
        if (menu.findItem(ID_BLOCK_TITLE) == null) {
            menu.add(Menu.NONE, ID_BLOCK_TITLE, Menu.NONE, "屏蔽当前标题")
            changed = true
        }
        if (changed) {
            ModuleLog.i(displayName, "quick-block menu items added (total=${menu.size()})")
        }
        return changed
    }

    private fun showTagDialog() {
        val activity = currentActivity
        val tags = WorkContext.current?.tags.orEmpty()
        ModuleLog.i(displayName, "showTagDialog activity=${activity != null} tags=${tags.size}")
        if (activity == null) {
            ModuleLog.w(displayName, "no activity for dialog")
            return
        }
        if (tags.isEmpty()) {
            toast(activity, "未获取到当前作品的标签")
            return
        }
        val checked = BooleanArray(tags.size)
        try {
            AlertDialog.Builder(activity)
                .setTitle("选择要屏蔽的标签")
                .setMultiChoiceItems(tags.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton("取消", null)
                .setPositiveButton("保存") { _, _ ->
                    val selected = tags.filterIndexed { index, _ -> checked[index] }
                    if (selected.isEmpty()) {
                        toast(activity, "未选择标签")
                        return@setPositiveButton
                    }
                    val current = ConfigStoreRegistry.store.get(ConfigKeys.FILTER_TAGS)
                    ConfigStoreRegistry.store.put(
                        ConfigKeys.FILTER_TAGS,
                        RuleAppender.append(current, selected)
                    )
                    toast(activity, "已添加 ${selected.size} 个屏蔽标签")
                }
                .show()
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "tag dialog failed", t)
        }
    }

    private fun blockAuthor() {
        val activity = currentActivity ?: return
        val snapshot = WorkContext.current
        val author = snapshot?.authorName?.takeIf { it.isNotEmpty() }
            ?: snapshot?.authorAccount?.takeIf { it.isNotEmpty() }
        if (author.isNullOrEmpty()) {
            toast(activity, "未获取到当前作者")
            return
        }
        val current = ConfigStoreRegistry.store.get(ConfigKeys.FILTER_AUTHORS)
        ConfigStoreRegistry.store.put(ConfigKeys.FILTER_AUTHORS, RuleAppender.append(current, listOf(author)))
        toast(activity, "已屏蔽作者：$author")
    }

    private fun blockTitle() {
        val activity = currentActivity ?: return
        val title = WorkContext.current?.title?.takeIf { it.isNotEmpty() }
        if (title.isNullOrEmpty()) {
            toast(activity, "未获取到当前标题")
            return
        }
        val current = ConfigStoreRegistry.store.get(ConfigKeys.FILTER_KEYWORDS)
        ConfigStoreRegistry.store.put(ConfigKeys.FILTER_KEYWORDS, RuleAppender.append(current, listOf(title)))
        toast(activity, "已屏蔽标题：$title")
    }

    private fun toast(activity: Activity, message: String) {
        try {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
