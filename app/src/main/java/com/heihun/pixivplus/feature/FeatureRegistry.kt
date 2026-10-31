package com.heihun.pixivplus.feature

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.config.ConfigManager
import com.heihun.pixivplus.core.log.ModuleLog

object FeatureRegistry {

    private val features = ArrayList<FeatureModule>()
    private val watchedKeys = ArrayList<com.heihun.pixivplus.core.config.ConfigKey<*>>()

    fun installAll(host: Host, config: ConfigManager) {
        features.clear()
        watchedKeys.clear()

        features.add(com.heihun.pixivplus.feature.ads.AdsFeature())
        features.add(com.heihun.pixivplus.feature.filter.FilterFeature())
        features.add(com.heihun.pixivplus.feature.blockmenu.DetailMenuFeature())
        features.add(com.heihun.pixivplus.feature.history.HistoryFeature())
        features.add(com.heihun.pixivplus.feature.history.BrowsingHistoryBridgeFeature())
        features.add(com.heihun.pixivplus.feature.premium.PremiumFeature())
        features.add(com.heihun.pixivplus.feature.caption.CaptionFixFeature())
        features.add(com.heihun.pixivplus.feature.proxy.AppProxyFeature())
        features.add(com.heihun.pixivplus.feature.novelsave.NovelSaveFeature())
        features.add(com.heihun.pixivplus.feature.translate.TranslationFeature())

        for (feature in features) {
            try {
                feature.init(host)
                feature.hook(host)
            } catch (t: Throwable) {
                ModuleLog.e("FeatureRegistry", "${feature.id} failed", t)
            }
        }

        watchedKeys.add(ConfigKeys.HISTORY_ENABLED)
        watchedKeys.add(ConfigKeys.HISTORY_BRIDGE_ENABLED)
        watchedKeys.add(ConfigKeys.FILTER_ENABLED)
        watchedKeys.add(ConfigKeys.PREMIUM_OVERRIDE)
        watchedKeys.add(ConfigKeys.ADS_REMOVAL)
        watchedKeys.add(ConfigKeys.CAPTION_FIX)
        watchedKeys.add(ConfigKeys.FILTER_KEYWORDS)
        watchedKeys.add(ConfigKeys.FILTER_TAGS)
        watchedKeys.add(ConfigKeys.FILTER_AUTHORS)
        watchedKeys.add(ConfigKeys.FILTER_CASE_SENSITIVE)
        watchedKeys.add(ConfigKeys.PROXY_MODE)
        watchedKeys.add(ConfigKeys.PROXY_HOST)
        watchedKeys.add(ConfigKeys.PROXY_PORT)
        watchedKeys.add(ConfigKeys.PROXY_USERNAME)
        watchedKeys.add(ConfigKeys.PROXY_PASSWORD)
        watchedKeys.add(ConfigKeys.APP_PROXY_ENABLED)
        watchedKeys.add(ConfigKeys.NOVEL_SAVE_ENABLED)
        watchedKeys.add(ConfigKeys.TRANSLATE_ENABLED)
        watchedKeys.add(ConfigKeys.TRANSLATE_TARGET)

        for (key in watchedKeys) {
            config.addListener(key) { value ->
                ModuleLog.i("FeatureRegistry", "config ${key.name} changed -> $value")
                for (feature in features) {
                    try {
                        feature.onConfigChanged(host)
                    } catch (t: Throwable) {
                        ModuleLog.e("FeatureRegistry", "${feature.id} onConfigChanged failed", t)
                    }
                }
            }
        }
    }

    fun features(): List<FeatureModule> = features

    fun notifyRuntimeReady(host: Host) {
        for (feature in features) {
            try {
                feature.onRuntimeReady(host)
            } catch (t: Throwable) {
                ModuleLog.e("FeatureRegistry", "${feature.id} onRuntimeReady failed", t)
            }
        }
    }
}
