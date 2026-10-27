package com.heihun.pixivplus.core

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

object Host {

    const val PACKAGE = "jp.pxv.android"

    @Volatile
    var application: Application? = null

    @Volatile
    var processName: String = ""

    @Volatile
    var versionCode: Long = 0L

    @Volatile
    var versionName: String = ""

    @Volatile
    var isMainProcess: Boolean = false

    val classLoader: ClassLoader
        get() = application?.classLoader ?: ClassLoader.getSystemClassLoader()

    val apkPath: String
        get() = application?.applicationInfo?.sourceDir ?: ""

    fun context(): Context? = application

    fun initialize(app: Application, process: String, mainProcess: Boolean) {
        application = app
        processName = process
        isMainProcess = mainProcess
        try {
            val manager = app.packageManager
            val info: PackageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(Host.PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(Host.PACKAGE, 0)
            }
            versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            versionName = info.versionName ?: ""
        } catch (t: Throwable) {
            versionCode = 0L
            versionName = "unknown"
        }
    }
}
