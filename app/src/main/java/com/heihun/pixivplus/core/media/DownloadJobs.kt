package com.heihun.pixivplus.core.media

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import com.heihun.pixivplus.core.i18n.DownloadText as D
import com.heihun.pixivplus.core.i18n.TaskText as T
import com.heihun.pixivplus.core.task.TaskCancelledException
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
        check(root.canonicalFile == root.absoluteFile && result.canonicalFile == result.absoluteFile) { D.BAD_CHECKPOINT() }
        return result
    }

    fun submit(activity: Activity, plan: DownloadPlan, restart: Boolean = false, images: List<OriginalImage>? = null) {
        // 校验持久化记录，禁止缓存目录穿越。
        DownloadPlan.decode(plan.encode())
        synchronized(cacheLock) {
            if (!active.add(plan.cacheKey)) { Toast.makeText(activity, T.ALREADY_QUEUED(), Toast.LENGTH_SHORT).show(); return }
            val title = if (plan.format == DownloadFormat.IMAGES) T.DOWNLOAD_IMAGES(plan.stem) else "${plan.format.name} · ${plan.stem}"
            val task = TaskManager.begin(TaskKind.DOWNLOAD, title, plan.workId, downloadPlan = plan.encode())
            val context = activity.applicationContext
            executor.execute {
                try {
                    val result = task.execute {
                        val directory = checkpoint(context, plan)
                        if (restart && directory.exists()) check(directory.deleteRecursively()) { D.REMOVE_CHECKPOINT_FAILED() }
                        check(directory.mkdirs() || directory.isDirectory) { D.CREATE_CHECKPOINT_FAILED() }
                        val store = ConfigStoreRegistry.store
                        val saved = if (plan.format == DownloadFormat.GIF) {
                            listOf(MediaExporter.saveGif(context, store, Uri.parse(plan.folder), plan.workId, plan.stem, directory))
                        } else {
                            val available = images ?: MediaRepository.originals(store, plan.workId, null)
                            val selected = available.filter { it.page in plan.pages }
                            check(selected.size == plan.pages.size) { D.PAGES_CHANGED() }
                            MediaExporter.saveOriginals(context, store, Uri.parse(plan.folder), plan.stem, selected,
                                plan.format == DownloadFormat.ZIP, plan.linksText, directory)
                        }
                        // 成功后无需保留断点；历史的重新下载会重新获取地址并从头下载。
                        task.recordFiles(saved)
                        directory.deleteRecursively()
                        saved
                    }
                    activity.runOnUiThread { Toast.makeText(context, T.FILES_SAVED(result.size, result.first().name), Toast.LENGTH_LONG).show() }
                } catch (t: Throwable) {
                    if (t !is Exception && t !is OutOfMemoryError) throw t
                    activity.runOnUiThread {
                        val message = if (t is TaskCancelledException) T.DOWNLOAD_CANCELLED() else T.DOWNLOAD_FAILED(t.message ?: t.javaClass.simpleName)
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                } finally { synchronized(cacheLock) { active.remove(plan.cacheKey) } }
            }
        }
    }
}
