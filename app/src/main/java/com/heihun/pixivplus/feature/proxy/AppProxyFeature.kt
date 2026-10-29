package com.heihun.pixivplus.feature.proxy

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.AppProxy
import com.heihun.pixivplus.core.net.AppProxyOkhttp
import com.heihun.pixivplus.core.net.ProxyMode
import com.heihun.pixivplus.core.net.ProxySettings
import com.heihun.pixivplus.feature.FeatureModule

/**
 * 让宿主 App 的网络请求也走模块配置的代理。
 *
 * OkHttp 客户端在 `OkHttpClient.Builder.build()` 前注入 proxy，
 * 其余请求通过进程级 ProxySelector 接管；「跟随系统」模式不接管。
 */
class AppProxyFeature : FeatureModule {

    override val id = "app_proxy"

    override val displayName = "App 代理"

    override fun init(host: Host) {
    }

    override fun hook(host: Host) {
        AppProxyOkhttp.ensureInstalled(host)
        apply()
    }

    override fun onConfigChanged(host: Host) {
        apply()
    }

    private fun apply() {
        try {
            val store = Runtime.configOrNull() ?: return
            val enabled = store.get(ConfigKeys.APP_PROXY_ENABLED)
            val spec = ProxySettings.current(store)
            if (!enabled || spec.mode == ProxyMode.SYSTEM) {
                if (AppProxy.isInstalled()) {
                    AppProxy.restore()
                }
            } else {
                AppProxy.install(spec)
            }
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "apply app proxy failed", t)
        }
    }

    private companion object {
        const val TAG = "AppProxyFeature"
    }
}
