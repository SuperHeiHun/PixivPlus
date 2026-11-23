package com.heihun.pixivplus.core.util

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Pixiv 6.192.1 gx8.onEvent 的作品操作弹窗；只修改该事件同步创建的列表。 */
object WorkPopupHooks {
    private data class Action(val label: () -> String, val enabled: (Any) -> Boolean, val click: (Activity, Any) -> Unit)
    private data class Target(val activity: Activity, val entity: Any)
    private val actions = CopyOnWriteArrayList<Action>()
    private val target = ThreadLocal<Target?>()
    private val installed = AtomicBoolean(false)

    fun register(host: Host, label: () -> String, enabled: (Any) -> Boolean, click: (Activity, Any) -> Unit) {
        actions.add(Action(label, enabled, click))
        if (!installed.compareAndSet(false, true)) return
        val events = ReflectUtil.loadClass(host.classLoader, "gx8")
            ?: ReflectUtil.loadClass(host.classLoader, "defpackage.gx8")
        val builder = ReflectUtil.loadClass(host.classLoader, "nf")
            ?: ReflectUtil.loadClass(host.classLoader, "defpackage.nf")
        val eventMethod = events?.let { ReflectUtil.findMethods(it, "onEvent", 1).firstOrNull() }
        val createMethod = builder?.let { ReflectUtil.findMethod(it, "create") }
        if (eventMethod == null || createMethod == null) {
            installed.set(false)
            ModuleLog.w("WorkPopupHooks", "work menu anchors unavailable")
            return
        }
        XposedBridge.hookMethod(eventMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val entity = ReflectUtil.readField(param.args.firstOrNull(), "a") ?: return
                val context = ReflectUtil.readField(param.thisObject, "a") as? Context ?: return
                val activity = DetailPagerResolver.activityOf(context) ?: return
                target.set(Target(activity, entity))
            }
            override fun afterHookedMethod(param: MethodHookParam) { target.remove() }
        })
        XposedBridge.hookMethod(createMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val current = target.get() ?: return
                try {
                    val params = ReflectUtil.readField(param.thisObject, "a") ?: return
                    val labels = ReflectUtil.readField(params, "p") as? Array<*> ?: return
                    val original = ReflectUtil.readField(params, "r") as? DialogInterface.OnClickListener ?: return
                    val extra = actions.filter { it.enabled(current.entity) }
                    if (extra.isEmpty()) return
                    val merged = labels.map { it as CharSequence }.toMutableList()
                    merged.addAll(extra.map { it.label() })
                    val itemsField = ReflectUtil.findField(params.javaClass, "p") ?: return
                    val clickField = ReflectUtil.findField(params.javaClass, "r") ?: return
                    itemsField.isAccessible = true
                    clickField.isAccessible = true
                    itemsField.set(params, merged.toTypedArray())
                    clickField.set(params, DialogInterface.OnClickListener { dialog, which ->
                        if (which < labels.size) original.onClick(dialog, which)
                        else extra.getOrNull(which - labels.size)?.let { action ->
                            dialog.dismiss()
                            current.activity.window.decorView.post {
                                if (!current.activity.isFinishing && !current.activity.isDestroyed) {
                                    action.click(current.activity, current.entity)
                                }
                            }
                        }
                    })
                } catch (t: Throwable) {
                    ModuleLog.w("WorkPopupHooks", "work menu injection failed", t)
                }
            }
        })
    }
}
