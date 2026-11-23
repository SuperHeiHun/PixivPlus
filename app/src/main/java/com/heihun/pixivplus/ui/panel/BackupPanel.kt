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
import com.heihun.pixivplus.core.i18n.BackupText as B
import com.heihun.pixivplus.core.i18n.CommonText as C
import com.heihun.pixivplus.core.i18n.I18n
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.ui.ActivityResultBridge
import com.heihun.pixivplus.ui.UiKit

/** 设置面板中的「数据备份」卡片：本地文件夹与 WebDAV。 */
object BackupPanel {

    private const val TAG = "BackupPanel"

    fun build(activity: Activity, card: LinearLayout) {
        card.removeAllViews()
        card.addView(UiKit.cardSectionTitle(activity, B.CONTENT()))
        card.addView(
            UiKit.switchRow(
                activity,
                B.INCLUDE_SETTINGS(),
                B.INCLUDE_SETTINGS_HINT(),
                configValue(ConfigKeys.BACKUP_INCLUDE_SETTINGS)
            ) { checked -> putConfig(ConfigKeys.BACKUP_INCLUDE_SETTINGS, checked) }
        )
        card.addView(
            UiKit.switchRow(
                activity,
                B.INCLUDE_FILTERS(),
                B.INCLUDE_FILTERS_HINT(),
                configValue(ConfigKeys.BACKUP_INCLUDE_FILTERS)
            ) { checked -> putConfig(ConfigKeys.BACKUP_INCLUDE_FILTERS, checked) }
        )
        card.addView(
            UiKit.switchRow(
                activity,
                B.INCLUDE_HISTORY(),
                B.INCLUDE_HISTORY_HINT(),
                configValue(ConfigKeys.BACKUP_INCLUDE_HISTORY)
            ) { checked -> putConfig(ConfigKeys.BACKUP_INCLUDE_HISTORY, checked) }
        )
        card.addView(UiKit.paragraph(
            activity,
            B.CONTENT_NOTE()
        ))

        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.cardSectionTitle(activity, B.SECURITY()))
        card.addView(
            UiKit.inputRow(
                activity,
                B.OPTIONAL_BACKUP_PASSWORD(),
                configText(ConfigKeys.BACKUP_PASSWORD),
                B.BLANK_PLAINTEXT(),
                password = true
            ) { value -> putConfig(ConfigKeys.BACKUP_PASSWORD, value.trim()) }
        )
        card.addView(UiKit.paragraph(
            activity,
            B.ENCRYPTION_NOTE()
        ))

        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.cardSectionTitle(activity, B.LOCAL_FILES()))
        card.addView(
            UiKit.selectRow(
                activity,
                B.BACKUP_FOLDER(),
                folderLabel(activity)
            ) { pickFolder(activity, card) }
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.filledButtonRow(activity, B.BACKUP_NOW()) { createLocal(activity) }
        )
        card.addView(UiKit.buttonRow(activity, B.RESTORE_LOCAL()) { selectLocal(activity) })
        card.addView(
            UiKit.paragraph(
                activity,
                B.LOCAL_NOTE()
            )
        )

        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.cardSectionTitle(activity, "WebDAV"))
        val locationRow = UiKit.infoRow(activity, C.FOLDER(), locationLabel())
        card.addView(
            UiKit.inputRow(
                activity,
                B.SERVER_ADDRESS(),
                configText(ConfigKeys.WEBDAV_HOST),
                C.SAMPLE_ADDRESS(),
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
                B.PORT(),
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
                B.USE_HTTPS(),
                B.USE_HTTPS_HINT(),
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
                B.DIRECTORY(),
                configText(ConfigKeys.WEBDAV_DIRECTORY),
                B.DIRECTORY_HINT(),
                singleLine = true
            ) { value ->
                putConfig(ConfigKeys.WEBDAV_DIRECTORY, value.trim())
                refreshLocation(locationRow)
            }
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.inputRow(
                activity,
                C.OPTIONAL_USERNAME(),
                configText(ConfigKeys.WEBDAV_USERNAME),
                C.BLANK_NO_AUTH(),
                singleLine = true
            ) { value -> putConfig(ConfigKeys.WEBDAV_USERNAME, value.trim()) }
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.inputRow(
                activity,
                C.OPTIONAL_PASSWORD(),
                configText(ConfigKeys.WEBDAV_PASSWORD),
                C.BLANK_NO_PASSWORD(),
                password = true
            ) { value -> putConfig(ConfigKeys.WEBDAV_PASSWORD, value) }
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(locationRow)
        card.addView(UiKit.filledButtonRow(activity, B.UPLOAD()) { uploadWebDav(activity) })
        card.addView(UiKit.buttonRow(activity, B.RESTORE_REMOTE()) { downloadWebDav(activity) })
        card.addView(
            UiKit.paragraph(
                activity,
                B.REMOTE_NOTE()
            )
        )

        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.paragraph(
                activity,
                B.PRIVACY_NOTE()
            )
        )
    }

    private fun pickFolder(activity: Activity, card: LinearLayout) {
        ActivityResultBridge.pickFolder(activity) { resultActivity, uri ->
            if (uri == null) {
                toast(resultActivity, C.NO_FOLDER_SELECTED())
            } else {
                putConfig(ConfigKeys.BACKUP_FOLDER_URI, uri.toString())
                ModuleLog.i(TAG, "backup folder selected: $uri")
                toast(resultActivity, B.FOLDER_SELECTED())
                build(resultActivity, card)
            }
        }
    }

    private fun createLocal(activity: Activity) {
        val treeUri = folderUri()
        if (treeUri == null) {
            toast(activity, B.NEED_FOLDER())
            return
        }
        toast(activity, B.CREATING())
        Thread({
            val file = BackupManager.createLocal(activity, treeUri)
            runOnUi(activity) {
                if (file == null) {
                    toast(activity, B.LOCAL_WRITE_FAILED())
                } else {
                    toast(activity, B.CREATED(file.name))
                    ModuleLog.i(TAG, "local backup created: ${file.uri}")
                }
            }
        }, "plp-backup-local").apply { isDaemon = true }.start()
    }

    private fun selectLocal(activity: Activity) {
        val treeUri = folderUri()
        if (treeUri == null) {
            toast(activity, B.NEED_FOLDER())
            return
        }
        toast(activity, B.READING_LIST())
        Thread({
            val files = BackupManager.listLocal(activity, treeUri)
            runOnUi(activity) {
                if (files.isEmpty()) {
                    toast(activity, B.NO_BACKUPS())
                } else {
                    ChoiceDialog.show(
                        activity,
                        B.CHOOSE_LOCAL(),
                        files.map { localLabel(it) },
                        0
                    ) { index ->
                        val file = files.getOrNull(index) ?: return@show
                        Thread({
                            val json = BackupManager.readLocal(activity, file.uri)
                            runOnUi(activity) {
                                if (json == null) {
                                    toast(activity, B.CANNOT_READ())
                                } else {
                                    handleRestorePayload(activity, B.LOCAL_SOURCE(file.name), json)
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
            toast(activity, C.CONFIG_NOT_READY())
            return
        }
        val endpoint = endpointOrNull(activity) ?: return
        val username = configText(ConfigKeys.WEBDAV_USERNAME)
        val password = configText(ConfigKeys.WEBDAV_PASSWORD)
        toast(activity, B.UPLOADING())
        Thread({
            val name = BackupManager.fileName()
            val data = try {
                BackupManager.createPayload().toByteArray(Charsets.UTF_8)
            } catch (t: Throwable) {
                ModuleLog.e(TAG, "create encrypted payload failed", t)
                runOnUi(activity) { toast(activity, B.ENCRYPTION_FAILED()) }
                return@Thread
            }
            val result = WebDavBackup.upload(
                store = store,
                host = endpoint.host,
                port = endpoint.port,
                https = endpoint.https,
                username = username,
                password = password,
                fileName = name,
                data = data,
                directory = endpoint.directory
            )
            runOnUi(activity) { toast(activity, result.message) }
        }, "plp-backup-webdav-up").apply { isDaemon = true }.start()
    }

    private fun downloadWebDav(activity: Activity) {
        val store = Runtime.configOrNull()
        if (store == null) {
            toast(activity, C.CONFIG_NOT_READY())
            return
        }
        val endpoint = endpointOrNull(activity) ?: return
        val username = configText(ConfigKeys.WEBDAV_USERNAME)
        val password = configText(ConfigKeys.WEBDAV_PASSWORD)
        toast(activity, B.LOOKING_UP_REMOTE())
        Thread({
            val latest = WebDavBackup.latest(
                store = store,
                host = endpoint.host,
                port = endpoint.port,
                https = endpoint.https,
                username = username,
                password = password,
                directory = endpoint.directory
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
                if (info == null && !BackupManager.isEncryptedJson(body)) {
                    toast(activity, B.INVALID_REMOTE())
                } else {
                    handleRestorePayload(activity, B.REMOTE_SOURCE(remote.name), body)
                }
            }
        }, "plp-backup-webdav-down").apply { isDaemon = true }.start()
    }

    /**
     * 备份内容统一入口：明文直接进确认；加密信封先解密
     * （优先尝试本机保存的备份密码，失败或未设置时弹密码输入）。
     */
    private fun handleRestorePayload(activity: Activity, source: String, json: String) {
        if (!BackupManager.isEncryptedJson(json)) {
            val info = BackupManager.info(json)
            if (info == null) {
                toast(activity, B.CANNOT_READ())
                return
            }
            confirmRestore(activity, source, info) {
                restoreAsync(activity, json)
            }
            return
        }
        val stored = configText(ConfigKeys.BACKUP_PASSWORD)
        if (stored.isNotEmpty()) {
            attemptDecrypt(activity, source, json, stored)
            return
        }
        PasswordPromptDialog.show(activity, B.ENTER_PASSWORD()) { pwd ->
            attemptDecrypt(activity, source, json, pwd)
        }
    }

    private fun attemptDecrypt(activity: Activity, source: String, json: String, password: String) {
        toast(activity, B.DECRYPTING())
        Thread({
            val plain = try {
                BackupManager.decrypt(json, password.toCharArray())
            } catch (t: Throwable) {
                ModuleLog.w(TAG, "decrypt backup failed: ${t.javaClass.simpleName}")
                null
            }
            runOnUi(activity) {
                if (plain == null) {
                    toast(activity, B.DECRYPTION_FAILED())
                    PasswordPromptDialog.show(activity, B.ENTER_PASSWORD()) { pwd ->
                        attemptDecrypt(activity, source, json, pwd)
                    }
                } else {
                    handleRestorePayload(activity, source, plain)
                }
            }
        }, "plp-backup-decrypt").apply { isDaemon = true }.start()
    }

    private fun confirmRestore(
        activity: Activity,
        source: String,
        info: BackupManager.BackupInfo,
        onConfirm: () -> Unit
    ) {
        val message = B.RESTORE_DETAILS(source, I18n.dateTime(info.createdAt), info.moduleVersion,
            info.configCount, info.historyCount, info.authorCount)
        ConfirmDialog.show(activity, B.RESTORE_BACKUP(), message, B.RESTORE(), danger = true) {
            onConfirm()
        }
    }

    private fun restoreAsync(activity: Activity, json: String) {
        toast(activity, B.RESTORING())
        Thread({
            val result = BackupManager.restore(json)
            runOnUi(activity) {
                val message = if (result.ok) {
                    B.RESTORE_REFRESH(result.message)
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
        val uri = folderUri() ?: return B.PICK_FOLDER()
        val name = BackupManager.folderName(context, uri)
        return name ?: B.PICK_AGAIN()
    }

    private fun locationLabel(): String {
        val host = configText(ConfigKeys.WEBDAV_HOST)
        val port = configText(ConfigKeys.WEBDAV_PORT).trim().toIntOrNull()
        val https = configValue(ConfigKeys.WEBDAV_HTTPS)
        return if (port == null) {
            C.NOT_SET()
        } else {
            WebDavBackup.folderUrl(
                host,
                port,
                https,
                configText(ConfigKeys.WEBDAV_DIRECTORY)
            )?.toString() ?: C.NOT_SET()
        }
    }

    private fun refreshLocation(row: LinearLayout) {
        val value = row.getChildAt(1) as? TextView ?: return
        value.text = locationLabel()
    }

    private fun endpointOrNull(activity: Activity): Endpoint? {
        val host = WebDavBackup.normalizeHost(configText(ConfigKeys.WEBDAV_HOST))
        if (host.isNullOrEmpty()) {
            toast(activity, B.NEED_ADDRESS())
            return null
        }
        val port = configText(ConfigKeys.WEBDAV_PORT).trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            toast(activity, B.BAD_PORT())
            return null
        }
        return Endpoint(
            host,
            port,
            configValue(ConfigKeys.WEBDAV_HTTPS),
            configText(ConfigKeys.WEBDAV_DIRECTORY)
        )
    }

    private fun localLabel(file: BackupManager.LocalBackup): String {
        val time = if (file.modifiedAt > 0) {
            I18n.dateTime(file.modifiedAt)
        } else {
            B.UNKNOWN_TIME()
        }
        return "$time · ${formatSize(file.size)}\n${file.name}"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(I18n.language().locale, "%.1f KB", bytes / 1024.0)
            else -> String.format(I18n.language().locale, "%.2f MB", bytes / (1024.0 * 1024.0))
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

    private data class Endpoint(
        val host: String,
        val port: Int,
        val https: Boolean,
        val directory: String
    )
}
