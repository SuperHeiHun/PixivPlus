package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.backup.BackupManager
import com.heihun.pixivplus.core.backup.WebDavBackup
import com.heihun.pixivplus.core.config.ConfigKey
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.ui.ActivityResultBridge
import com.heihun.pixivplus.ui.UiKit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置面板中的「数据备份」卡片：本地文件夹与 WebDAV。 */
object BackupPanel {

    private const val TAG = "BackupPanel"

    fun build(activity: Activity, card: LinearLayout) {
        card.removeAllViews()
        card.addView(UiKit.cardSectionTitle(activity, "本地文件"))
        card.addView(
            UiKit.selectRow(
                activity,
                "备份文件夹（系统选择器）",
                folderLabel(activity)
            ) { pickFolder(activity, card) }
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.filledButtonRow(activity, "立即备份到所选文件夹") { createLocal(activity) }
        )
        card.addView(UiKit.buttonRow(activity, "从本地备份恢复") { selectLocal(activity) })
        card.addView(
            UiKit.paragraph(
                activity,
                "备份文件以 pixiv-backup-yy-MM-dd_HH-mm.json 命名，保存在手动选择的文件夹中；" +
                    "文件夹授权仅保留在本机。"
            )
        )

        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.cardSectionTitle(activity, "WebDAV"))
        val locationRow = UiKit.infoRow(activity, "保存位置", locationLabel())
        card.addView(
            UiKit.inputRow(
                activity,
                "服务器地址 / IP",
                configText(ConfigKeys.WEBDAV_HOST),
                "例如 192.168.10.12",
                singleLine = true
            ) { value ->
                putConfig(ConfigKeys.WEBDAV_HOST, value.trim())
                refreshLocation(locationRow)
            }
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.inputRow(
                activity,
                "端口",
                configText(ConfigKeys.WEBDAV_PORT),
                "80",
                numeric = true
            ) { value ->
                putConfig(ConfigKeys.WEBDAV_PORT, value.filter { it.isDigit() }.take(5))
                refreshLocation(locationRow)
            }
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.switchRow(
                activity,
                "使用 HTTPS",
                "关闭时使用 http://；开启时使用 https://",
                configValue(ConfigKeys.WEBDAV_HTTPS)
            ) { checked ->
                putConfig(ConfigKeys.WEBDAV_HTTPS, checked)
                refreshLocation(locationRow)
            }
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.inputRow(
                activity,
                "用户名（可选）",
                configText(ConfigKeys.WEBDAV_USERNAME),
                "留空表示免认证",
                singleLine = true
            ) { value -> putConfig(ConfigKeys.WEBDAV_USERNAME, value.trim()) }
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.inputRow(
                activity,
                "密码（可选）",
                configText(ConfigKeys.WEBDAV_PASSWORD),
                "留空表示无密码",
                password = true
            ) { value -> putConfig(ConfigKeys.WEBDAV_PASSWORD, value) }
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(locationRow)
        card.addView(UiKit.filledButtonRow(activity, "上传备份到 WebDAV") { uploadWebDav(activity) })
        card.addView(UiKit.buttonRow(activity, "从 WebDAV 恢复最近备份") { downloadWebDav(activity) })
        card.addView(
            UiKit.paragraph(
                activity,
                "备份固定保存在 /PixivPlus/，文件名为 pixiv-backup-yy-MM-dd_HH-mm.json；" +
                    "网络请求沿用上方的代理设置。"
            )
        )

        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.paragraph(
                activity,
                "备份不包含代理密码、WebDAV 密码和本地备份文件夹；恢复会覆盖当前配置、" +
                    "历史记录与作者资料，以上三项保留本机现有值。"
            )
        )
    }

    private fun pickFolder(activity: Activity, card: LinearLayout) {
        ActivityResultBridge.pickFolder(activity) { resultActivity, uri ->
            if (uri == null) {
                toast(resultActivity, "未选择文件夹")
            } else {
                putConfig(ConfigKeys.BACKUP_FOLDER_URI, uri.toString())
                ModuleLog.i(TAG, "backup folder selected: $uri")
                toast(resultActivity, "已选择备份文件夹")
                build(resultActivity, card)
            }
        }
    }

    private fun createLocal(activity: Activity) {
        val treeUri = folderUri()
        if (treeUri == null) {
            toast(activity, "请先通过系统选择器选择备份文件夹")
            return
        }
        toast(activity, "正在创建本地备份…")
        Thread({
            val file = BackupManager.createLocal(activity, treeUri)
            runOnUi(activity) {
                if (file == null) {
                    toast(activity, "本地备份失败：无法写入所选文件夹，详情见日志")
                } else {
                    toast(activity, "已创建备份：${file.name}")
                    ModuleLog.i(TAG, "local backup created: ${file.uri}")
                }
            }
        }, "plp-backup-local").apply { isDaemon = true }.start()
    }

    private fun selectLocal(activity: Activity) {
        val treeUri = folderUri()
        if (treeUri == null) {
            toast(activity, "请先通过系统选择器选择备份文件夹")
            return
        }
        toast(activity, "正在读取备份列表…")
        Thread({
            val files = BackupManager.listLocal(activity, treeUri)
            runOnUi(activity) {
                if (files.isEmpty()) {
                    toast(activity, "所选文件夹中没有 pixiv-backup-*.json 备份")
                } else {
                    ChoiceDialog.show(
                        activity,
                        "选择本地备份",
                        files.map { localLabel(it) },
                        0
                    ) { index ->
                        val file = files.getOrNull(index) ?: return@show
                        Thread({
                            val json = BackupManager.readLocal(activity, file.uri)
                            val info = json?.let { BackupManager.info(it) }
                            runOnUi(activity) {
                                if (json == null || info == null) {
                                    toast(activity, "无法读取该备份文件")
                                } else {
                                    confirmRestore(activity, "本地备份 ${file.name}", info) {
                                        restoreAsync(activity, json)
                                    }
                                }
                            }
                        }, "plp-backup-read").apply { isDaemon = true }.start()
                    }
                }
            }
        }, "plp-backup-list").apply { isDaemon = true }.start()
    }

    private fun uploadWebDav(activity: Activity) {
        val store = Runtime.configOrNull()
        if (store == null) {
            toast(activity, "配置尚未初始化")
            return
        }
        val endpoint = endpointOrNull(activity) ?: return
        val username = configText(ConfigKeys.WEBDAV_USERNAME)
        val password = configText(ConfigKeys.WEBDAV_PASSWORD)
        toast(activity, "正在上传备份…")
        Thread({
            val name = BackupManager.fileName()
            val data = BackupManager.createJson().toByteArray(Charsets.UTF_8)
            val result = WebDavBackup.upload(
                store = store,
                host = endpoint.host,
                port = endpoint.port,
                https = endpoint.https,
                username = username,
                password = password,
                fileName = name,
                data = data
            )
            runOnUi(activity) { toast(activity, result.message) }
        }, "plp-backup-webdav-up").apply { isDaemon = true }.start()
    }

    private fun downloadWebDav(activity: Activity) {
        val store = Runtime.configOrNull()
        if (store == null) {
            toast(activity, "配置尚未初始化")
            return
        }
        val endpoint = endpointOrNull(activity) ?: return
        val username = configText(ConfigKeys.WEBDAV_USERNAME)
        val password = configText(ConfigKeys.WEBDAV_PASSWORD)
        toast(activity, "正在查找最近的 WebDAV 备份…")
        Thread({
            val latest = WebDavBackup.latest(
                store = store,
                host = endpoint.host,
                port = endpoint.port,
                https = endpoint.https,
                username = username,
                password = password
            )
            val remote = latest.backup
            if (!latest.ok || remote == null) {
                runOnUi(activity) { toast(activity, latest.message) }
                return@Thread
            }
            val download = WebDavBackup.download(store, remote, username, password)
            val body = download.body
            if (!download.ok || body == null) {
                runOnUi(activity) { toast(activity, download.message) }
                return@Thread
            }
            val info = BackupManager.info(body)
            runOnUi(activity) {
                if (info == null) {
                    toast(activity, "WebDAV 文件不是有效的 PixivPlus 备份")
                } else {
                    confirmRestore(activity, "WebDAV 备份 ${remote.name}", info) {
                        restoreAsync(activity, body)
                    }
                }
            }
        }, "plp-backup-webdav-down").apply { isDaemon = true }.start()
    }

    private fun confirmRestore(
        activity: Activity,
        source: String,
        info: BackupManager.BackupInfo,
        onConfirm: () -> Unit
    ) {
        val message = buildString {
            append("来源：").append(source).append('\n')
            append("备份时间：")
            append(
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(info.createdAt))
            ).append('\n')
            append("模块版本：").append(info.moduleVersion).append('\n')
            append("内容：配置 ").append(info.configCount).append(" 项、历史 ")
            append(info.historyCount).append(" 条、作者资料 ")
            append(info.authorCount).append(" 条\n\n")
            append("恢复会覆盖当前配置、历史记录与作者资料；代理密码、WebDAV 密码")
            append("和本地备份文件夹保留本机现有值。")
        }
        ConfirmDialog.show(activity, "恢复备份", message, "恢复", danger = true) {
            onConfirm()
        }
    }

    private fun restoreAsync(activity: Activity, json: String) {
        toast(activity, "正在恢复备份…")
        Thread({
            val result = BackupManager.restore(json)
            runOnUi(activity) {
                val message = if (result.ok) {
                    "${result.message}；重新打开设置面板可查看更新"
                } else {
                    result.message
                }
                toast(activity, message)
            }
        }, "plp-backup-restore").apply { isDaemon = true }.start()
    }

    private fun folderUri(): Uri? {
        val raw = configText(ConfigKeys.BACKUP_FOLDER_URI)
        if (raw.isBlank()) return null
        return try {
            Uri.parse(raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun folderLabel(context: Context): String {
        val uri = folderUri() ?: return "未选择（点按选择）"
        val name = BackupManager.folderName(context, uri)
        return name ?: "无法读取（点按重新选择）"
    }

    private fun locationLabel(): String {
        val host = configText(ConfigKeys.WEBDAV_HOST)
        val port = configText(ConfigKeys.WEBDAV_PORT).trim().toIntOrNull()
        val https = configValue(ConfigKeys.WEBDAV_HTTPS)
        return if (port == null) {
            "未设置"
        } else {
            WebDavBackup.folderUrl(host, port, https)?.toString() ?: "未设置"
        }
    }

    private fun refreshLocation(row: LinearLayout) {
        val value = row.getChildAt(1) as? TextView ?: return
        value.text = locationLabel()
    }

    private fun endpointOrNull(activity: Activity): Endpoint? {
        val host = WebDavBackup.normalizeHost(configText(ConfigKeys.WEBDAV_HOST))
        if (host.isNullOrEmpty()) {
            toast(activity, "请先填写 WebDAV 服务器地址或 IP")
            return null
        }
        val port = configText(ConfigKeys.WEBDAV_PORT).trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            toast(activity, "WebDAV 端口无效（1-65535）")
            return null
        }
        return Endpoint(host, port, configValue(ConfigKeys.WEBDAV_HTTPS))
    }

    private fun localLabel(file: BackupManager.LocalBackup): String {
        val time = if (file.modifiedAt > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.modifiedAt))
        } else {
            "未知时间"
        }
        return "$time · ${formatSize(file.size)}\n${file.name}"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun configText(key: ConfigKey<String>): String {
        return try {
            Runtime.configOrNull()?.get(key) ?: key.defaultValue
        } catch (_: Throwable) {
            key.defaultValue
        }
    }

    private fun configValue(key: ConfigKey<Boolean>): Boolean {
        return try {
            Runtime.configOrNull()?.get(key) ?: key.defaultValue
        } catch (_: Throwable) {
            key.defaultValue
        }
    }

    private fun putConfig(key: ConfigKey<String>, value: String) {
        try {
            Runtime.configOrNull()?.put(key, value)
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "write config failed", t)
        }
    }

    private fun putConfig(key: ConfigKey<Boolean>, value: Boolean) {
        try {
            Runtime.configOrNull()?.put(key, value)
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "write config failed", t)
        }
    }

    private fun runOnUi(activity: Activity, block: () -> Unit) {
        try {
            activity.runOnUiThread {
                if (!activity.isFinishing) {
                    try {
                        block()
                    } catch (t: Throwable) {
                        ModuleLog.w(TAG, "ui callback failed", t)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun toast(context: Context, message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
        }
    }

    private data class Endpoint(val host: String, val port: Int, val https: Boolean)
}
