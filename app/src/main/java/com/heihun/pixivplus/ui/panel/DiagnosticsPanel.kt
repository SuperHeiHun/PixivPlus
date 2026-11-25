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
import com.heihun.pixivplus.core.i18n.CommonText as C
import com.heihun.pixivplus.core.i18n.I18n
import com.heihun.pixivplus.core.i18n.PanelText as P
import com.heihun.pixivplus.core.log.LogExporter
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.ui.UiKit

object DiagnosticsPanel {

    fun build(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(UiKit.infoRow(context, P.HOST_VERSION(), "${Host.versionName} (${Host.versionCode})"))
        container.addView(UiKit.infoRow(context, P.MODULE_BUILD(), moduleBuildLabel()))
        container.addView(UiKit.infoRow(context, P.PROCESS(), processLabel()))
        container.addView(UiKit.infoRow(context, P.DATABASE(), Runtime.databasePath ?: P.DATABASE_STATUS()))
        container.addView(UiKit.infoRow(context, P.CONFIG(), Runtime.configOrNull()?.javaClass?.simpleName ?: C.NOT_READY()))
        container.addView(
            UiKit.infoRow(
                context,
                P.RESPONSES(),
                P.RESPONSE_COUNTS(com.heihun.pixivplus.core.net.PayloadProcessor.processedCount(), com.heihun.pixivplus.core.net.PayloadProcessor.filteredCount())
            )
        )

        container.addView(UiKit.divider(context, 16, 16))
        container.addView(UiKit.cardSectionTitle(context, P.ANCHORS()))
        val anchors = ResolveRegistry.all()
        if (anchors.isEmpty()) {
            container.addView(UiKit.infoRow(context, P.STATUS(), P.NOT_RESOLVED()))
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

        container.addView(UiKit.divider(context, 16, 16))
        container.addView(UiKit.cardSectionTitle(context, P.LOGS()))
        container.addView(
            UiKit.filledButtonRow(context, P.EXPORT_LOGS()) {
                val path = LogExporter.export(context)
                if (path == null) {
                    toast(context, P.EXPORT_LOGS_FAILED())
                } else {
                    toast(context, P.EXPORTED(path))
                }
            }
        )
        container.addView(
            UiKit.buttonRow(context, P.COPY_LOGS()) {
                val text = ModuleLog.dump().takeLast(200).joinToString("\n")
                copyToClipboard(context, text)
                toast(context, P.COPIED())
            }
        )
        container.addView(
            UiKit.dangerButtonRow(context, P.CLEAR_LOGS()) {
                ModuleLog.clear()
                toast(context, C.CLEARED())
            }
        )
        return container
    }

    private fun processLabel(): String {
        val process = Host.processName.ifEmpty { C.UNKNOWN() }
        return if (Host.isMainProcess) P.MAIN_PROCESS(process) else P.CHILD_PROCESS(process)
    }

    /** 新构建显示稳定源码指纹；兼容旧 APK 的数字时间戳，便于确认设备上的构建。 */
    fun moduleBuildLabel(): String {
        return try {
            val raw = com.heihun.pixivplus.BuildConfig.BUILD_FINGERPRINT
            val ts = raw.toLongOrNull() ?: return raw
            val time = I18n.dateTime(ts)
            "$time ($raw)"
        } catch (_: Throwable) {
            C.UNKNOWN()
        }
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
