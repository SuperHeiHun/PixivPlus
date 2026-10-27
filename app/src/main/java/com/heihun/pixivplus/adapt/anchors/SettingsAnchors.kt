package com.heihun.pixivplus.adapt.anchors

import com.heihun.pixivplus.adapt.ResolveSpec

object SettingsAnchors {

    const val SETTING_ACTIVITY_CLASS = "jp.pxv.android.feature.setting.list.SettingActivity"
    const val SETTING_LAYOUT_NAME = "feature_setting_activity_settings"
    const val SETTING_CONTAINER_ID = "fragment_container"
    const val APPLICATION_CLASS = "jp.pxv.android.Pixiv"

    val specs: List<ResolveSpec> = listOf(
        ResolveSpec.ClassSpec(
            id = "settings.activity",
            description = "宿主设置页 Activity",
            className = SETTING_ACTIVITY_CLASS
        ),
        ResolveSpec.MethodSpec(
            id = "settings.activity.onCreate",
            description = "宿主设置页入口方法",
            className = SETTING_ACTIVITY_CLASS,
            methodName = "onCreate",
            parameterTypes = listOf("android.os.Bundle")
        ),
        ResolveSpec.StringSpec(
            id = "settings.layout",
            description = "宿主设置页布局资源名",
            literal = SETTING_LAYOUT_NAME
        ),
        ResolveSpec.StringSpec(
            id = "settings.container",
            description = "宿主设置页容器 id",
            literal = SETTING_CONTAINER_ID
        ),
        ResolveSpec.ClassSpec(
            id = "entry.application",
            description = "宿主 Application",
            className = APPLICATION_CLASS
        )
    )
}
