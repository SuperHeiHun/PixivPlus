package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把模块代理注入宿主 App 的 OkHttp 客户端。
 *
 * hook `okhttp3.OkHttpClient.Builder.build()`，在构建前写入 `proxy` 与
 * `proxyAuthenticator`，App 内后创建的客户端（含 `OkHttpClient()` 默认构造）
 * 都会走模块代理；HTTP 代理需要认证时用动态代理实现 407 应答。
 */
object AppProxyOkhttp {

    private const val TAG = "AppProxyOkhttp"
    private const val BUILDER_CLASS = "okhttp3.OkHttpClient\$Builder"
    private const val AUTHENTICATOR_CLASS = "okhttp3.Authenticator"
    private const val REQUEST_BUILDER_CLASS = "okhttp3.Request\$Builder"

    private val installed = AtomicBoolean(false)

    fun ensureInstalled(host: Host) {
        if (!installed.compareAndSet(false, true)) return
        val builderClass = ReflectUtil.loadClass(host.classLoader, BUILDER_CLASS)
        if (builderClass == null) {
            ModuleLog.w(TAG, "OkHttpClient.Builder not found, app okhttp proxy skipped")
            installed.set(false)
            return
        }
        val buildMethod = ReflectUtil.findMethods(builderClass, "build")
            .firstOrNull { it.parameterCount == 0 }
        if (buildMethod == null) {
            ModuleLog.w(TAG, "OkHttpClient.Builder.build not found, app okhttp proxy skipped")
            installed.set(false)
            return
        }
        try {
            XposedBridge.hookMethod(buildMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val builder = param.thisObject ?: return
                    inject(builder)
                }
            })
            ModuleLog.i(TAG, "okhttp builder hook installed")
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "hook okhttp builder failed", t)
            installed.set(false)
        }
    }

    private fun inject(builder: Any) {
        try {
            val store = Runtime.configOrNull() ?: return
            if (!store.get(ConfigKeys.APP_PROXY_ENABLED)) return
            val spec = ProxySettings.current(store)
            val proxy = AppProxy.proxyFor(spec) ?: return
            XposedHelpers.setObjectField(builder, "proxy", proxy)
            val authenticator = proxyAuthenticator(builder.javaClass.classLoader, spec)
            if (authenticator != null) {
                XposedHelpers.setObjectField(builder, "proxyAuthenticator", authenticator)
            }
            ModuleLog.d(TAG, "okhttp client proxied: ${spec.display()}")
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "inject okhttp proxy failed", t)
        }
    }

    /** HTTP 代理 407 应答器：OkHttp 的 Authenticator 用 JDK 动态代理实现。 */
    private fun proxyAuthenticator(loader: ClassLoader?, spec: ProxySpec): Any? {
        if (spec.mode != ProxyMode.HTTP) return null
        val credential = AppProxy.basicCredentials(spec) ?: return null
        val iface = ReflectUtil.loadClass(loader, AUTHENTICATOR_CLASS) ?: return null
        if (!iface.isInterface) return null
        return try {
            java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(iface)) { _, method, args ->
                when {
                    method.name == "authenticate" -> authenticate(args, credential)
                    method.returnType == Boolean::class.javaPrimitiveType -> false
                    method.returnType == Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "build proxy authenticator failed", t)
            null
        }
    }

    /** 对 407 响应构造带 Proxy-Authorization 的新请求。 */
    private fun authenticate(args: Array<Any>?, credential: String): Any? {
        val response = args?.getOrNull(1) ?: return null
        val request = ReflectUtil.invoke(
            response,
            ReflectUtil.findMethod(response.javaClass, "request")
        ) ?: return null
        val builderClass = ReflectUtil.loadClass(request.javaClass.classLoader, REQUEST_BUILDER_CLASS)
            ?: return null
        val builder = try {
            builderClass.getDeclaredConstructor().newInstance()
        } catch (_: Throwable) {
            return null
        }
        ReflectUtil.invoke(
            builder,
            ReflectUtil.findMethod(builderClass, "header", String::class.java, String::class.java),
            "Proxy-Authorization",
            credential
        )
        return ReflectUtil.invoke(builder, ReflectUtil.findMethod(builderClass, "build"))
    }
}
