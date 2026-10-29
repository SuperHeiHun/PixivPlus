package com.heihun.pixivplus.entry

import android.app.Application
import android.content.Context
import com.heihun.pixivplus.adapt.ResolveCache
import com.heihun.pixivplus.adapt.ResolveRegistry
import com.heihun.pixivplus.adapt.anchors.AdAnchors
import com.heihun.pixivplus.adapt.anchors.SettingsAnchors
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.config.ConfigManager
import com.heihun.pixivplus.core.db.AuthorProfileDao
import com.heihun.pixivplus.core.db.HistoryDao
import com.heihun.pixivplus.core.db.LocalDatabase
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.FeatureRegistry
import com.heihun.pixivplus.ui.ActivityResultBridge
import com.heihun.pixivplus.ui.inject.SettingsInjector
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.Executors

object InitCoordinator {

    private const val TAG = "Init"
    private const val CONFIG_PREFS = "pixiv_local_plus_config"

    @Volatile
    private var initialized = false

    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "plp-init").apply { isDaemon = true }
    }

    fun install(lpParam: XC_LoadPackage.LoadPackageParam) {
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val application = param.thisObject as? Application ?: return
                onApplicationReady(application)
            }
        }

        try {
            XposedBridge.hookAllMethods(Application::class.java, "attachBaseContext", callback)
            XposedBridge.hookAllMethods(Application::class.java, "onCreate", callback)
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "hook framework Application failed", t)
        }

        try {
            val appClass = lpParam.classLoader.loadClass(SettingsAnchors.APPLICATION_CLASS)
            XposedBridge.hookAllMethods(appClass, "attachBaseContext", callback)
            XposedBridge.hookAllMethods(appClass, "onCreate", callback)
            ModuleLog.i(TAG, "hooked application class ${appClass.name}")
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "application class hook skipped: ${t.message}")
        }
    }

    private fun onApplicationReady(application: Application) {
        if (initialized) return
        initialized = true
        try {
            val process = ProcessGuard.processName(application)
            val mainProcess = ProcessGuard.isMainProcess(process)
            Host.initialize(application, process, mainProcess)
            ModuleLog.i(TAG, "init process=$process mainProcess=$mainProcess version=${Host.versionName}(${Host.versionCode})")

            val config = ConfigManager(application.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE))
            Runtime.config = config
            ConfigStoreRegistry.install(config)

            SettingsInjector.install()
            ActivityResultBridge.install()
            FeatureRegistry.installAll(Host, config)

            background.execute {
                initDatabase()
                resolveAnchors(application)
                FeatureRegistry.notifyRuntimeReady(Host)
            }
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "initialization failed", t)
        }
    }

    private fun initDatabase() {
        if (!Host.isMainProcess) {
            ModuleLog.i(TAG, "secondary process: database disabled")
            return
        }
        try {
            val application = Host.application ?: return
            val database = LocalDatabase(application)
            Runtime.database = database
            Runtime.databasePath = database.writableDatabase.path
            Runtime.historyDao = HistoryDao(database)
            Runtime.authorProfileDao = AuthorProfileDao(database)
            ModuleLog.i(TAG, "database ready: ${Runtime.databasePath}")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "database init failed", t)
        }
    }

    private fun resolveAnchors(application: Application) {
        try {
            ResolveRegistry.initialize(Host, ResolveCache(application))
            ResolveRegistry.resolveAll(Host, SettingsAnchors.specs + AdAnchors.specs)
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "anchor resolution failed", t)
        }
    }
}
