package com.heihun.pixivplus.ui.panel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.heihun.pixivplus.adapt.ResolveRegistry
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.log.LogExporter
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.ui.UiKit

object DiagnosticsPanel {

    fun build(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(UiKit.infoRow(context, "宿主版本", "${Host.versionName} (${Host.versionCode})"))
        container.addView(UiKit.infoRow(context, "当前进程", processLabel()))
        container.addView(UiKit.infoRow(context, "数据库", Runtime.databasePath ?: "未初始化（非主进程或初始化失败）"))
        container.addView(UiKit.infoRow(context, "配置", Runtime.configOrNull()?.javaClass?.simpleName ?: "未初始化"))
        container.addView(
            UiKit.infoRow(
                context,
                "接口响应处理",
                "已处理 ${com.heihun.pixivplus.core.net.PayloadProcessor.processedCount()} 个，已过滤 ${com.heihun.pixivplus.core.net.PayloadProcessor.filteredCount()} 项"
            )
        )

        container.addView(UiKit.sectionTitle(context, "锚点解析状态"))
        val anchors = ResolveRegistry.all()
        if (anchors.isEmpty()) {
            container.addView(UiKit.infoRow(context, "状态", "尚未解析"))
        } else {
            for ((id, status) in anchors) {
                container.addView(
                    UiKit.infoRow(
                        context,
                        ResolveRegistry.description(id),
                        "${status.signature} [${status.via}]"
                    )
                )
            }
        }

        container.addView(UiKit.sectionTitle(context, "日志"))
        container.addView(
            UiKit.buttonRow(context, "导出日志到文件") {
                val path = LogExporter.export(context)
                if (path == null) {
                    toast(context, "导出失败，详见日志")
                } else {
                    toast(context, "已导出：$path")
                }
            }
        )
        container.addView(
            UiKit.buttonRow(context, "复制最近 200 行日志") {
                val text = ModuleLog.dump().takeLast(200).joinToString("\n")
                copyToClipboard(context, text)
                toast(context, "已复制到剪贴板")
            }
        )
        container.addView(
            UiKit.buttonRow(context, "清空内存日志") {
                ModuleLog.clear()
                toast(context, "已清空")
            }
        )
        return container
    }

    private fun processLabel(): String {
        val process = Host.processName.ifEmpty { "unknown" }
        return if (Host.isMainProcess) "$process (主进程)" else "$process (子进程)"
    }

    private fun copyToClipboard(context: Context, text: String) {
        try {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            manager.setPrimaryClip(ClipData.newPlainText("PixivPlus", text))
        } catch (t: Throwable) {
            ModuleLog.e("DiagnosticsPanel", "copy failed", t)
        }
    }

    private fun toast(context: Context, message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
