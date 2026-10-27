package com.heihun.pixivplus.core.util

import android.content.Context
import android.os.Build
import com.heihun.pixivplus.core.log.ModuleLog
import java.io.File
import java.util.zip.ZipFile

object NativeLibLoader {

    private const val TAG = "NativeLib"
    private const val MODULE_PACKAGE = "com.heihun.pixivplus"
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    private val loaded = HashSet<String>()

    @Synchronized
    fun ensureDexKit(context: Context): Boolean {
        if (loaded.contains("dexkit")) return true
        return try {
            val apkPath = moduleApkPath(context)
            if (apkPath == null) {
                ModuleLog.w(TAG, "module apk path unavailable")
                return false
            }
            val abi = Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis }
            if (abi == null) {
                ModuleLog.w(TAG, "no supported abi: ${Build.SUPPORTED_ABIS.joinToString()}")
                return false
            }
            val entry = "lib/$abi/libdexkit.so"
            val target = File(context.cacheDir, "plp_libs/libdexkit_$abi.so")
            if (!target.exists() || target.length() == 0L) {
                target.parentFile?.mkdirs()
                ZipFile(apkPath).use { zip ->
                    val zipEntry = zip.getEntry(entry) ?: run {
                        ModuleLog.w(TAG, "entry $entry not found in $apkPath")
                        return false
                    }
                    zip.getInputStream(zipEntry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            System.load(target.absolutePath)
            loaded.add("dexkit")
            ModuleLog.i(TAG, "dexkit native loaded ($entry -> ${target.absolutePath})")
            true
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "dexkit native load failed", t)
            false
        }
    }

    private fun moduleApkPath(context: Context): String? {
        fromClassLoader()?.let { return it }
        return fromPackageManager(context)
    }

    private fun fromClassLoader(): String? {
        return try {
            val loader = NativeLibLoader::class.java.classLoader ?: return null
            val resource = loader.getResource("assets/xposed_init") ?: return null
            val text = resource.toString()
            val marker = "jar:file://"
            if (!text.startsWith(marker)) return null
            val rest = text.substring(marker.length)
            val end = rest.indexOf("!/")
            if (end <= 0) return null
            rest.substring(0, end)
        } catch (_: Throwable) {
            null
        }
    }

    private fun fromPackageManager(context: Context): String? {
        return try {
            val info = context.packageManager.getPackageInfo(MODULE_PACKAGE, 0)
            info.applicationInfo?.sourceDir
        } catch (_: Throwable) {
            null
        }
    }
}
