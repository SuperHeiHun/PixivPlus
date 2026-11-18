package com.heihun.pixivplus.core.media

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import com.heihun.pixivplus.core.task.TaskCancelledException
import com.heihun.pixivplus.core.task.TaskExecution
import com.heihun.pixivplus.core.task.TaskKind
import com.heihun.pixivplus.core.task.TaskManager
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import java.io.File

object DownloadJobs {
    /** 缓存清理和提交任务共用门闩，避免清理后立刻启动任务的竞态。 */
    val cacheLock = Any()
    private val active = HashSet<String>()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "plp-download-jobs").apply { isDaemon = true } }
    fun checkpoint(context: android.content.Context, plan: DownloadPlan): File {
        val root = File(context.cacheDir.canonicalFile, "pixivplus_downloads")
        val result = File(root, plan.cacheKey)
        check(root.canonicalFile == root.absoluteFile && result.canonicalFile == result.absoluteFile) { "下载断点目录无效" }
        return result
    }

    fun submit(activity: Activity, plan: DownloadPlan, restart: Boolean = false, images: List<OriginalImage>? = null) {
        // 校验持久化记录，禁止缓存目录穿越。
        DownloadPlan.decode(plan.encode())
        synchronized(cacheLock) {
            if (!active.add(plan.cacheKey)) { Toast.makeText(activity, "该下载已在任务列表中", Toast.LENGTH_SHORT).show(); return }
            val task = TaskManager.begin(TaskKind.DOWNLOAD, "${plan.format.name}：${plan.stem}", plan.workId, downloadPlan = plan.encode())
            val context = activity.applicationContext
            executor.execute {
                try {
                    val result = task.execute {
                        val directory = checkpoint(context, plan)
                        if (restart && directory.exists()) check(directory.deleteRecursively()) { "无法清理旧断点" }
                        check(directory.mkdirs() || directory.isDirectory) { "无法创建断点目录" }
                        val store = ConfigStoreRegistry.store
                        val saved = if (plan.format == DownloadFormat.GIF) {
                            listOf(MediaExporter.saveGif(context, store, Uri.parse(plan.folder), plan.workId, plan.stem, directory, TaskExecution::progress))
                        } else {
                            val available = images ?: MediaRepository.originals(store, plan.workId, null)
                            val selected = available.filter { it.page in plan.pages }
                            check(selected.size == plan.pages.size) { "作品页码已变化，请重新选择图片" }
                            MediaExporter.saveOriginals(context, store, Uri.parse(plan.folder), plan.stem, selected,
                                plan.format == DownloadFormat.ZIP, plan.linksText, directory, TaskExecution::progress)
                        }
                        // 成功后无需保留断点；历史的重新下载会重新获取地址并从头下载。
                        directory.deleteRecursively()
                        saved
                    }
                    activity.runOnUiThread { Toast.makeText(context, "已保存 ${result.size} 个文件：${result.first()}", Toast.LENGTH_LONG).show() }
                } catch (t: Throwable) {
                    if (t !is Exception && t !is OutOfMemoryError) throw t
                    activity.runOnUiThread { Toast.makeText(context, if (t is TaskCancelledException) "下载已取消，可在任务列表继续下载" else "下载失败，可在任务列表继续或重新下载：${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
                } finally { synchronized(cacheLock) { active.remove(plan.cacheKey) } }
            }
        }
    }
}
