package com.heihun.pixivplus.feature.drawer

import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
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

        /** 注入按钮的排序权重，确保排在菜单末尾。 */
        private const val TOGGLE_ORDER = 999

        /** 全部已知侧边栏菜单项资源名 -> 中文标签（设置页展示用）。 */
        val DRAWER_ITEM_LABELS: Map<String, String> = linkedMapOf(
            "drawer_menu_home" to "主页",
            "drawer_menu_new_works" to "最新",
            "drawer_menu_search" to "搜索",
            "drawer_menu_notification" to "通知",
            "drawer_menu_upload" to "投稿作品",
            "drawer_menu_works" to "你的作品",
            "drawer_menu_collection" to "收藏",
            "drawer_menu_browsing_history" to "浏览记录",
            "drawer_menu_novel_marker" to "书签",
            "drawer_menu_connections" to "「我的」分组标题",
            "drawer_menu_following" to "关注",
            "drawer_menu_follower" to "粉丝",
            "drawer_menu_mypixiv" to "好P友",
            "drawer_menu_others" to "「其他」分组标题",
            "drawer_menu_about" to "关于本APP",
            "drawer_menu_support" to "帮助与反馈",
            "drawer_menu_setting" to "设置",
            "drawer_menu_mute_setting" to "屏蔽设定"
        )
    }

    private val navViews = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val navRefs = ConcurrentHashMap<Int, WeakReference<View>>()
    private val navMenus = ConcurrentHashMap<Int, WeakReference<Menu>>()
    private var installed = false

    override fun hook(host: Host) {
        if (installed) return
        installed = true
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
            // App 会动态 add 下半部分菜单项（关注 / 粉丝 / 设置等），监听菜单结构变化重新应用。
            val menuClass = ReflectUtil.loadClass(host.classLoader, "androidx.appcompat.view.menu.MenuBuilder")
            val onChange = menuClass?.let {
                ReflectUtil.findMethods(it, "onItemsChanged").firstOrNull { m -> m.parameterCount == 1 }
            }
            if (onChange != null) {
                XposedBridge.hookMethod(onChange, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val menu = param.thisObject ?: return
                        if (!isTrackedMenu(menu)) return
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            applyToTrackedMenu(menu)
                        }, 120L)
                    }
                })
                ModuleLog.i(displayName, "navigation view hooks installed (with menu change listener)")
                return
            }
            ModuleLog.i(displayName, "navigation view hooks installed")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "hook NavigationView failed", t)
        }
    }

    override fun onConfigChanged(host: Host) {
        super.onConfigChanged(host)
        ModuleLog.i(displayName, "侧边栏精简已${if (isEnabled(ConfigStoreRegistry.store)) "开启" else "关闭"}")
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
        navRefs[key] = WeakReference(view)
        navViews.add(key)
        if (navRefs.size > 8) {
            navRefs.entries.removeIf { it.value.get() == null }
        }
    }

    private fun isTrackedMenu(menu: Any): Boolean {
        for (key in navMenus.keys) {
            if (navMenus[key]?.get() === menu) return true
        }
        return false
    }

    private fun applyToTrackedMenu(menu: Any) {
        for (key in navViews.toList()) {
            val view = navRefs[key]?.get() ?: continue
            val tracked = navMenus[key]?.get() ?: continue
            if (tracked === menu) {
                applyToView(view)
            }
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
        navMenus[System.identityHashCode(view)] = WeakReference(menu)
        val slim = slimEnabled()
        val hidden = hiddenItems()

        ensureToggleItem(menu, slim)

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
            val entryName = try {
                view.resources.getResourceEntryName(item.itemId)
            } catch (_: Throwable) {
                null
            } ?: continue
            // 只管理侧边栏自身的菜单项，App 默认不可见的其他项不得强改可见性。
            if (!entryName.startsWith("drawer_menu_")) continue
            val targetVisible = !(slim && entryName in hidden)
            if (item.isVisible != targetVisible) {
                item.isVisible = targetVisible
                changed++
            }
            val subMenu = item.subMenu
            if (subMenu != null) applyToMenu(view, subMenu, slim, hidden, onToggled)
        }
        if (changed > 0) onToggled(changed)
    }

    /** 注入「精简侧边栏」按钮（幂等，点击切换精简模式；order 放大保证排在菜单末尾）。 */
    private fun ensureToggleItem(menu: Menu, slim: Boolean) {
        val existing = menu.findItem(TOGGLE_ITEM_ID)
        val title = if (slim) "恢复全部菜单" else "精简侧边栏"
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
                if (newValue) "侧边栏已精简；重启 Pixiv 后完全生效，隐藏项可在 PixivPlus 设置中调整"
                else "已恢复全部菜单；重启 Pixiv 后完全生效",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {
        }
        ModuleLog.i(displayName, "drawer slim toggled -> $newValue")
    }
}
