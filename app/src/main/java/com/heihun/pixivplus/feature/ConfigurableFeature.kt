package com.heihun.pixivplus.feature

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.log.ModuleLog

abstract class ConfigurableFeature(
    override val id: String,
    override val displayName: String,
    private val configKey: com.heihun.pixivplus.core.config.ConfigKey<Boolean>
) : FeatureModule {

    protected fun isEnabled(config: ConfigStore): Boolean = config.get(configKey)

    override fun init(host: Host) {
        ModuleLog.i(displayName, "init (enabled=${isEnabled(ConfigStoreRegistry.store)})")
    }

    override fun onConfigChanged(host: Host) {
        ModuleLog.i(displayName, "config changed")
    }
}

object ConfigStoreRegistry {
    lateinit var store: ConfigStore
        private set

    fun install(store: ConfigStore) {
        this.store = store
    }
}
