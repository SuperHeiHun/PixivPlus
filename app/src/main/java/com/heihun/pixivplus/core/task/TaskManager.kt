package com.heihun.pixivplus.core.task

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import java.util.concurrent.ConcurrentHashMap

class TaskHandle internal constructor(initial: TaskRecord) {
    internal val lock = Any()
    @Volatile var record: TaskRecord = initial
        internal set
    val token = CancellationToken()
    internal var lastNotification = 0L
    fun progress(message: String) = TaskManager.progress(this, message)
    fun <T> execute(work: () -> T): T {
        TaskManager.running(this)
        try {
            val result = TaskExecution.withContext(TaskExecution.Context(token, ::progress), work)
            TaskManager.finish(this, TaskState.SUCCEEDED, "已完成")
            token.check()
            return result
        } catch (t: Throwable) {
            val cancelled = token.isCancelled || t is java.util.concurrent.CancellationException || t is InterruptedException
            TaskManager.finish(this, if (cancelled) TaskState.CANCELLED else TaskState.FAILED,
                if (cancelled) "已取消" else "${t.message ?: t.javaClass.simpleName}".take(200))
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
    fun begin(kind: TaskKind, title: String): TaskHandle {
        loadHistory()
        val task = TaskHandle(TaskRecord(java.util.UUID.randomUUID().toString(), title.take(160), kind))
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
            task.record = task.record.copy(state = TaskState.CANCELLING, message = "正在取消并清理临时文件…")
            task.token.cancel(closeAsync = true) // 不在通知接收器的主线程阻塞 disconnect。
            TaskNotifications.show(task.record)
        }
    }
    internal fun running(task: TaskHandle) = synchronized(task.lock) {
        if (!task.token.isCancelled) task.record = task.record.copy(state = TaskState.RUNNING, message = "正在处理…")
        TaskNotifications.show(task.record)
    }
    internal fun progress(task: TaskHandle, message: String) = synchronized(task.lock) {
        task.token.check()
        val match = Regex("\\((\\d+)/(\\d+)\\)").find(message)
        task.record = task.record.copy(message = message.take(200), done = match?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            total = match?.groupValues?.get(2)?.toIntOrNull() ?: 0)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - task.lastNotification >= 700) {
            task.lastNotification = now
            TaskNotifications.show(task.record)
        }
    }
    internal fun finish(task: TaskHandle, state: TaskState, message: String) = synchronized(task.lock) {
        val cancelled = task.token.isCancelled
        task.record = task.record.copy(state = if (cancelled) TaskState.CANCELLED else state,
            message = if (cancelled) "已取消" else message, finishedAt = System.currentTimeMillis())
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
