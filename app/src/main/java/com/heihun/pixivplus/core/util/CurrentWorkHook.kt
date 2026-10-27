package com.heihun.pixivplus.core.util

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.CopyOnWriteArrayList

object CurrentWorkHook {

    const val DETAIL_TAGS_VIEW_CLASS =
        "jp.pxv.android.feature.component.androidview.DetailCaptionAndTagsView"

    private val listeners = CopyOnWriteArrayList<(Any, Long, String) -> Unit>()

    private val hooked = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun install(
        host: Host,
        displayName: String,
        listener: (entity: Any, id: Long, type: String) -> Unit
    ): Int {
        listeners.add(listener)
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
                        val idMethod = ReflectUtil.findMethod(entity.javaClass, "getId") ?: return
                        val id = ReflectUtil.invoke(entity, idMethod) as? Long ?: return
                        for (callback in listeners) {
                            try {
                                callback(entity, id, defaultType)
                            } catch (t: Throwable) {
                                ModuleLog.w(displayName, "current work listener failed", t)
                            }
                        }
                    }
                })
                count++
            } catch (t: Throwable) {
                ModuleLog.w(displayName, "current work hook failed: $methodName", t)
            }
        }
        return count
    }
}
