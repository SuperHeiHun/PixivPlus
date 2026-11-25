package com.heihun.pixivplus.feature.drawer

import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.DrawerText as D
import com.heihun.pixivplus.core.i18n.I18n
import com.heihun.pixivplus.core.i18n.SettingsText as S
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.core.util.DetailPagerResolver
import com.heihun.pixivplus.ui.panel.SettingsPanelBuilder
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 侧边栏精简：在主抽屉菜单（标准 Material NavigationView）末尾注入一个
 * 「精简侧边栏 / 恢复全部菜单」按钮，一键隐藏低频菜单项（投稿作品、我的组等）。
 * 隐藏清单可在模块设置里逐项配置。
 */
class DrawerSlimFeature : ConfigurableFeature(
    id = "drawer_slim",
    displayName = "侧边栏精简",
    configKey = ConfigKeys.DRAWER_SLIM_ENABLED
) {

    companion object {
        /** 主抽屉 NavigationView 的资源 id 名（宿主 resources 实测确认）。 */
        private const val NAVIGATION_VIEW_ID_NAME = "navigation_view"

        /** 注入按钮的自定义 itemId（避开宿主 0x7f 段）。 */
        private const val TOGGLE_ITEM_ID = 0x60011001
        private const val TASK_ITEM_ID = 0x60011002
        private const val TASK_ENTRY_NAME = "drawer_menu_pixivplus_tasks"

        /** 注入按钮的排序权重，确保排在菜单末尾。 */
        private const val TOGGLE_ORDER = 999

        /** 持久化使用宿主资源名，只有设置页的显示标签跟随语言。 */
        internal val DRAWER_ITEM_LABELS = linkedMapOf(
            "drawer_menu_home" to D.HOME,
            "drawer_menu_new_works" to D.NEW_WORKS,
            "drawer_menu_search" to D.SEARCH,
            "drawer_menu_notification" to D.NOTIFICATIONS,
            "drawer_menu_upload" to D.POST_WORK,
            "drawer_menu_works" to D.YOUR_WORKS,
            "drawer_menu_collection" to D.BOOKMARKS,
            "drawer_menu_browsing_history" to S.HISTORY,
            "drawer_menu_novel_marker" to D.NOVEL_MARKER,
            "drawer_menu_connections" to D.CONNECTIONS,
            "drawer_menu_following" to D.FOLLOWING,
            "drawer_menu_follower" to D.FOLLOWERS,
            "drawer_menu_mypixiv" to D.MY_PIXIV,
            "drawer_menu_others" to D.OTHER_HEADING,
            "drawer_menu_about" to D.ABOUT_PIXIV,
            "drawer_menu_support" to D.SUPPORT,
            "drawer_menu_setting" to D.SETTINGS,
            "drawer_menu_mute_setting" to D.MUTE_SETTINGS,
            TASK_ENTRY_NAME to D.TASKS_ENTRY
        )
    }

    private val navViews = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val navRefs = ConcurrentHashMap<Int, WeakReference<View>>()
    private data class TrackedItem(val item: WeakReference<MenuItem>, val view: WeakReference<View>, val entryName: String)
    private val trackedItems = ConcurrentHashMap<Int, TrackedItem>()
    private val visibilityHooks = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private var installed = false

    override fun hook(host: Host) {
        if (installed) return
        installed = true
        I18n.addListener(::applyToAll)
        val clazz = ReflectUtil.loadClass(host.classLoader, "com.google.android.material.navigation.NavigationView")
        if (clazz == null) {
            ModuleLog.w(displayName, "NavigationView class not found, feature skipped")
            return
        }
        try {
            for (constructor in clazz.declaredConstructors) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (!isMainNavigationView(view)) return
                        rememberView(view)
                        // 构造后菜单可能尚未 inflate 完成，延迟到下一帧应用。
                        view.postDelayed({ applyToView(view) }, 300L)
                    }
                })
            }
            // 宿主 MenuBuilder/onItemsChanged 已混淆，不能依赖其原始类名或方法名。
            // 绘制前补查新建/替换的菜单、setGroupVisible 等绕过公开 setVisible 的更新。
            // 每次只在确有差异时写可见性，不排队轮询或制造菜单更新循环。
            val draw = ReflectUtil.findMethod(clazz, "dispatchDraw", android.graphics.Canvas::class.java)
            if (draw != null) XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!isMainNavigationView(view)) return
                    rememberView(view)
                    applyToView(view)
                }
            })
            ModuleLog.i(displayName, "navigation view hooks installed (scoped visibility and draw guard)")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "hook NavigationView failed", t)
        }
    }

    override fun onConfigChanged(host: Host) {
        super.onConfigChanged(host)
        ModuleLog.i(displayName, "drawer slim enabled=${isEnabled(ConfigStoreRegistry.store)}")
        applyToAll()
    }

    private fun isMainNavigationView(view: View): Boolean {
        if (view.id == View.NO_ID) return false
        return try {
            view.resources.getResourceEntryName(view.id) == NAVIGATION_VIEW_ID_NAME
        } catch (_: Throwable) {
            false
        }
    }

    private fun rememberView(view: View) {
        val key = System.identityHashCode(view)
        if (navRefs[key]?.get() !== view) navRefs[key] = WeakReference(view)
        navViews.add(key)
        if (navRefs.size > 8) {
            navRefs.entries.removeIf {
                if (it.value.get() == null) { navViews.remove(it.key); true } else false
            }
        }
        if (trackedItems.size > 256) {
            trackedItems.entries.removeIf { it.value.item.get() == null || it.value.view.get() == null }
        }
    }

    private fun trackItem(view: View, item: MenuItem, entryName: String) {
        val key = System.identityHashCode(item)
        val existing = trackedItems[key]
        if (existing != null && existing.item.get() === item && existing.view.get() === view && existing.entryName == entryName) return
        trackedItems[key] = TrackedItem(WeakReference(item), WeakReference(view), entryName)
        val method = ReflectUtil.findMethod(item.javaClass, "setVisible", Boolean::class.javaPrimitiveType!!) ?: return
        if (!visibilityHooks.add(method)) return
        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] != true) return
                    val tracked = trackedItems[System.identityHashCode(param.thisObject)] ?: return
                    if (tracked.item.get() !== param.thisObject || tracked.view.get() == null) return
                    if (DrawerVisibilityPolicy.mustHide(tracked.entryName, slimEnabled(), hiddenItems(),
                            ConfigStoreRegistry.store.get(ConfigKeys.TASK_LIST_ENABLED))) {
                        // 改实参，不递归调用 setVisible；正常保留宿主返回值与菜单通知。
                        param.args[0] = false
                    }
                }
            })
        } catch (t: Throwable) {
            visibilityHooks.remove(method)
            ModuleLog.w(displayName, "hook menu visibility failed", t)
        }
    }

    private fun entryName(view: View, item: MenuItem): String? {
        return if (item.itemId == TASK_ITEM_ID) TASK_ENTRY_NAME else try {
            view.resources.getResourceEntryName(item.itemId)
        } catch (_: Throwable) {
            null
        }
    }

    /** 精简模式下需要隐藏的菜单项资源名集合。 */
    private fun hiddenItems(): Set<String> {
        return ConfigStoreRegistry.store
            .get(ConfigKeys.DRAWER_SLIM_ITEMS)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun slimEnabled(): Boolean = isEnabled(ConfigStoreRegistry.store)

    private fun applyToAll() {
        for (key in navViews) {
            val view = navRefs[key]?.get() ?: continue
            applyToView(view)
        }
    }

    private fun applyToView(view: View) {
        val menu = ReflectUtil.invoke(view, ReflectUtil.findMethod(view.javaClass, "getMenu")) as? Menu ?: return
        val slim = slimEnabled()
        val hidden = hiddenItems()

        ensureToggleItem(menu, slim)
        ensureTaskItem(view, menu)

        var changed = 0
        applyToMenu(view, menu, slim, hidden) { changed += it }
        if (changed > 0) {
            ModuleLog.i(displayName, "drawer slim applied: $changed item(s) toggled, slim=$slim")
        }
    }

    /**
     * 遍历菜单并按清单切换可见性；「其他」「我的」是带 <menu> 的子菜单项
     * （menu.size() 只含顶层，about/support/mute_setting 等在子菜单里），必须递归。
     */
    private fun applyToMenu(view: View, menu: Menu, slim: Boolean, hidden: Set<String>, onToggled: (Int) -> Unit) {
        var changed = 0
        for (index in 0 until menu.size()) {
            val item = menu.getItem(index) ?: continue
            if (item.itemId == TOGGLE_ITEM_ID) continue
            // 不以父项是否有可解析的资源名决定是否遍历子菜单。
            val subMenu = item.subMenu
            if (subMenu != null) applyToMenu(view, subMenu, slim, hidden, onToggled)
            val entryName = entryName(view, item) ?: continue
            // 只管理侧边栏自身的菜单项，App 默认不可见的其他项不得强改可见性。
            if (!entryName.startsWith("drawer_menu_")) continue
            trackItem(view, item, entryName)
            val targetVisible = !DrawerVisibilityPolicy.mustHide(entryName, slim, hidden,
                ConfigStoreRegistry.store.get(ConfigKeys.TASK_LIST_ENABLED))
            if (item.isVisible != targetVisible) {
                item.isVisible = targetVisible
                changed++
            }
        }
        if (changed > 0) onToggled(changed)
    }

    /** 注入「精简侧边栏」按钮（幂等，点击切换精简模式；order 放大保证排在菜单末尾）。 */
    private fun ensureTaskItem(view: View, menu: Menu) {
        if (!ConfigStoreRegistry.store.get(ConfigKeys.TASK_LIST_ENABLED)) return
        val title = D.TASKS_ENTRY()
        val existing = menu.findItem(TASK_ITEM_ID)
        if (existing != null) {
            if (existing.title?.toString() != title) existing.title = title
            return
        }
        menu.add(0, TASK_ITEM_ID, 998, title).apply {
            setIcon(android.R.drawable.ic_menu_agenda)
            isCheckable = false
            setOnMenuItemClickListener {
                DetailPagerResolver.activityOf(view.context)?.let { activity ->
                    SettingsPanelBuilder.show(activity, SettingsPanelBuilder.Screen.TASKS)
                }
                true
            }
        }
    }

    private fun ensureToggleItem(menu: Menu, slim: Boolean) {
        val existing = menu.findItem(TOGGLE_ITEM_ID)
        val title = if (slim) D.RESTORE() else D.SIMPLIFY()
        if (existing != null) {
            if (existing.title?.toString() != title) existing.title = title
            return
        }
        try {
            val item = menu.add(0, TOGGLE_ITEM_ID, TOGGLE_ORDER, title)
            item.setOnMenuItemClickListener {
                onToggleClicked()
                true
            }
            ModuleLog.i(displayName, "drawer slim toggle button added")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "add toggle item failed", t)
        }
    }

    private fun onToggleClicked() {
        val newValue = !slimEnabled()
        try {
            ConfigStoreRegistry.store.put(ConfigKeys.DRAWER_SLIM_ENABLED, newValue)
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "toggle slim failed", t)
            return
        }
        applyToAll()
        try {
            val context = Host.context()
            Toast.makeText(
                context,
                if (newValue) D.SIMPLIFIED() else D.RESTORED(),
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {
        }
        ModuleLog.i(displayName, "drawer slim toggled -> $newValue")
    }
}
