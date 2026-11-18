package com.heihun.pixivplus.core.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import java.util.concurrent.ConcurrentHashMap

/** 通知以宿主 Pixiv 身份显示；独立低优先级频道，不影响宿主其他通知。 */
object TaskNotifications {
    private const val CHANNEL = "pixivplus_tasks_silent"
    private const val ACTION = "jp.pxv.android.pixivplus.CANCEL_TASK"
    private val shown = ConcurrentHashMap.newKeySet<String>()
    private var initialized = false
    @Synchronized private fun initialize(context: Context, manager: NotificationManager) {
        if (initialized) return
        val channel = NotificationChannel(CHANNEL, "PixivPlus 下载与翻译（静默）", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION) intent.getStringExtra("task_id")?.let { TaskManager.cancel(it) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") context.registerReceiver(receiver, IntentFilter(ACTION)) }
        initialized = true
    }
    fun show(record: TaskRecord) {
        try {
            val context = Host.context() ?: return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!ConfigStoreRegistry.store.get(ConfigKeys.TASK_NOTIFICATIONS_ENABLED)) {
                manager.cancel(record.id, 1); shown.remove(record.id); return
            }
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            initialize(context, manager)
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("PixivPlus · ${record.title}")
                .setContentText(record.message)
                .setStyle(Notification.BigTextStyle().bigText(record.message))
                .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(!record.state.terminal).setAutoCancel(record.state.terminal)
            if (!record.state.terminal) {
                notification.setProgress(record.total, record.done, record.total <= 0)
                val intent = Intent(ACTION).setPackage(Host.PACKAGE).putExtra("task_id", record.id)
                    .setData(android.net.Uri.parse("pixivplus-task://${record.id}"))
                val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                notification.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "取消", pending).build())
            } else notification.setTimeoutAfter(60000)
            manager.notify(record.id, 1, notification.build())
            shown.add(record.id)
        } catch (t: Exception) { ModuleLog.w("TaskNotifications", "task notification unavailable: ${t.javaClass.simpleName}") }
    }
    fun refresh(records: List<TaskRecord>) {
        val context = Host.context() ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!ConfigStoreRegistry.store.get(ConfigKeys.TASK_NOTIFICATIONS_ENABLED)) {
            shown.forEach { manager.cancel(it, 1) }; shown.clear()
        } else records.forEach(::show)
    }
}
