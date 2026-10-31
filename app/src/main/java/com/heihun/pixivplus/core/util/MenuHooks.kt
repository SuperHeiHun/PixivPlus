package com.heihun.pixivplus.core.util

import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 详情页 / 阅读页 MaterialToolbar 菜单的通用工具。
 *
 * - [findToolbars] + [readMenu]：从视图树中找到暴露 `Menu` 的工具栏并读取菜单；
 * - [installClickHook]：hook `setOnMenuItemClickListener`，拦截菜单项点击。
 *   多个功能可各自安装，按 itemId 分工，handler 返回 true 表示已消费点击。
 */
object MenuHooks {

    private const val TOOLBAR_CLASS = "com.google.android.material.appbar.MaterialToolbar"
    private val NO_ACCESSOR = Any()
    private val accessorCache = ConcurrentHashMap<Class<*>, Any>()

    fun findToolbars(root: View): List<View> {
        val result = ArrayList<View>()
        collect(root, result)
        return result
    }

    private fun collect(root: View, out: MutableList<View>) {
        if (accessor(root.javaClass) != null) {
            out.add(root)
            return
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index) ?: continue
                collect(child, out)
            }
        }
    }

    fun readMenu(toolbar: View): Menu? {
        val method = accessor(toolbar.javaClass) ?: return null
        return ReflectUtil.invoke(toolbar, method) as? Menu
    }

    private fun accessor(clazz: Class<*>): Method? {
        return when (val cached = accessorCache.getOrPut(clazz) {
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

    fun installClickHook(
        host: Host,
        hookedMethods: MutableSet<Method>,
        displayName: String,
        handler: (MenuItem) -> Boolean
    ) {
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
                    hookListener(listener.javaClass, hookedMethods, displayName, handler)
                }
            })
            ModuleLog.i(displayName, "menu click hook installed")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "hook toolbar listener failed", t)
        }
    }

    private fun hookListener(
        listenerClass: Class<*>,
        hookedMethods: MutableSet<Method>,
        displayName: String,
        handler: (MenuItem) -> Boolean
    ) {
        val method = listenerClass.methods.firstOrNull { candidate ->
            candidate.parameterCount == 1 && candidate.parameterTypes[0] == MenuItem::class.java &&
                (candidate.returnType == Boolean::class.javaPrimitiveType || candidate.returnType == Void.TYPE)
        } ?: return
        if (!hookedMethods.add(method)) return
        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val item = param.args?.firstOrNull() as? MenuItem ?: return
                    if (handler(item)) {
                        param.result = true
                    }
                }
            })
            ModuleLog.i(displayName, "menu click hook installed on ${listenerClass.name}.${method.name}")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "hook menu click failed", t)
        }
    }
}
