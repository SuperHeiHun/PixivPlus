package com.heihun.pixivplus.core.util

import android.view.View
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.CopyOnWriteArrayList

object CurrentWorkHook {

    const val DETAIL_TAGS_VIEW_CLASS =
        "jp.pxv.android.feature.component.androidview.DetailCaptionAndTagsView"

    const val DETAIL_BASE_ACTIVITY_CLASS = "defpackage.xd0"

    private val DETAIL_BASE_ACTIVITY_CLASSES = listOf("xd0", DETAIL_BASE_ACTIVITY_CLASS)

    private val listeners = CopyOnWriteArrayList<(Any, Long, String) -> Unit>()

    private val hooked = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun install(
        host: Host,
        displayName: String,
        listener: (entity: Any, id: Long, type: String) -> Unit
    ): Int {
        listeners.add(listener)
        return hookCaptionViews(host, displayName) + hookPageSelection(host, displayName)
    }

    private fun hookCaptionViews(host: Host, displayName: String): Int {
        val clazz = ReflectUtil.loadClass(host.classLoader, DETAIL_TAGS_VIEW_CLASS) ?: run {
            ModuleLog.w(displayName, "caption view class not found")
            return 0
        }
        var count = 0
        for ((methodName, defaultType) in listOf("setIllust" to "ILLUST", "setNovel" to "NOVEL")) {
            val candidates = ReflectUtil.findMethods(clazz, methodName)
                .filter { it.parameterCount == 1 }
            val expected = if (defaultType == "ILLUST") "PixivIllust" else "PixivNovel"
            val method = candidates.firstOrNull { it.parameterTypes[0].name.endsWith(expected) }
                ?: candidates.firstOrNull()
                ?: continue
            val key = "${method.name}@${defaultType}"
            if (hooked.putIfAbsent(key, true) != null) continue
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val entity = param.args?.firstOrNull() ?: return
                        val id = idOf(entity) ?: return
                        if (!isVisibleWork(param.thisObject, id)) {
                            ModuleLog.d(displayName, "skip adjacent page id=$id")
                            return
                        }
                        notifyListeners(displayName, entity, id, defaultType)
                    }
                })
                count++
            } catch (t: Throwable) {
                ModuleLog.w(displayName, "current work hook failed: $methodName", t)
            }
        }
        return count
    }

    private fun hookPageSelection(host: Host, displayName: String): Int {
        val clazz = DETAIL_BASE_ACTIVITY_CLASSES
            .asSequence()
            .mapNotNull { ReflectUtil.loadClass(host.classLoader, it) }
            .firstOrNull()
        if (clazz == null) {
            ModuleLog.w(displayName, "detail base activity class not found")
            return 0
        }
        val method = ReflectUtil.findMethod(
            clazz,
            "onPageSelected",
            Int::class.javaPrimitiveType ?: return 0
        ) ?: return 0
        if (hooked.putIfAbsent("onPageSelected@detail", true) != null) return 0
        return try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject ?: return
                    val index = param.args?.firstOrNull() as? Int ?: return
                    val entity = itemAt(activity, index) ?: return
                    val id = idOf(entity) ?: return
                    notifyListeners(displayName, entity, id, "ILLUST")
                }
            })
            ModuleLog.i(displayName, "detail page selection hook installed")
            1
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "detail page selection hook failed", t)
            0
        }
    }

    private fun isVisibleWork(owner: Any?, id: Long): Boolean {
        val activity = (owner as? View)?.let { DetailPagerResolver.activityOf(it.context) }
            ?: DetailActivityTracker.get()
        if (activity == null) return true
        return DetailPagerResolver.isCurrentWork(activity, id)
    }

    private fun itemAt(activity: Any, index: Int): Any? {
        val adapter = ReflectUtil.readField(activity, "D") ?: return null
        val method = ReflectUtil.findMethod(
            adapter.javaClass,
            "d",
            Int::class.javaPrimitiveType ?: return null
        ) ?: return null
        return ReflectUtil.invoke(adapter, method, index)
    }

    private fun idOf(entity: Any): Long? {
        val idMethod = ReflectUtil.findMethod(entity.javaClass, "getId") ?: return null
        return ReflectUtil.invoke(entity, idMethod) as? Long
    }

    private fun notifyListeners(displayName: String, entity: Any, id: Long, type: String) {
        for (callback in listeners) {
            try {
                callback(entity, id, type)
            } catch (t: Throwable) {
                ModuleLog.w(displayName, "current work listener failed", t)
            }
        }
    }
}
