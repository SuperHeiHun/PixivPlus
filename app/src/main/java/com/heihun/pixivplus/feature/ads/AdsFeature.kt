package com.heihun.pixivplus.feature.ads

import android.view.View
import android.view.ViewGroup
import com.heihun.pixivplus.adapt.ResolveRegistry
import com.heihun.pixivplus.adapt.ResolveStatus
import com.heihun.pixivplus.adapt.anchors.AdAnchors
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class AdsFeature : ConfigurableFeature("ads", "广告移除", ConfigKeys.ADS_REMOVAL) {

    private val hooked = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val containerNames = HashSet(AdAnchors.adContainerClasses)

    @Volatile
    private var adsEnabled = false

    @Volatile
    private var hideHookInstalled = false

    override fun hook(host: Host) {
        adsEnabled = isEnabled(ConfigStoreRegistry.store)
        hookAdgStart(host)
        hookAdViewSetup(host)
        hookSelfServeModel(host)
        hookDetailAdItem(host)
        hookAdContainerHiding(host)
        ModuleLog.i(displayName, "hooks installed (behavior ${if (adsEnabled) "enabled" else "disabled"})")
    }

    override fun onRuntimeReady(host: Host) {
        hookOptOutSwitch(host)
        hookYufulightFunnel(host)
    }

    override fun onConfigChanged(host: Host) {
        adsEnabled = isEnabled(ConfigStoreRegistry.store)
        ModuleLog.i(displayName, "广告移除已${if (adsEnabled) "开启" else "关闭"}（即时生效）")
    }

    private fun hookAdgStart(host: Host) {
        val clazz = ReflectUtil.loadClass(host.classLoader, AdAnchors.ADG_CLASS)
        if (clazz == null) {
            ModuleLog.w(displayName, "ADG class not found")
            return
        }
        val method = ReflectUtil.findMethod(clazz, "start")
        if (method == null) {
            ModuleLog.w(displayName, "ADG.start not found")
            return
        }
        if (hookNoOp(method)) {
            ModuleLog.i(displayName, "blocked ${clazz.name}.start")
        }
    }

    private fun hookAdViewSetup(host: Host) {
        var blocked = 0
        for ((className, methodName) in AdAnchors.adViewSetupTargets) {
            val clazz = ReflectUtil.loadClass(host.classLoader, className) ?: continue
            for (method in ReflectUtil.findMethods(clazz, methodName, 1)) {
                if (hookNoOp(method)) {
                    blocked++
                    ModuleLog.i(displayName, "blocked ${clazz.name}.${method.name}")
                }
            }
        }
        val overlay = ReflectUtil.loadClass(
            host.classLoader,
            "jp.pxv.android.feature.advertisement.view.OverlayAppLovinView"
        )
        if (overlay != null) {
            for (method in ReflectUtil.findMethods(overlay, "c", 2)) {
                if (method.parameterTypes[0] == String::class.java && method.parameterTypes[1] == String::class.java) {
                    if (hookNoOp(method)) {
                        blocked++
                        ModuleLog.i(displayName, "blocked ${overlay.name}.${method.name}")
                    }
                }
            }
        }
        if (blocked == 0) ModuleLog.w(displayName, "no ad view setup method resolved")
    }

    private fun hookSelfServeModel(host: Host) {
        val clazz = ReflectUtil.loadClass(host.classLoader, AdAnchors.SELF_SERVE_MODEL_CLASS)
        if (clazz == null) {
            ModuleLog.w(displayName, "self serve model not found")
            return
        }
        for (name in listOf("getAdImageUrl", "getClickUrl")) {
            val method = ReflectUtil.findMethod(clazz, name) ?: continue
            if (!hooked.add(method)) continue
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!adsEnabled) return
                        param.result = ""
                    }
                })
                ModuleLog.i(displayName, "neutralized ${clazz.name}.${name}")
            } catch (t: Throwable) {
                hooked.remove(method)
                ModuleLog.w(displayName, "hook ${clazz.name}.${name} failed", t)
            }
        }
    }

    private fun hookDetailAdItem(host: Host) {
        val clazz = ReflectUtil.loadClass(host.classLoader, AdAnchors.DETAIL_AD_ITEM_CLASS)
        if (clazz == null) {
            ModuleLog.w(displayName, "detail ad item not found")
            return
        }
        val method = ReflectUtil.findMethod(clazz, "show")
        if (method == null) return
        if (hookNoOp(method)) {
            ModuleLog.i(displayName, "blocked ${clazz.name}.show")
        }
    }

    private fun hookAdContainerHiding(host: Host) {
        if (hideHookInstalled) return
        hideHookInstalled = true
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (!adsEnabled) return
                        if (!containerNames.contains(view.javaClass.name)) return
                        hideAdTree(view)
                    }
                }
            )
            ModuleLog.i(displayName, "ad containers hidden on attach (${containerNames.size} classes)")
        } catch (t: Throwable) {
            ModuleLog.e(displayName, "container hiding hook failed", t)
        }
    }

    private fun hideAdTree(view: View) {
        try {
            view.visibility = View.GONE
            val parent = view.parent as? ViewGroup ?: return
            if (parent.visibility == View.GONE) return
            val parentName = parent.javaClass.name
            if (parentName == "androidx.recyclerview.widget.RecyclerView") return
            if (parentName.contains("ViewPager")) return
            parent.visibility = View.GONE
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "hide ad container failed", t)
        }
    }

    private fun hookOptOutSwitch(host: Host) {
        val refs = ResolveRegistry.dexKit()
            ?.findMethodsUsingString(AdAnchors.OPT_OUT_PREF, limit = 8)
            .orEmpty()
        var count = 0
        val signatures = ArrayList<String>()
        for (ref in refs) {
            val method = ref.resolve(host.classLoader) ?: continue
            if (method.parameterCount != 0) continue
            if (method.returnType != Boolean::class.javaPrimitiveType) continue
            if (!hooked.add(method)) continue
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!adsEnabled) return
                        param.result = true
                    }
                })
                count++
                signatures.add(ref.signature())
                ModuleLog.i(displayName, "opt-out forced on ${ref.signature()}")
            } catch (t: Throwable) {
                hooked.remove(method)
                ModuleLog.w(displayName, "opt-out hook failed for ${ref.signature()}", t)
            }
        }
        if (count == 0) {
            count = hookPreferenceOptOut(host)
            if (count > 0) signatures.add("SharedPreferences.getBoolean(${AdAnchors.OPT_OUT_PREF})")
        }
        report(AdAnchors.ID_OPT_OUT, AdAnchors.DESC_OPT_OUT, count, signatures)
    }

    private fun hookPreferenceOptOut(host: Host): Int {
        val clazz = ReflectUtil.loadClass(host.classLoader, "android.app.SharedPreferencesImpl") ?: return 0
        val method = ReflectUtil.findMethods(clazz, "getBoolean").firstOrNull { it.parameterTypes.size == 2 }
            ?: return 0
        if (!hooked.add(method)) return 0
        return try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = param.args?.getOrNull(0) as? String ?: return
                    if (!adsEnabled) return
                    if (key == AdAnchors.OPT_OUT_PREF) {
                        param.result = true
                    }
                }
            })
            ModuleLog.i(displayName, "opt-out forced via SharedPreferences.getBoolean")
            1
        } catch (t: Throwable) {
            hooked.remove(method)
            ModuleLog.w(displayName, "preference opt-out hook failed", t)
            0
        }
    }

    private fun hookYufulightFunnel(host: Host) {
        val refs = ResolveRegistry.dexKit()
            ?.findMethodsUsingString(
                literal = AdAnchors.REQUEST_LOG,
                methodName = "b",
                paramCount = 3,
                limit = 4
            )
            .orEmpty()
        var count = 0
        val signatures = ArrayList<String>()
        for (ref in refs) {
            val method = ref.resolve(host.classLoader) ?: continue
            if (hookNoOp(method)) {
                count++
                signatures.add(ref.signature())
                ModuleLog.i(displayName, "ad request blocked at ${ref.signature()}")
            }
        }
        if (count == 0) {
            count = hookActionCreatorFallback(host)
        }
        report(AdAnchors.ID_FUNNEL, AdAnchors.DESC_FUNNEL, count, signatures)
    }

    private fun hookActionCreatorFallback(host: Host): Int {
        var installed = 0
        for (className in AdAnchors.actionCreatorViews) {
            val clazz = ReflectUtil.loadClass(host.classLoader, className) ?: continue
            val method = ReflectUtil.findMethod(clazz, "getActionCreator\$advertisement") ?: continue
            if (!hooked.add(method)) continue
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val creator = param.result ?: return
                        for (candidate in ReflectUtil.findMethods(creator.javaClass, "b", 3)) {
                            if (hookNoOp(candidate)) {
                                ModuleLog.i(displayName, "ad request blocked via ${candidate.name}")
                            }
                        }
                    }
                })
                installed++
            } catch (t: Throwable) {
                hooked.remove(method)
                ModuleLog.w(displayName, "action creator fallback failed for $className", t)
            }
        }
        if (installed > 0) {
            ModuleLog.i(displayName, "action creator fallback installed on $installed view(s)")
        }
        return installed
    }

    private fun hookNoOp(method: Method): Boolean {
        if (!hooked.add(method)) return false
        return try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!adsEnabled) return
                    param.result = null
                }
            })
            true
        } catch (t: Throwable) {
            hooked.remove(method)
            ModuleLog.w(displayName, "hook ${method.name} failed", t)
            false
        }
    }

    private fun report(id: String, description: String, count: Int, signatures: List<String>) {
        val status = if (count > 0) {
            ResolveStatus.Resolved("$count hook(s): ${signatures.joinToString(", ")}", "dexkit/runtime")
        } else {
            ResolveStatus.Failed("ad hook target not resolved")
        }
        ResolveRegistry.report(id, description, status)
    }
}
