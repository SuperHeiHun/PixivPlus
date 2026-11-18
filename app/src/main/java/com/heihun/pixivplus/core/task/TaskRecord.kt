package com.heihun.pixivplus.core.task

import org.json.JSONArray
import org.json.JSONObject

enum class TaskKind { DOWNLOAD, TRANSLATE }
enum class TaskState(val label: String, val terminal: Boolean = false) {
    QUEUED("等待处理"), RUNNING("进行中"), CANCELLING("取消中"),
    SUCCEEDED("已完成", true), CANCELLED("已取消", true), FAILED("失败", true), INTERRUPTED("宿主重启，任务中断", true)
}
data class TaskRecord(
    val id: String, val title: String, val kind: TaskKind, val state: TaskState = TaskState.QUEUED,
    val message: String = "等待处理", val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0, val done: Int = 0, val total: Int = 0,
    val workId: Long = 0, val workType: String = "ILLUST", val downloadPlan: String? = null
)

object TaskRecords {
    const val HISTORY_LIMIT = 100
    fun encode(records: List<TaskRecord>): String = JSONArray().apply {
        records.take(HISTORY_LIMIT).forEach { record -> put(JSONObject()
            .put("id", record.id).put("title", record.title).put("kind", record.kind.name)
            .put("state", record.state.name).put("message", record.message).put("created", record.createdAt)
            .put("finished", record.finishedAt).put("done", record.done).put("total", record.total)
            .put("work", record.workId).put("work_type", record.workType).put("download_plan", record.downloadPlan)) }
    }.toString()
    fun decode(text: String): List<TaskRecord> = try {
        val array = JSONArray(text)
        (0 until minOf(array.length(), HISTORY_LIMIT)).mapNotNull { index ->
            try {
                val item = array.getJSONObject(index)
                val state = TaskState.valueOf(item.getString("state"))
                TaskRecord(item.getString("id"), item.getString("title"), TaskKind.valueOf(item.getString("kind")),
                    if (state.terminal) state else TaskState.INTERRUPTED,
                    if (state.terminal) item.optString("message") else TaskState.INTERRUPTED.label,
                    item.getLong("created"), item.optLong("finished"), item.optInt("done"), item.optInt("total"),
                    item.optLong("work"), item.optString("work_type", "ILLUST"),
                    if (item.isNull("download_plan")) null else item.optString("download_plan").takeIf { it.isNotBlank() })
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() }
}
