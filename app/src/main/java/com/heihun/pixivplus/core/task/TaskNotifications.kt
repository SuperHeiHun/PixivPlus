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
import com.heihun.pixivplus.core.i18n.CommonText as C
import com.heihun.pixivplus.core.i18n.TaskText as T
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import java.util.concurrent.ConcurrentHashMap

/** 通知以宿主 Pixiv 身份显示；独立低优先级频道，不影响宿主其他通知。 */
object TaskNotifications {
    private const val CHANNEL = "pixivplus_tasks_silent"
    private const val ACTION = "jp.pxv.android.pixivplus.CANCEL_TASK"
    private const val CANCEL_SCHEME = "pixivplus-task"
    private val shown = ConcurrentHashMap.newKeySet<String>()
    private var initialized = false
    private var channelName: String? = null
    // API 33+ 显式 NOT_EXPORTED；旧系统没有该标志，不能用需要宿主声明权限的 ContextCompat 包装。
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Synchronized private fun initialize(context: Context, manager: NotificationManager) {
        val title = T.CHANNEL()
        if (channelName != title) {
            val channel = manager.getNotificationChannel(CHANNEL) ?: NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            }
            channel.name = title
            manager.createNotificationChannel(channel)
            channelName = title
        }
        if (initialized) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION) intent.getStringExtra("task_id")?.let { TaskManager.cancel(it) }
            }
        }
        // PendingIntent 使用任务 URI 区分身份；接收器必须同时匹配 scheme，否则带 data 的广播不会命中。
        val filter = IntentFilter(ACTION).apply { addDataScheme(CANCEL_SCHEME) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") context.registerReceiver(receiver, filter) }
        initialized = true
    }
    // 运行于宿主进程，使用宿主权限而非模块 Manifest；发送前检查宿主 POST_NOTIFICATIONS。
    @android.annotation.SuppressLint("NotificationPermission")
    fun show(record: TaskRecord) {
        try {
            val context = Host.context() ?: return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!ConfigStoreRegistry.store.get(ConfigKeys.TASK_NOTIFICATIONS_ENABLED)) {
                manager.cancel(record.id, 1); shown.remove(record.id); return
            }
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            initialize(context, manager)
            val message = record.displayMessage()
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("PixivPlus · ${record.title}")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(TaskNavigation.contentIntent(context))
                .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(!record.state.terminal).setAutoCancel(record.state.terminal)
            if (!record.state.terminal) {
                notification.setProgress(record.total, record.done, record.total <= 0)
                val intent = Intent(ACTION).setPackage(Host.PACKAGE).putExtra("task_id", record.id)
                    .setData(android.net.Uri.parse("$CANCEL_SCHEME://${record.id}"))
                val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                notification.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, C.CANCEL(), pending).build())
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
