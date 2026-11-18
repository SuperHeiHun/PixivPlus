package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.heihun.pixivplus.core.media.ModuleCache
import com.heihun.pixivplus.ui.UiKit

object CachePanel {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "plp-cache-clean").apply { isDaemon = true } }
    fun build(activity: Activity, body: LinearLayout) {
        val card = UiKit.card(activity)
        val size = UiKit.infoRow(activity, "模块磁盘缓存", "正在统计…")
        card.addView(size)
        fun refresh() { executor.execute {
            val bytes = try { ModuleCache.size(activity.applicationContext) } catch (_: Exception) { 0L }
            activity.runOnUiThread { (size.getChildAt(1) as? TextView)?.text = android.text.format.Formatter.formatFileSize(activity, bytes) }
        } }
        card.addView(UiKit.paragraph(activity, "清理下载断点、导出临时文件、旧调试响应缓存，并清空预览和翻译内存缓存。\n不删除已保存作品、设置、下载历史或宿主缓存。清理后未完成任务需要重新下载；运行中的任务必须先结束。"))
        card.addView(UiKit.filledButtonRow(activity, "清理模块缓存") {
            ConfirmDialog.show(activity, "清理模块缓存", "未完成下载的断点会被删除，继续下载时会从头开始。已保存作品不会删除。", "清理") {
                executor.execute {
                    val message = try { val bytes = ModuleCache.clear(activity.applicationContext); "已清理：${android.text.format.Formatter.formatFileSize(activity, bytes)}" }
                        catch (t: Exception) { t.message ?: "缓存清理失败" }
                    activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); refresh() }
                }
            }
        })
        body.addView(card)
        refresh()
    }
}
