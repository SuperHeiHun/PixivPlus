package com.heihun.pixivplus.core.task

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.ui.panel.SettingsPanelBuilder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** 通知直接返回最近的宿主 Activity，在其上打开 Dialog，不把 MainActivity 移到栈顶。 */
object TaskNavigation {
    private const val MAIN_ACTIVITY = "jp.pxv.android.MainActivity"
    private const val OPEN_TASKS = "pixivplus_open_tasks"
    private val pending = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val activities = WeakHashMap<Activity, Long>()
    private var sequence = 0L
    @Volatile private var origin = WeakReference<Activity>(null)
    private var resumed = WeakReference<Activity>(null)
    private var installed = false

    fun contentIntent(context: Context): PendingIntent {
        val activity = origin.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        val intent = if (activity != null) {
            // 保留小说 ID、深链等原始参数；通知只唤回同一页面，不创建阅读页的空白副本。
            Intent(activity.intent).setClassName(Host.PACKAGE, activity.javaClass.name)
        } else {
            Intent(Intent.ACTION_MAIN).setClassName(Host.PACKAGE, MAIN_ACTIVITY)
        }
        intent.flags = TaskEntryPolicy.ACTIVITY_FLAGS
        intent.putExtra(OPEN_TASKS, true)
        return PendingIntent.getActivity(context, 0x60011002, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun install(host: Host) {
        if (installed) return
        val application = host.application ?: return
        try {
            // 在宿主处理 Intent 前消费模块标记。已有页面无需再次处理小说深链/刷新正文。
            XposedBridge.hookAllMethods(Instrumentation::class.java, "callActivityOnNewIntent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.args.filterIsInstance<Activity>().firstOrNull() ?: return
                    val intent = param.args.filterIsInstance<Intent>().firstOrNull() ?: return
                    if (!consume(activity, intent)) return
                    param.result = null
                    activity.window?.decorView?.post {
                        if (resumed.get() === activity) openPending(activity)
                    }
                }
            })
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) { consume(activity, activity.intent) }
                override fun onActivityResumed(activity: Activity) {
                    if (activity.packageName != Host.PACKAGE) return
                    val changed = origin.get() !== activity
                    activities[activity] = ++sequence
                    origin = WeakReference(activity)
                    resumed = WeakReference(activity)
                    consume(activity, activity.intent)
                    activity.window?.decorView?.post {
                        if (resumed.get() !== activity || activity.isFinishing || activity.isDestroyed) return@post
                        openPending(activity)
                        // 任务可能早已完成；同步仍显示的通知，避免旧通知继续指向上一页。
                        if (changed) TaskNotifications.refreshContentIntents()
                    }
                }
                override fun onActivityPaused(activity: Activity) {
                    if (resumed.get() === activity) resumed.clear()
                }
                override fun onActivityDestroyed(activity: Activity) {
                    pending.remove(activity)
                    activities.remove(activity)
                    if (origin.get() === activity) {
                        origin = WeakReference(activities.entries.filterNot { it.key.isFinishing || it.key.isDestroyed }
                            .maxByOrNull { it.value }?.key)
                        TaskNotifications.refreshContentIntents()
                    }
                }
                override fun onActivityStarted(activity: Activity) { }
                override fun onActivityStopped(activity: Activity) { }
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) { }
            })
            installed = true
        } catch (t: Exception) { ModuleLog.w("TaskNavigation", "notification entry hook failed", t) }
    }

    private fun consume(activity: Activity, intent: Intent?): Boolean {
        if (activity.packageName != Host.PACKAGE || intent?.getBooleanExtra(OPEN_TASKS, false) != true) return false
        intent.removeExtra(OPEN_TASKS)
        pending.add(activity)
        return true
    }

    private fun openPending(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || !pending.remove(activity)) return
        SettingsPanelBuilder.show(activity, SettingsPanelBuilder.Screen.TASKS)
    }
}
