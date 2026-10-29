package com.heihun.pixivplus.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.heihun.pixivplus.core.log.ModuleLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicReference

/**
 * 宿主 Activity 的 startActivityForResult / onActivityResult 桥接。
 *
 * 模块的设置面板是注入宿主 Activity 的 Dialog，自身没有 Activity，
 * 因此通过 hook Activity 的结果回调来接收系统文件夹选择器的返回值。
 */
object ActivityResultBridge {

    private const val TAG = "ActivityResultBridge"
    private const val REQUEST_PICK_FOLDER = 0x504C

    private val pending = AtomicReference<((Activity, Uri?) -> Unit)?>(null)

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        hookOnActivityResult()
        hookDispatchActivityResult()
    }

    /** 打开系统文件夹选择器；用户取消或失败时回调 null。 */
    fun pickFolder(activity: Activity, onPicked: (Activity, Uri?) -> Unit) {
        pending.set(onPicked)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            activity.startActivityForResult(intent, REQUEST_PICK_FOLDER)
        } catch (t: Throwable) {
            pending.set(null)
            ModuleLog.e(TAG, "start folder picker failed", t)
            onPicked(activity, null)
        }
    }

    private fun hookOnActivityResult() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val requestCode = param.args.getOrNull(0) as? Int ?: return
                        val resultCode = param.args.getOrNull(1) as? Int ?: return
                        val data = param.args.getOrNull(2) as? Intent
                        handle(param.thisObject as? Activity ?: return, requestCode, resultCode, data)
                    }
                }
            )
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "hook onActivityResult failed: ${t.message}")
        }
    }

    private fun hookDispatchActivityResult() {
        try {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "dispatchActivityResult",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.size < 4) return
                        val requestCode = param.args[1] as? Int ?: return
                        val resultCode = param.args[2] as? Int ?: return
                        val data = param.args[3] as? Intent
                        handle(param.thisObject as? Activity ?: return, requestCode, resultCode, data)
                    }
                }
            )
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "hook dispatchActivityResult failed: ${t.message}")
        }
    }

    private fun handle(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_PICK_FOLDER) return
        val callback = pending.getAndSet(null) ?: return
        var uri: Uri? = null
        if (resultCode == Activity.RESULT_OK) {
            val intent = data
            if (intent != null) {
                uri = intent.data
                if (uri != null) takePersistablePermission(activity, uri, intent.flags)
            }
        }
        try {
            callback(activity, uri)
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "folder picker callback failed", t)
        }
    }

    private fun takePersistablePermission(activity: Activity, uri: Uri, flags: Int) {
        val resolver = activity.contentResolver
        val takeFlags = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        try {
            resolver.takePersistableUriPermission(
                uri,
                if (takeFlags != 0) takeFlags else
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (t: Throwable) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (retry: Throwable) {
                ModuleLog.w(
                    TAG,
                    "persist folder permission failed: ${retry.javaClass.simpleName}: ${retry.message}"
                )
            }
        }
    }
}
