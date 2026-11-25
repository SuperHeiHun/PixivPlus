package com.heihun.pixivplus.feature.slim

import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.DrawerText as D
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

class BottomSlimFeature : ConfigurableFeature("bottom_slim", "底栏精简", ConfigKeys.BOTTOM_SLIM_ENABLED) {
    companion object {
        internal val labels = linkedMapOf("home" to D.HOME, "search" to D.SEARCH,
            "new_works" to D.NEW_WORKS, "notifications" to D.NOTIFICATIONS, "mypage" to D.MY_PAGE)
    }

    private val views = WeakHashMap<View, Boolean>()
    private val items = WeakHashMap<MenuItem, WeakReference<View>>()
    private val hooked = HashSet<Method>()
    private val applying = ThreadLocal<Boolean>()
    private val pending = WeakHashMap<View, Int>()

    override fun hook(host: Host) {
        val clazz = ReflectUtil.loadClass(host.classLoader,
            "com.google.android.material.bottomnavigation.BottomNavigationView") ?: return
        val measure = ReflectUtil.findMethod(clazz, "onMeasure", Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!) ?: return
        XposedBridge.hookMethod(measure, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (name(view, view.id) != "bottom_navigation") return
                views[view] = true
                apply(view)
            }
        })
        ModuleLog.i(displayName, "main bottom navigation measure/visibility guards installed")
    }

    override fun onConfigChanged(host: Host) {
        for (view in views.keys.toList()) view.post { apply(view) }
    }

    private fun menu(view: View): Menu? = ReflectUtil.invoke(view,
        ReflectUtil.findMethod(view.javaClass, "getMenu")) as? Menu

    private fun name(view: View, id: Int): String? = try {
        view.resources.getResourceEntryName(id)
    } catch (_: Exception) { null }

    private fun hidden(view: View, menu: Menu): Set<String> {
        if (!isEnabled(ConfigStoreRegistry.store)) return emptySet()
        val available = (0 until menu.size()).mapNotNull { index ->
            menu.getItem(index).takeIf { it.isEnabled }?.let { name(view, it.itemId) }
        }
        return BottomSlimPolicy.effectiveHidden(BottomSlimPolicy.parse(
            ConfigStoreRegistry.store.get(ConfigKeys.BOTTOM_SLIM_ITEMS)), available)
    }

    private fun track(view: View, item: MenuItem) {
        if (items[item]?.get() === view) return
        items[item] = WeakReference(view)
        val setter = ReflectUtil.findMethod(item.javaClass, "setVisible", Boolean::class.javaPrimitiveType!!) ?: return
        if (!hooked.add(setter)) return
        try {
            XposedBridge.hookMethod(setter, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] != true || applying.get() == true) return
                    val target = param.thisObject as? MenuItem ?: return
                    val owner = items[target]?.get() ?: return
                    val menu = menu(owner) ?: return
                    if (name(owner, target.itemId) in hidden(owner, menu)) param.args[0] = false
                }
            })
        } catch (t: Throwable) {
            hooked.remove(setter)
            ModuleLog.w(displayName, "bottom item visibility hook failed", t)
        }
    }

    private fun apply(view: View) {
        if (applying.get() == true) return
        val currentMenu = menu(view) ?: return
        applying.set(true)
        try {
            val hiddenNames = hidden(view, currentMenu)
            for (index in 0 until currentMenu.size()) {
                val item = currentMenu.getItem(index)
                val key = name(view, item.itemId) ?: continue
                if (key !in BottomSlimPolicy.entries) continue
                track(view, item)
                val visible = key !in hiddenNames
                if (item.isVisible != visible) item.isVisible = visible
            }
            val selected = ReflectUtil.invoke(view, ReflectUtil.findMethod(view.javaClass, "getSelectedItemId")) as? Int
            if (selected != null && name(view, selected) in hiddenNames && pending[view] != selected) {
                val next = (0 until currentMenu.size()).map { currentMenu.getItem(it) }.firstOrNull { it.isVisible && it.isEnabled }
                if (next != null) {
                    pending[view] = selected
                    val targetId = next.itemId
                    view.post {
                        pending.remove(view)
                        val current = ReflectUtil.invoke(view, ReflectUtil.findMethod(view.javaClass, "getSelectedItemId"))
                        val latest = menu(view)
                        if (current == selected && latest != null && name(view, selected) in hidden(view, latest) &&
                            latest.findItem(targetId)?.let { it.isVisible && it.isEnabled } == true) {
                            ReflectUtil.invoke(view, ReflectUtil.findMethod(view.javaClass, "setSelectedItemId",
                                Int::class.javaPrimitiveType!!), targetId)
                        }
                    }
                }
            }
        } finally { applying.remove() }
    }
}
