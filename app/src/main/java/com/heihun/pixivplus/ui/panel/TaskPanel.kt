package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.CommonText as C
import com.heihun.pixivplus.core.i18n.I18n
import com.heihun.pixivplus.core.i18n.PanelText as P
import com.heihun.pixivplus.core.i18n.TaskText as T
import com.heihun.pixivplus.core.task.TaskManager
import com.heihun.pixivplus.core.task.TaskRecord
import com.heihun.pixivplus.core.task.TaskState
import com.heihun.pixivplus.core.media.DownloadPlan
import com.heihun.pixivplus.core.media.DownloadJobs
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.ui.UiKit

object TaskPanel {
    fun build(activity: Activity, body: LinearLayout, openCache: () -> Unit) {
        val store = ConfigStoreRegistry.store
        val settings = UiKit.card(activity)
        settings.addView(UiKit.switchRow(activity, T.NOTIFICATIONS(), T.NOTIFICATIONS_HINT(), store.get(ConfigKeys.TASK_NOTIFICATIONS_ENABLED)) {
            store.put(ConfigKeys.TASK_NOTIFICATIONS_ENABLED, it)
            TaskManager.refreshNotifications()
        })
        settings.addView(UiKit.switchRow(activity, T.SIDEBAR(), T.SIDEBAR_HINT(), store.get(ConfigKeys.TASK_LIST_ENABLED)) {
            store.put(ConfigKeys.TASK_LIST_ENABLED, it)
        })
        settings.addView(UiKit.switchRow(activity, T.RECORD_DOWNLOADS(), T.RECORD_DOWNLOADS_HINT(), store.get(ConfigKeys.TASK_HISTORY_ENABLED)) {
            store.put(ConfigKeys.TASK_HISTORY_ENABLED, it)
        })
        settings.addView(UiKit.paragraph(activity, T.TASK_HELP()))
        settings.addView(UiKit.filledButtonRow(activity, T.CLEAR_DOWNLOADS()) {
            ConfirmDialog.show(activity, T.CLEAR_DOWNLOADS(), T.CLEAR_DOWNLOADS_NOTE(), C.CLEAR()) { TaskManager.clearHistory() }
        })
        settings.addView(UiKit.filledButtonRow(activity, P.CACHE_CLEAN(), openCache))
        body.addView(settings)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        var previousActive: List<TaskRecord>? = null
        var previousHistory: List<TaskRecord>? = null
        var previousEnabled: Boolean? = null
        var languageRevision = 0
        var renderedLanguageRevision = -1
        val onLanguageChanged: () -> Unit = { languageRevision++ }
        com.heihun.pixivplus.core.i18n.I18n.addListener(onLanguageChanged)
        val refresh = object : Runnable {
            override fun run() {
                if (!body.isAttachedToWindow) return
                // 不在按下/抬起之间重建按钮，否则进度刷新可能吞掉取消点击。
                if (hasPressedView(list)) { body.postDelayed(this, 250); return }
                val active = TaskManager.activeTasks()
                val history = TaskManager.history()
                val enabled = store.get(ConfigKeys.TASK_HISTORY_ENABLED)
                if (active != previousActive || history != previousHistory || enabled != previousEnabled || languageRevision != renderedLanguageRevision) {
                    previousActive = active; previousHistory = history; previousEnabled = enabled
                    renderedLanguageRevision = languageRevision
                    list.removeAllViews()
                    list.addView(UiKit.sectionTitle(activity, T.ACTIVE_TASKS(active.size)))
                    if (active.isEmpty()) list.addView(UiKit.paragraph(activity, T.NO_ACTIVE_TASKS()))
                    active.forEach { record -> addRecord(activity, list, record, true) }
                    list.addView(UiKit.sectionTitle(activity, T.DOWNLOAD_HISTORY()))
                    if (!enabled) list.addView(UiKit.paragraph(activity, T.DOWNLOAD_HISTORY_OFF()))
                    else if (history.isEmpty()) list.addView(UiKit.paragraph(activity, T.DOWNLOAD_HISTORY_EMPTY()))
                    else history.forEach { record -> addRecord(activity, list, record, false) }
                }
                body.postDelayed(this, 1000)
            }
        }
        body.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { body.post(refresh) }
            override fun onViewDetachedFromWindow(view: View) {
                body.removeCallbacks(refresh)
                com.heihun.pixivplus.core.i18n.I18n.removeListener(onLanguageChanged)
            }
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
        card.addView(UiKit.paragraph(activity, "${I18n.dateTime(record.createdAt)} · ${record.displayMessage()}"))
        if (record.state == TaskState.SUCCEEDED && record.files.isNotEmpty()) {
            card.addView(UiKit.textButton(activity, T.OPEN_FILE()).apply {
                setOnClickListener { com.heihun.pixivplus.core.task.TaskFileOpener.show(activity, record.files) }
            })
        } else if (record.state == TaskState.SUCCEEDED && record.kind == com.heihun.pixivplus.core.task.TaskKind.DOWNLOAD) {
            card.addView(UiKit.paragraph(activity, T.NO_RECORDED_FILES()))
        }
        if (record.workId > 0) {
            val novel = record.workType == "NOVEL"
            fun openWork() {
                val deep = if (novel) "pixiv://novels/${record.workId}" else "pixiv://illusts/${record.workId}"
                val web = if (novel) "https://www.pixiv.net/novel/show.php?id=${record.workId}" else "https://www.pixiv.net/artworks/${record.workId}"
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deep)).setPackage("jp.pxv.android")) }
                catch (_: Exception) {
                    try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web))) }
                    catch (_: Exception) { android.widget.Toast.makeText(activity, T.OPEN_WORK_FAILED(), android.widget.Toast.LENGTH_SHORT).show() }
                }
            }
            card.setOnClickListener { openWork() }
            card.addView(UiKit.textButton(activity, if (novel) T.OPEN_NOVEL() else T.OPEN_ARTWORK()).apply { setOnClickListener { openWork() } })
        }
        if (active) card.addView(UiKit.textButton(activity, if (record.state == TaskState.CANCELLING) T.CANCELLING() else T.CANCEL_TASK()).apply {
            isEnabled = record.state != TaskState.CANCELLING
            setOnClickListener { TaskManager.cancel(record.id) }
        })
        else record.downloadPlan?.let { text ->
            val plan = try { DownloadPlan.decode(text) } catch (_: Exception) { null }
            if (plan != null) {
                if (record.state != TaskState.SUCCEEDED) card.addView(UiKit.textButton(activity, T.RESUME_DOWNLOAD()).apply {
                    setOnClickListener { DownloadJobs.submit(activity, plan) }
                })
                card.addView(UiKit.textButton(activity, T.RESTART_DOWNLOAD()).apply {
                    setOnClickListener { ConfirmDialog.show(activity, T.RESTART_DOWNLOAD(), T.RESTART_NOTE(), T.DOWNLOAD_AGAIN()) {
                        DownloadJobs.submit(activity, plan, restart = true)
                    } }
                })
            }
        }
        list.addView(card)
    }
}
