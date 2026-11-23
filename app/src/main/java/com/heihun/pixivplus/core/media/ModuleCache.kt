package com.heihun.pixivplus.core.media

import android.content.Context
import com.heihun.pixivplus.core.i18n.PanelText as P
import com.heihun.pixivplus.core.task.TaskManager
import com.heihun.pixivplus.core.translate.Translator
import java.io.File

/** 仅管理模块下载断点、导出临时目录及旧调试缓存，不触碰宿主图片、配置、历史或原生库。 */
object ModuleCache {
    internal fun ownsDirectoryName(name: String): Boolean = name == "pixivplus_downloads" || name == "plp_bodies" ||
        name.matches(Regex("plp-media-[a-fA-F0-9-]{36}"))
    private fun roots(context: Context): List<File> = context.cacheDir.canonicalFile.listFiles()?.filter {
        ownsDirectoryName(it.name)
    }.orEmpty()
    private fun safeChildren(file: File, cacheRoot: File): List<File> {
        if (file.canonicalFile != file.absoluteFile || !file.path.startsWith(cacheRoot.path + File.separator)) return emptyList()
        return if (file.isDirectory) file.listFiles()?.toList().orEmpty() else emptyList()
    }
    fun size(context: Context): Long {
        val root = context.cacheDir.canonicalFile
        fun bytes(file: File): Long {
            if (file.canonicalFile != file.absoluteFile) return 0
            return if (file.isDirectory) safeChildren(file, root).sumOf { bytes(it) } else file.length()
        }
        return roots(context).sumOf { bytes(it) }
    }
    fun clear(context: Context): Long = synchronized(DownloadJobs.cacheLock) {
        check(TaskManager.activeTasks().isEmpty()) { P.CACHE_ACTIVE() }
        val root = context.cacheDir.canonicalFile
        val before = size(context)
        fun remove(file: File) {
            if (file.canonicalFile != file.absoluteFile || !file.path.startsWith(root.path + File.separator)) return
            safeChildren(file, root).forEach(::remove)
            check(!file.exists() || file.delete()) { P.CACHE_PARTIAL() }
        }
        roots(context).forEach(::remove)
        Translator.clearCache()
        MediaPreviewLoader.clearCaches()
        before
    }
}
