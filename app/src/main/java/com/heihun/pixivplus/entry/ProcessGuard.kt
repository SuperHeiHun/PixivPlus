package com.heihun.pixivplus.entry

import android.app.Application
import android.os.Build
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog

object ProcessGuard {

    fun processName(application: Application): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                currentProcessNameReflective(application)
            }
        } catch (t: Throwable) {
            ModuleLog.w("ProcessGuard", "process name lookup failed", t)
            ""
        }
    }

    fun isMainProcess(processName: String): Boolean {
        if (processName.isEmpty()) return true
        if (processName == Host.PACKAGE) return true
        return !processName.startsWith(Host.PACKAGE + ":")
    }

    private fun currentProcessNameReflective(application: Application): String {
        val activityThread = Class.forName("android.app.ActivityThread")
        val method = activityThread.getDeclaredMethod("currentProcessName")
        return (method.invoke(null) as? String) ?: application.javaClass.name
    }
}
