package com.heihun.pixivplus.core.task

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.heihun.pixivplus.core.i18n.TaskText as T
import com.heihun.pixivplus.core.media.SavedFile
import com.heihun.pixivplus.ui.panel.ChoiceDialog
import java.util.concurrent.Executors

object TaskFileOpener {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "plp-open-file").apply { isDaemon = true }
    }

    fun show(activity: Activity, files: List<SavedFile>) {
        val valid = files.filter { it.isValid() }
        if (valid.size == 1) open(activity, valid.first())
        else if (valid.isNotEmpty()) ChoiceDialog.show(activity, T.OPEN_FILE(), valid.map { it.name }, -1) { index ->
            valid.getOrNull(index)?.let { open(activity, it) }
        }
    }

    private fun open(activity: Activity, file: SavedFile) {
        worker.execute {
            val uri = Uri.parse(file.uri)
            val readable = try {
                activity.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            } catch (_: Exception) { false }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!readable) { toast(activity, T.FILE_UNAVAILABLE()); return@runOnUiThread }
                try {
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    view.clipData = ClipData.newRawUri(file.name, uri)
                    activity.startActivity(Intent.createChooser(view, T.OPEN_FILE()))
                } catch (_: ActivityNotFoundException) { toast(activity, T.NO_FILE_VIEWER()) }
                catch (_: Exception) { toast(activity, T.OPEN_FILE_FAILED()) }
            }
        }
    }

    private fun toast(activity: Activity, text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
}
