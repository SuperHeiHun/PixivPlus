package com.heihun.pixivplus.feature

import com.heihun.pixivplus.core.Host

interface FeatureModule {

    val id: String

    val displayName: String

    fun init(host: Host)

    fun hook(host: Host)

    fun onConfigChanged(host: Host)

    fun onRuntimeReady(host: Host) {
    }
}
