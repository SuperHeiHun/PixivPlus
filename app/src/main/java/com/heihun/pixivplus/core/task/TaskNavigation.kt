package com.heihun.pixivplus.core.task

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.ui.panel.SettingsPanelBuilder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap

/** 通知直接启动宿主 Activity，避免 Android 12+ 禁止的通知广播/服务跳转 trampoline。 */
object TaskNavigation {
    private const val MAIN_ACTIVITY = "jp.pxv.android.MainActivity"
    private const val OPEN_TASKS = "pixivplus_open_tasks"
    private val pending = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var installed = false

    fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN).setClassName(Host.PACKAGE, MAIN_ACTIVITY)
            .putExtra(OPEN_TASKS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        // 所有通知打开同一个任务列表；不使用 CLEAR_TOP，以免销毁现有详情页栈。
        return PendingIntent.getActivity(context, 0x60011002, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun install(host: Host) {
        if (installed) return
        val clazz = ReflectUtil.loadClass(host.classLoader, MAIN_ACTIVITY) ?: return
        val create = ReflectUtil.findMethod(clazz, "onCreate", android.os.Bundle::class.java)
        val resume = ReflectUtil.findMethod(clazz, "onResume")
        val newIntent = ReflectUtil.findMethod(clazz, "onNewIntent", Intent::class.java)
        if (create == null || resume == null || newIntent == null) {
            ModuleLog.w("TaskNavigation", "main activity notification entry unavailable")
            return
        }
        try {
            XposedBridge.hookMethod(create, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    consume(activity, activity.intent)
                }
            })
            XposedBridge.hookMethod(newIntent, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    consume(activity, param.args.firstOrNull() as? Intent)
                }
            })
            XposedBridge.hookMethod(resume, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name != MAIN_ACTIVITY || !pending.remove(activity)) return
                    activity.window.decorView.post {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            SettingsPanelBuilder.show(activity, SettingsPanelBuilder.Screen.TASKS)
                        }
                    }
                }
            })
            installed = true
        } catch (t: Exception) { ModuleLog.w("TaskNavigation", "notification entry hook failed", t) }
    }

    private fun consume(activity: Activity, intent: Intent?) {
        if (activity.javaClass.name != MAIN_ACTIVITY || intent?.getBooleanExtra(OPEN_TASKS, false) != true) return
        intent.removeExtra(OPEN_TASKS)
        pending.add(activity)
    }
}
