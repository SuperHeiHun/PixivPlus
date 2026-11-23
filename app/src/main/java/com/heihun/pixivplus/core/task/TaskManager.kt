package com.heihun.pixivplus.core.task

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.TaskText as T
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import java.util.concurrent.ConcurrentHashMap

class TaskHandle internal constructor(initial: TaskRecord) {
    internal val lock = Any()
    @Volatile var record: TaskRecord = initial
        internal set
    val token = CancellationToken()
    internal var lastNotification = 0L
    fun <T> execute(work: () -> T): T {
        TaskManager.running(this)
        try {
            val result = TaskExecution.withContext(TaskExecution.Context(token) { TaskManager.progress(this, it) }, work)
            TaskManager.finish(this, TaskState.SUCCEEDED, TaskState.SUCCEEDED.label)
            token.check()
            return result
        } catch (t: Throwable) {
            val cancelled = token.isCancelled || t is java.util.concurrent.CancellationException || t is InterruptedException
            TaskManager.finish(this, if (cancelled) TaskState.CANCELLED else TaskState.FAILED,
                if (cancelled) TaskState.CANCELLED.label else "${t.message ?: t.javaClass.simpleName}".take(200))
            if (cancelled) throw TaskCancelledException()
            throw t
        }
    }
}

object TaskManager {
    private val active = ConcurrentHashMap<String, TaskHandle>()
    private val historyLock = Any()
    private val history = ArrayList<TaskRecord>()
    private var loaded = false
    private fun historyEnabled() = ConfigStoreRegistry.store.get(ConfigKeys.TASK_HISTORY_ENABLED)
    private fun loadHistory() = synchronized(historyLock) {
        if (!loaded) {
            val context = Host.context() ?: return@synchronized
            history.addAll(TaskRecords.decode(context.getSharedPreferences("pixivplus_tasks", 0).getString("history", "[]") ?: "[]"))
            loaded = true
        }
    }
    fun begin(kind: TaskKind, title: String, workId: Long = 0, workType: String = "ILLUST", downloadPlan: String? = null): TaskHandle {
        loadHistory()
        val task = TaskHandle(TaskRecord(java.util.UUID.randomUUID().toString(), title.take(160), kind,
            workId = workId, workType = workType, downloadPlan = downloadPlan))
        active[task.record.id] = task
        persist(task.record)
        TaskNotifications.show(task.record)
        return task
    }
    fun activeTasks(): List<TaskRecord> = active.values.map { it.record }.sortedByDescending { it.createdAt }
    fun history(): List<TaskRecord> {
        loadHistory()
        if (!historyEnabled()) return emptyList()
        return synchronized(historyLock) { history.filter { it.state.terminal }.sortedByDescending { it.createdAt } }
    }
    fun clearHistory() {
        loadHistory()
        synchronized(historyLock) { history.removeAll { it.state.terminal }; saveHistory() }
    }
    fun cancel(id: String) {
        val task = active[id] ?: return
        synchronized(task.lock) {
            if (task.record.state.terminal || task.record.state == TaskState.CANCELLING) return
            task.record = task.record.copy(state = TaskState.CANCELLING,
                message = if (task.record.downloadPlan == null) T.CANCELLING_CLEAN() else T.CANCELLING_KEEP())
            task.token.cancel(closeAsync = true) // 不在通知接收器的主线程阻塞 disconnect。
            TaskNotifications.show(task.record)
        }
    }
    internal fun running(task: TaskHandle) = synchronized(task.lock) {
        if (!task.token.isCancelled) task.record = task.record.copy(state = TaskState.RUNNING, message = T.PROCESSING())
        TaskNotifications.show(task.record)
    }
    internal fun progress(task: TaskHandle, progress: TaskExecution.Progress) = synchronized(task.lock) {
        task.token.check()
        val total = progress.total.coerceAtLeast(0)
        task.record = task.record.copy(message = progress.message.take(200), done = progress.done.coerceIn(0, total), total = total)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - task.lastNotification >= 700) {
            task.lastNotification = now
            TaskNotifications.show(task.record)
        }
    }
    internal fun finish(task: TaskHandle, state: TaskState, message: String) = synchronized(task.lock) {
        val cancelled = task.token.isCancelled
        task.record = task.record.copy(state = if (cancelled) TaskState.CANCELLED else state,
            message = if (cancelled) TaskState.CANCELLED.label else message, finishedAt = System.currentTimeMillis())
        active.remove(task.record.id)
        persist(task.record)
        TaskNotifications.show(task.record)
    }
    fun refreshNotifications() { TaskNotifications.refresh(activeTasks()) }
    private fun persist(record: TaskRecord) {
        if (record.kind != TaskKind.DOWNLOAD || !historyEnabled()) return
        synchronized(historyLock) {
            history.removeAll { it.id == record.id }
            history.add(0, record)
            while (history.size > TaskRecords.HISTORY_LIMIT) history.removeAt(history.lastIndex)
            saveHistory()
        }
    }
    private fun saveHistory() {
        Host.context()?.getSharedPreferences("pixivplus_tasks", 0)?.edit()?.putString("history", TaskRecords.encode(history))?.apply()
    }
}
