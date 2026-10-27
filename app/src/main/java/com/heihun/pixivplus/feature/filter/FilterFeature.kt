package com.heihun.pixivplus.feature.filter

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.ApiHooks
import com.heihun.pixivplus.core.net.FilterRules
import com.heihun.pixivplus.core.net.PayloadProcessor
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature

class FilterFeature : ConfigurableFeature("filter", "本地过滤", ConfigKeys.FILTER_ENABLED) {

    override fun hook(host: Host) {
        if (!isEnabled(ConfigStoreRegistry.store)) {
            ModuleLog.i(displayName, "disabled by config, hooks skipped")
            return
        }
        reloadRules()
        ApiHooks.ensureInstalled(host)
        ModuleLog.i(displayName, "active: ${PayloadProcessor.rules.describe()}")
    }

    override fun onConfigChanged(host: Host) {
        reloadRules()
        ModuleLog.i(displayName, "rules updated: ${PayloadProcessor.rules.describe()}")
    }

    private fun reloadRules() {
        val store = ConfigStoreRegistry.store
        if (!isEnabled(store)) {
            PayloadProcessor.rules = FilterRules.EMPTY
            return
        }
        PayloadProcessor.rules = FilterRules(
            keywords = FilterRules.parse(store.get(ConfigKeys.FILTER_KEYWORDS)),
            tags = FilterRules.parse(store.get(ConfigKeys.FILTER_TAGS)),
            authors = FilterRules.parse(store.get(ConfigKeys.FILTER_AUTHORS)),
            caseSensitive = store.get(ConfigKeys.FILTER_CASE_SENSITIVE)
        )
    }
}
