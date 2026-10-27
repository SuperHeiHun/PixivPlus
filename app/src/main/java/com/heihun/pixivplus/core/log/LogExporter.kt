package com.heihun.pixivplus.core.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExporter {

    const val DIRECTORY_NAME = "pixiv_local_plus"

    fun export(context: Context, prefix: String = "log"): String? {
        return try {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, DIRECTORY_NAME)
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "$prefix-$stamp.txt")
            file.writeText(ModuleLog.dump().joinToString("\n"))
            file.absolutePath
        } catch (t: Throwable) {
            ModuleLog.e("LogExporter", "export failed", t)
            null
        }
    }
}
