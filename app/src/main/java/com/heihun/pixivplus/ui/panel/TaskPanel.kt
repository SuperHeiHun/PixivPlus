package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.task.TaskManager
import com.heihun.pixivplus.core.task.TaskRecord
import com.heihun.pixivplus.core.task.TaskState
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.ui.UiKit

object TaskPanel {
    fun build(activity: Activity, body: LinearLayout) {
        val store = ConfigStoreRegistry.store
        val settings = UiKit.card(activity)
        settings.addView(UiKit.switchRow(activity, "任务通知", "默认静默，通知中可取消下载和翻译", store.get(ConfigKeys.TASK_NOTIFICATIONS_ENABLED)) {
            store.put(ConfigKeys.TASK_NOTIFICATIONS_ENABLED, it)
            TaskManager.refreshNotifications()
        })
        settings.addView(UiKit.switchRow(activity, "侧边栏任务列表入口", "关闭仅隐藏入口，不停止任务；也可在侧边栏精简中隐藏", store.get(ConfigKeys.TASK_LIST_ENABLED)) {
            store.put(ConfigKeys.TASK_LIST_ENABLED, it)
        })
        settings.addView(UiKit.switchRow(activity, "记录下载历史", "仅本机保留最近 100 条，关闭不会删除已有文件", store.get(ConfigKeys.TASK_HISTORY_ENABLED)) {
            store.put(ConfigKeys.TASK_HISTORY_ENABLED, it)
        })
        settings.addView(UiKit.paragraph(activity, "通知以 Pixiv 身份显示；Android 13 及以上需允许 Pixiv 发送通知。关闭通知后仍可在本页取消。\n退出或重启 Pixiv 会中断正在进行的任务。"))
        settings.addView(UiKit.filledButtonRow(activity, "清空下载历史") {
            ConfirmDialog.show(activity, "清空下载历史", "仅清空记录，不删除已保存文件。", "清空") { TaskManager.clearHistory() }
        })
        body.addView(settings)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        var previousActive: List<TaskRecord>? = null
        var previousHistory: List<TaskRecord>? = null
        var previousEnabled: Boolean? = null
        val refresh = object : Runnable {
            override fun run() {
                if (!body.isAttachedToWindow) return
                // 不在按下/抬起之间重建按钮，否则进度刷新可能吞掉取消点击。
                if (hasPressedView(list)) { body.postDelayed(this, 250); return }
                val active = TaskManager.activeTasks()
                val history = TaskManager.history()
                val enabled = store.get(ConfigKeys.TASK_HISTORY_ENABLED)
                if (active != previousActive || history != previousHistory || enabled != previousEnabled) {
                    previousActive = active; previousHistory = history; previousEnabled = enabled
                    list.removeAllViews()
                    list.addView(UiKit.sectionTitle(activity, "下载与翻译任务（${active.size}）"))
                    if (active.isEmpty()) list.addView(UiKit.paragraph(activity, "暂无正在进行的任务"))
                    active.forEach { record -> addRecord(activity, list, record, true) }
                    list.addView(UiKit.sectionTitle(activity, "下载历史"))
                    if (!enabled) list.addView(UiKit.paragraph(activity, "下载历史记录已关闭"))
                    else if (history.isEmpty()) list.addView(UiKit.paragraph(activity, "暂无下载历史"))
                    else history.forEach { record -> addRecord(activity, list, record, false) }
                }
                body.postDelayed(this, 1000)
            }
        }
        body.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { body.post(refresh) }
            override fun onViewDetachedFromWindow(view: View) { body.removeCallbacks(refresh) }
        })
        if (body.isAttachedToWindow) body.post(refresh)
    }
    private fun hasPressedView(view: View): Boolean {
        if (view.isPressed) return true
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            if (hasPressedView(view.getChildAt(index))) return true
        }
        return false
    }
    private fun addRecord(activity: Activity, list: LinearLayout, record: TaskRecord, active: Boolean) {
        val card = UiKit.card(activity)
        card.addView(UiKit.infoRow(activity, record.title, record.state.label))
        val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(record.createdAt))
        card.addView(UiKit.paragraph(activity, "$time · ${record.message}"))
        if (active) card.addView(UiKit.textButton(activity, if (record.state == TaskState.CANCELLING) "取消中…" else "取消任务").apply {
            isEnabled = record.state != TaskState.CANCELLING
            setOnClickListener { TaskManager.cancel(record.id) }
        })
        list.addView(card)
    }
}
