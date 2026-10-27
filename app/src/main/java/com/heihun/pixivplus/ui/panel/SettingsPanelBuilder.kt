package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.heihun.pixivplus.BuildConfig
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.ui.UiKit
import com.heihun.pixivplus.ui.theme.CharcoalBridge

object SettingsPanelBuilder {

    private const val TAG = "SettingsPanel"

    fun show(activity: Activity) {
        try {
            val theme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.R.style.Theme_DeviceDefault_DayNight
            } else {
                android.R.style.Theme_DeviceDefault
            }
            val dialog = Dialog(activity, theme)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val content = buildContent(activity, dialog)
            dialog.setContentView(content)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
            ModuleLog.i(TAG, "panel shown")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "show failed", t)
        }
    }

    private fun buildContent(activity: Activity, dialog: Dialog): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor(activity))
        }
        root.addView(buildHeader(activity, dialog))
        root.addView(UiKit.divider(activity))

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, UiKit.dp(activity, 24))
        }

        body.addView(UiKit.sectionTitle(activity, "功能开关"))
        body.addView(UiKit.divider(activity))
        body.addView(
            UiKit.switchRow(
                activity,
                "本地浏览历史",
                "记录插画 / 漫画 / 小说的访问历史",
                configValue(ConfigKeys.HISTORY_ENABLED)
            ) { checked -> putConfig(ConfigKeys.HISTORY_ENABLED, checked) }
        )
        body.addView(
            UiKit.switchRow(
                activity,
                "个人首页浏览记录联动",
                "在原版「浏览记录」页面展示本地历史记录",
                configValue(ConfigKeys.HISTORY_BRIDGE_ENABLED)
            ) { checked -> putConfig(ConfigKeys.HISTORY_BRIDGE_ENABLED, checked) }
        )
        body.addView(
            UiKit.switchRow(
                activity,
                "本地过滤",
                "按关键词 / 标签 / 作者隐藏内容",
                configValue(ConfigKeys.FILTER_ENABLED)
            ) { checked -> putConfig(ConfigKeys.FILTER_ENABLED, checked) }
        )
        body.addView(
            UiKit.switchRow(
                activity,
                "本地 Premium 覆盖",
                "仅影响客户端界面显示，服务端权限不会解锁",
                configValue(ConfigKeys.PREMIUM_OVERRIDE)
            ) { checked -> putConfig(ConfigKeys.PREMIUM_OVERRIDE, checked) }
        )
        body.addView(
            UiKit.switchRow(
                activity,
                "移除广告",
                "屏蔽 ADG / AppLovin / Self-serve 广告请求与广告位",
                configValue(ConfigKeys.ADS_REMOVAL)
            ) { checked -> putConfig(ConfigKeys.ADS_REMOVAL, checked) }
        )

        body.addView(UiKit.sectionTitle(activity, "过滤规则"))
        body.addView(UiKit.divider(activity))
        body.addView(
            UiKit.inputRow(
                activity,
                "标题 / 简介关键词（换行或逗号分隔）",
                configText(ConfigKeys.FILTER_KEYWORDS),
                "例如：R-18, 关键词"
            ) { value -> putConfig(ConfigKeys.FILTER_KEYWORDS, value) }
        )
        body.addView(
            UiKit.inputRow(
                activity,
                "标签（换行或逗号分隔）",
                configText(ConfigKeys.FILTER_TAGS),
                "例如：原创, 舰娘"
            ) { value -> putConfig(ConfigKeys.FILTER_TAGS, value) }
        )
        body.addView(
            UiKit.inputRow(
                activity,
                "作者（换行或逗号分隔）",
                configText(ConfigKeys.FILTER_AUTHORS),
                "例如：画师名, account"
            ) { value -> putConfig(ConfigKeys.FILTER_AUTHORS, value) }
        )
        body.addView(
            UiKit.switchRow(
                activity,
                "区分大小写",
                "关闭时忽略英文大小写",
                configValue(ConfigKeys.FILTER_CASE_SENSITIVE)
            ) { checked -> putConfig(ConfigKeys.FILTER_CASE_SENSITIVE, checked) }
        )
        body.addView(
            UiKit.switchRow(
                activity,
                "暂停记录历史",
                "临时停止写入浏览历史",
                configValue(ConfigKeys.HISTORY_PAUSED)
            ) { checked -> putConfig(ConfigKeys.HISTORY_PAUSED, checked) }
        )
        body.addView(
            UiKit.inputRow(
                activity,
                "历史保留天数（0 = 永久）",
                configText(ConfigKeys.HISTORY_RETENTION_DAYS).ifEmpty { "30" },
                "30"
            ) { value -> putConfig(ConfigKeys.HISTORY_RETENTION_DAYS, value.filter { it.isDigit() }.ifEmpty { "0" }) }
        )

        body.addView(UiKit.sectionTitle(activity, "浏览历史"))
        body.addView(UiKit.divider(activity))
        body.addView(HistoryPanel.build(activity))

        body.addView(UiKit.sectionTitle(activity, "诊断"))
        body.addView(UiKit.divider(activity))
        body.addView(DiagnosticsPanel.build(activity))

        body.addView(UiKit.sectionTitle(activity, "关于"))
        body.addView(UiKit.divider(activity))
        body.addView(
            UiKit.infoRow(
                activity,
                "模块版本",
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            )
        )
        body.addView(
            UiKit.infoRow(
                activity,
                "数据存储",
                "宿主应用私有目录，不上传任何数据"
            )
        )
        body.addView(
            TextView(activity).apply {
                text = "提示：本地 Premium 只改变本机界面判断，不会解锁服务端限制功能；请仅在备用账号上测试。"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.7f
                setPadding(
                    UiKit.dp(activity, 20),
                    UiKit.dp(activity, 10),
                    UiKit.dp(activity, 20),
                    0
                )
            }
        )

        scroll.addView(body)
        root.addView(scroll)
        return root
    }

    private fun buildHeader(activity: Activity, dialog: Dialog): View {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 14), UiKit.dp(activity, 8), UiKit.dp(activity, 14))
        }
        header.addView(TextView(activity).apply {
            text = "PixivLocalPlus"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(activity).apply {
            text = "关闭"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(CharcoalBridge.brandColor(activity))
            setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 8), UiKit.dp(activity, 12), UiKit.dp(activity, 8))
            setOnClickListener { dialog.dismiss() }
        })
        return header
    }

    private fun configValue(key: com.heihun.pixivplus.core.config.ConfigKey<Boolean>): Boolean {
        return try {
            Runtime.configOrNull()?.get(key) ?: key.defaultValue
        } catch (_: Throwable) {
            key.defaultValue
        }
    }

    private fun configText(key: com.heihun.pixivplus.core.config.ConfigKey<String>): String {
        return try {
            Runtime.configOrNull()?.get(key) ?: key.defaultValue
        } catch (_: Throwable) {
            key.defaultValue
        }
    }

    private fun putConfig(key: com.heihun.pixivplus.core.config.ConfigKey<Boolean>, value: Boolean) {
        try {
            Runtime.configOrNull()?.put(key, value)
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "write config failed", t)
        }
    }

    private fun putConfig(key: com.heihun.pixivplus.core.config.ConfigKey<String>, value: String) {
        try {
            Runtime.configOrNull()?.put(key, value)
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "write config failed", t)
        }
    }

    private fun backgroundColor(activity: Activity): Int {
        return try {
            val typedValue = android.util.TypedValue()
            if (activity.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
                typedValue.data
            } else {
                Color.WHITE
            }
        } catch (_: Throwable) {
            Color.WHITE
        }
    }
}
