package com.heihun.pixivplus.core.translate

import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.novel.NovelBlock
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

/** 阅读页与保存共用的小说翻译管线；调用方必须在后台线程运行。 */
object NovelTranslation {
    fun translate(
        store: ConfigStore,
        blocks: List<NovelBlock>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<NovelText.Line> {
        val lines = NovelText.buildLines(blocks)
        val groups = NovelText.group(lines)
        if (groups.isEmpty()) throw IllegalStateException("没有可翻译的文本")
        val workers = Executors.newFixedThreadPool(minOf(3, groups.size)) { task ->
            Thread(task, "plp-novel-translate").apply { isDaemon = true }
        }
        val completed = ExecutorCompletionService<Pair<NovelText.Group, Translator.Result>>(workers)
        try {
            for (group in groups) {
                completed.submit(Callable { group to Translator.translateChunk(store, group.text) })
            }
            for (index in groups.indices) {
                val (group, result) = completed.take().get()
                val text = result.text ?: throw IllegalStateException(result.error ?: "翻译请求失败")
                val outputs = ArrayList(text.split('\n').map { it.trim() })
                while (outputs.isNotEmpty() && outputs.last().isEmpty()) outputs.removeAt(outputs.size - 1)
                group.outputs.addAll(if (outputs.isEmpty()) listOf("") else outputs)
                onProgress(index + 1, groups.size)
            }
            return NovelText.render(lines, groups)
        } finally {
            workers.shutdownNow()
        }
    }
}
