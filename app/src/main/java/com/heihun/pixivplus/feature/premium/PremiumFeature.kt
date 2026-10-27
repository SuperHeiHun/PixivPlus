package com.heihun.pixivplus.feature.premium

import com.heihun.pixivplus.adapt.ResolveRegistry
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.ApiHooks
import com.heihun.pixivplus.core.net.PayloadProcessor
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class PremiumFeature : ConfigurableFeature("premium", "本地 Premium 覆盖", ConfigKeys.PREMIUM_OVERRIDE) {

    private val hooked = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())

    @Volatile
    private var premiumEnabled = false

    private val premiumEntities = listOf(
        "jp.pxv.android.data.userstate.remote.dto.ProfileApiModel",
        "jp.pxv.android.domain.auth.entity.OAuthUser",
        "jp.pxv.android.domain.commonentity.PixivProfile"
    )

    override fun hook(host: Host) {
        premiumEnabled = isEnabled(ConfigStoreRegistry.store)
        PayloadProcessor.premiumEnabled = premiumEnabled
        ApiHooks.ensureInstalled(host)
        ModuleLog.i(displayName, "premium payload rewrite ready (client side only, ${if (premiumEnabled) "enabled" else "disabled"})")
    }

    override fun onRuntimeReady(host: Host) {
        val refs = ResolveRegistry.dexKit()
            ?.findPremiumFlagReaders(premiumEntities, "is_premium")
            .orEmpty()
        var count = 0
        for (ref in refs) {
            val method = ref.resolve(host.classLoader) ?: continue
            if (method.returnType != Boolean::class.javaPrimitiveType) continue
            if (hookTrue(method)) count++
        }
        if (count > 0) {
            ModuleLog.i(displayName, "premium getter hooks installed ($count): ${refs.take(6).joinToString { it.signature() }}")
        } else {
            ModuleLog.w(displayName, "premium getter not resolved, payload rewrite remains active")
        }
    }

    override fun onConfigChanged(host: Host) {
        premiumEnabled = isEnabled(ConfigStoreRegistry.store)
        PayloadProcessor.premiumEnabled = premiumEnabled
        ModuleLog.i(displayName, "Premium 覆盖已${if (premiumEnabled) "开启" else "关闭"}（即时生效）")
    }

    private fun hookGetterByField(host: Host, className: String, fieldName: String): Int {
        val resolver = ResolveRegistry.dexKit() ?: return 0
        val refs = resolver.findMethodsUsingField(className, fieldName)
        var count = 0
        for (ref in refs) {
            val method = ref.resolve(host.classLoader) ?: continue
            if (method.parameterCount != 0) continue
            if (method.returnType != Boolean::class.javaPrimitiveType) continue
            if (hookTrue(method)) count++
        }
        return count
    }

    private fun hookTrue(method: Method): Boolean {
        if (!hooked.add(method)) return false
        return try {
            XposedBridge.hookMethod(method, object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!premiumEnabled) return
                    param.result = true
                }
            })
            ModuleLog.i(displayName, "forced ${method.declaringClass.name}.${method.name} = true")
            true
        } catch (t: Throwable) {
            hooked.remove(method)
            ModuleLog.w(displayName, "premium hook failed for ${method.name}", t)
            false
        }
    }
}
