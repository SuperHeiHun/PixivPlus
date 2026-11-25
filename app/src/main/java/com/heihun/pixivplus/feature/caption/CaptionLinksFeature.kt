package com.heihun.pixivplus.feature.caption

import android.view.View
import android.widget.TextView
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import com.heihun.pixivplus.ui.CaptionLinkifier
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap

class CaptionLinksFeature : ConfigurableFeature("caption_links", "简介链接点击", ConfigKeys.CAPTION_LINKS_ENABLED) {
    private val captions = WeakHashMap<TextView, String>()

    override fun hook(host: Host) {
        val clazz = ReflectUtil.loadClass(host.classLoader,
            "jp.pxv.android.feature.component.androidview.DetailCaptionAndTagsView") ?: return
        val render = ReflectUtil.findMethod(clazz, "c", String::class.java)?.takeIf { it.returnType == Void.TYPE }
            ?: clazz.declaredMethods.singleOrNull { it.parameterCount == 1 && it.parameterTypes[0] == String::class.java && it.returnType == Void.TYPE }
            ?: return
        XposedBridge.hookMethod(render, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val html = param.args[0] as? String ?: return
                    if (html.isEmpty()) return
                    val view = param.thisObject as? View ?: return
                    val id = view.resources.getIdentifier("caption_text_view", "id", Host.PACKAGE)
                    val textView = if (id != 0) view.findViewById<TextView>(id) else null
                    val target = textView ?: ReflectUtil.readField(ReflectUtil.readField(view, "e"), "e") as? TextView ?: return
                    captions[target] = html
                    CaptionLinkifier.apply(target, html, isEnabled(ConfigStoreRegistry.store))
                } catch (t: Exception) {
                    ModuleLog.w(displayName, "could not enhance rendered caption: ${t.javaClass.simpleName}")
                }
            }
        })
        ModuleLog.i(displayName, "illust/novel caption link render hook installed")
    }

    override fun onConfigChanged(host: Host) {
        for ((view, html) in captions.toMap()) view.post {
            if (captions[view] == html) CaptionLinkifier.apply(view, html, isEnabled(ConfigStoreRegistry.store))
        }
    }
}
