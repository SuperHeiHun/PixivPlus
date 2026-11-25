package com.heihun.pixivplus.feature.slim

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.DrawerText as D
import com.heihun.pixivplus.core.i18n.SettingsText as S
import com.heihun.pixivplus.core.i18n.SlimText as Slim
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Modifier
import java.util.ArrayDeque

/** 仅针对首页底栏的 MyPage Compose，不处理 UserProfile 或按全局文本删除 View。 */
class MyPageSlimFeature : ConfigurableFeature("my_page_slim", "个人首页精简", ConfigKeys.MY_PAGE_SLIM_ENABLED) {
    companion object {
        internal val labels = linkedMapOf("premium" to Slim.PREMIUM, "upload" to Slim.UPLOAD,
            "works" to D.YOUR_WORKS, "collection" to D.BOOKMARKS, "history" to S.HISTORY,
            "marker" to D.NOVEL_MARKER, "following" to D.FOLLOWING, "follower" to D.FOLLOWERS,
            "mypixiv" to D.MY_PIXIV, "mute" to D.MUTE_SETTINGS, "help" to D.SUPPORT, "about" to D.ABOUT_PIXIV)
    }

    private data class Frame(val state: Any, val action: Any, val composer: Any, val hidden: Set<String>, val labels: Map<String, String>)
    private val frames = ThreadLocal<ArrayDeque<Frame>>()

    override fun hook(host: Host) {
        // 当前反编译确认的宿主映射；未来版本不猜混淆方法，宁可保留原界面。
        if (host.versionName != "6.192.1") {
            ModuleLog.w(displayName, "unsupported host mapping; original MyPage retained")
            return
        }
        val clazz = ReflectUtil.loadClass(host.classLoader, "qw1") ?: return
        val root = clazz.declaredMethods.singleOrNull { it.name == "g" && it.parameterCount == 6 &&
            it.returnType == Void.TYPE && Modifier.isStatic(it.modifiers) } ?: return
        val stateType = root.parameterTypes[1]
        val getter = ReflectUtil.findMethod(stateType, "getUser") ?: return
        if (getter.returnType.name != "jp.pxv.android.feature.mypage.model.MyPageUser") return
        val row = clazz.declaredMethods.singleOrNull { it.name == "h" && it.parameterCount == 8 &&
            it.returnType == Void.TYPE && Modifier.isStatic(it.modifiers) &&
            it.parameterTypes[1] == Integer::class.java && it.parameterTypes[2] == String::class.java &&
            it.parameterTypes[5] == root.parameterTypes[4] } ?: return
        val premium = clazz.declaredMethods.singleOrNull { it.name == "j" && it.parameterCount == 5 &&
            it.returnType == Void.TYPE && Modifier.isStatic(it.modifiers) &&
            it.parameterTypes[1] == stateType && it.parameterTypes[3] == root.parameterTypes[4] } ?: return

        XposedBridge.hookMethod(root, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = Host.context() ?: return
                val translated = MyPageSlimPolicy.rowResources.mapNotNull { (key, resource) ->
                    val id = context.resources.getIdentifier(resource, "string", Host.PACKAGE)
                    if (id == 0) null else key to context.getString(id)
                }.toMap()
                val store = ConfigStoreRegistry.store
                val stack = frames.get() ?: ArrayDeque<Frame>().also { frames.set(it) }
                stack.addLast(Frame(param.args[1], param.args[2], param.args[4],
                    MyPageSlimPolicy.hidden(isEnabled(store), BottomSlimPolicy.parse(store.get(ConfigKeys.MY_PAGE_SLIM_ITEMS))), translated))
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val stack = frames.get() ?: return
                if (stack.isNotEmpty()) stack.removeLast()
                if (stack.isEmpty()) frames.remove()
            }
        })
        XposedBridge.hookMethod(row, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val frame = frames.get()?.peekLast() ?: return
                if (frame.composer !== param.args[5] || frame.hidden.isEmpty()) return
                val icon = (param.args[1] as? Int)?.let { id ->
                    try { Host.context()?.resources?.getResourceEntryName(id) } catch (_: Exception) { null }
                }
                val key = MyPageSlimPolicy.rowKey(icon, param.args[2] as? String ?: return, frame.labels)
                if (key != null && key in frame.hidden) param.result = null
            }
        })
        XposedBridge.hookMethod(premium, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val frame = frames.get()?.peekLast() ?: return
                if (frame.state === param.args[1] && frame.composer === param.args[3] && "premium" in frame.hidden) {
                    param.result = null
                }
            }
        })
        // 投稿按钮是独立 Compose Button，双重校验其内容 lambda 和所属 MyPage action。
        val buttonClass = ReflectUtil.loadClass(host.classLoader, "mq0")
        val uploadContent = ReflectUtil.loadClass(host.classLoader, "c22")?.let { ReflectUtil.readStaticField(it, "g") }
        val button = buttonClass?.declaredMethods?.singleOrNull { it.name == "a" && it.parameterCount == 12 &&
            it.returnType == Void.TYPE && it.parameterTypes[9] == root.parameterTypes[4] }
        if (button != null && uploadContent != null) XposedBridge.hookMethod(button, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val frame = frames.get()?.peekLast() ?: return
                if ("upload" !in frame.hidden || frame.composer !== param.args[9] || param.args[8] !== uploadContent) return
                val action = param.args[0] ?: return
                if (ReflectUtil.readField(action, "b") === frame.action && ReflectUtil.readField(action, "a") == 20) {
                    param.result = null
                }
            }
        })
        ModuleLog.i(displayName, "MyPage-only Compose row/premium/upload hooks installed")
    }
}
