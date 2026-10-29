package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.heihun.pixivplus.BuildConfig
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.FilterRules
import com.heihun.pixivplus.core.net.ProxyMode
import com.heihun.pixivplus.core.net.ProxySettings
import com.heihun.pixivplus.core.net.ProxySpec
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.ui.UiKit

object SettingsPanelBuilder {

    private const val TAG = "SettingsPanel"

    fun show(activity: Activity) {
        try {
            val dialog = UiKit.dialog(activity)
            val content = buildContent(activity, dialog)
            dialog.setContentView(content)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            dialog.show()
            UiKit.styleFullScreenWindow(dialog)
            ModuleLog.i(TAG, "panel shown")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "show failed", t)
        }
    }

    private fun buildContent(activity: Activity, dialog: Dialog): View {
        val palette = UiKit.colors(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.surface)
        }
        root.addView(UiKit.dialogHeader(activity, "PixivPlus", "本地增强设置") { dialog.dismiss() })
        root.addView(UiKit.divider(activity))

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, UiKit.dp(activity, 24))
        }

        body.addView(UiKit.sectionTitle(activity, "功能开关"))
        val features = UiKit.card(activity)
        addRow(features, UiKit.switchRow(
            activity,
            "本地浏览历史",
            "记录插画 / 漫画 / 小说的访问历史",
            configValue(ConfigKeys.HISTORY_ENABLED)
        ) { checked -> putConfig(ConfigKeys.HISTORY_ENABLED, checked) })
        addRow(features, UiKit.switchRow(
            activity,
            "个人首页浏览记录联动",
            "在原版「浏览记录」页面展示本地历史记录",
            configValue(ConfigKeys.HISTORY_BRIDGE_ENABLED)
        ) { checked -> putConfig(ConfigKeys.HISTORY_BRIDGE_ENABLED, checked) })
        addRow(features, UiKit.switchRow(
            activity,
            "本地过滤",
            "按关键词 / 标签 / 作者隐藏内容",
            configValue(ConfigKeys.FILTER_ENABLED)
        ) { checked -> putConfig(ConfigKeys.FILTER_ENABLED, checked) })
        addRow(features, UiKit.switchRow(
            activity,
            "本地 Premium 覆盖",
            "仅影响客户端界面显示，服务端权限不会解锁",
            configValue(ConfigKeys.PREMIUM_OVERRIDE)
        ) { checked -> putConfig(ConfigKeys.PREMIUM_OVERRIDE, checked) })
        addRow(features, UiKit.switchRow(
            activity,
            "移除广告",
            "屏蔽 ADG / AppLovin / Self-serve 广告请求与广告位",
            configValue(ConfigKeys.ADS_REMOVAL)
        ) { checked -> putConfig(ConfigKeys.ADS_REMOVAL, checked) })
        addRow(features, UiKit.switchRow(
            activity,
            "插画简介修复",
            "恢复因包含链接而被 Android 客户端隐藏的完整简介",
            configValue(ConfigKeys.CAPTION_FIX)
        ) { checked -> putConfig(ConfigKeys.CAPTION_FIX, checked) })
        body.addView(features)

        body.addView(UiKit.sectionTitle(activity, "过滤规则"))
        val filters = UiKit.card(activity)
        body.addView(filters)
        renderFilterCard(activity, filters)

        body.addView(UiKit.sectionTitle(activity, "网络代理"))
        val proxyCard = UiKit.card(activity)
        body.addView(proxyCard)
        renderProxyCard(activity, proxyCard)

        body.addView(UiKit.sectionTitle(activity, "数据备份"))
        val backupCard = UiKit.card(activity)
        body.addView(backupCard)
        BackupPanel.build(activity, backupCard)

        body.addView(UiKit.sectionTitle(activity, "浏览历史"))
        val history = UiKit.card(activity)
        addRow(history, UiKit.switchRow(
            activity,
            "暂停记录历史",
            "临时停止写入浏览历史",
            configValue(ConfigKeys.HISTORY_PAUSED)
        ) { checked -> putConfig(ConfigKeys.HISTORY_PAUSED, checked) })
        history.addView(UiKit.divider(activity, 16, 16))
        history.addView(UiKit.inputRow(
            activity,
            "历史保留天数（0 = 永久）",
            configText(ConfigKeys.HISTORY_RETENTION_DAYS).ifEmpty { "30" },
            "30",
            numeric = true
        ) { value -> putConfig(ConfigKeys.HISTORY_RETENTION_DAYS, value.filter { it.isDigit() }.ifEmpty { "0" }) })
        history.addView(UiKit.divider(activity, 16, 16))
        history.addView(HistoryPanel.build(activity))
        body.addView(history)

        body.addView(UiKit.sectionTitle(activity, "诊断"))
        val diagnostics = UiKit.card(activity)
        diagnostics.addView(DiagnosticsPanel.build(activity))
        body.addView(diagnostics)

        body.addView(UiKit.sectionTitle(activity, "关于"))
        val about = UiKit.card(activity)
        about.addView(UiKit.infoRow(activity, "模块版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
        about.addView(UiKit.divider(activity, 16, 16))
        about.addView(UiKit.infoRow(activity, "数据存储", "宿主应用私有目录，不上传任何数据"))
        about.addView(UiKit.divider(activity, 16, 16))
        about.addView(UiKit.paragraph(
            activity,
            "提示：本地 Premium 只改变本机界面判断，不会解锁服务端限制功能；请仅在备用账号上测试。"
        ))
        body.addView(about)

        scroll.addView(body)
        root.addView(scroll)
        return root
    }

    private fun renderFilterCard(activity: Activity, card: LinearLayout) {
        card.removeAllViews()
        addRow(
            card,
            filterRow(
                activity,
                card,
                "标题 / 简介关键词",
                ConfigKeys.FILTER_KEYWORDS,
                "例如：R-18, 关键词"
            )
        )
        addRow(
            card,
            filterRow(
                activity,
                card,
                "标签",
                ConfigKeys.FILTER_TAGS,
                "例如：原创, 舰娘"
            )
        )
        addRow(
            card,
            filterRow(
                activity,
                card,
                "作者",
                ConfigKeys.FILTER_AUTHORS,
                "例如：画师名, account"
            )
        )
        addRow(
            card,
            UiKit.switchRow(
                activity,
                "区分大小写",
                "关闭时忽略英文大小写",
                configValue(ConfigKeys.FILTER_CASE_SENSITIVE)
            ) { checked -> putConfig(ConfigKeys.FILTER_CASE_SENSITIVE, checked) }
        )
    }

    private fun filterRow(
        activity: Activity,
        card: LinearLayout,
        title: String,
        key: com.heihun.pixivplus.core.config.ConfigKey<String>,
        example: String
    ): View {
        val current = configText(key)
        return UiKit.selectRow(activity, title, filterSummary(current)) {
            FilterRuleDialog.show(activity, title, example, current) { value ->
                putConfig(key, value)
                renderFilterCard(activity, card)
            }
        }
    }

    private fun filterSummary(raw: String): String {
        val rules = FilterRules.parse(raw)
        return when {
            rules.isEmpty() -> "未设置"
            rules.size == 1 -> rules.first()
            else -> "${rules.size} 条规则"
        }
    }

    private fun renderProxyCard(activity: Activity, card: LinearLayout) {
        val store = Runtime.configOrNull()
        val spec = store?.let { ProxySettings.current(it) } ?: ProxySpec(ProxyMode.SYSTEM, "", 0)
        card.removeAllViews()

        card.addView(UiKit.selectRow(activity, "代理模式", spec.mode.label) {
            val modes = ProxyMode.entries
            ChoiceDialog.show(
                activity,
                "代理模式",
                modes.map { it.label },
                modes.indexOf(spec.mode)
            ) { index ->
                putConfig(ConfigKeys.PROXY_MODE, modes[index].storageValue)
                renderProxyCard(activity, card)
            }
        })

        if (spec.mode.requiresEndpoint()) {
            card.addView(UiKit.divider(activity, 16, 16))
            card.addView(UiKit.inputRow(
                activity,
                "代理地址",
                configText(ConfigKeys.PROXY_HOST),
                "例如 192.168.10.12"
            ) { value -> putConfig(ConfigKeys.PROXY_HOST, value) })
            card.addView(UiKit.divider(activity, 16, 16))
            card.addView(UiKit.inputRow(
                activity,
                "代理端口",
                configText(ConfigKeys.PROXY_PORT),
                "7890",
                numeric = true
            ) { value -> putConfig(ConfigKeys.PROXY_PORT, value.filter { it.isDigit() }.take(5)) })

            when (spec.mode) {
                ProxyMode.HTTP, ProxyMode.SOCKS5 -> {
                    card.addView(UiKit.divider(activity, 16, 16))
                    card.addView(UiKit.inputRow(
                        activity,
                        "用户名（可选）",
                        configText(ConfigKeys.PROXY_USERNAME),
                        "留空表示免认证"
                    ) { value -> putConfig(ConfigKeys.PROXY_USERNAME, value.trim()) })
                    card.addView(UiKit.divider(activity, 16, 16))
                    card.addView(UiKit.inputRow(
                        activity,
                        "密码（可选）",
                        configText(ConfigKeys.PROXY_PASSWORD),
                        "留空表示无密码",
                        password = true
                    ) { value -> putConfig(ConfigKeys.PROXY_PASSWORD, value) })
                }
                ProxyMode.SOCKS4 -> {
                    card.addView(UiKit.divider(activity, 16, 16))
                    card.addView(UiKit.inputRow(
                        activity,
                        "用户 ID（可选）",
                        configText(ConfigKeys.PROXY_USERNAME),
                        "作为 SOCKS4 USERID 发送，可留空"
                    ) { value -> putConfig(ConfigKeys.PROXY_USERNAME, value.trim()) })
                }
                else -> Unit
            }

            card.addView(UiKit.paragraph(
                activity,
                "认证凭据以明文保存在 Pixiv 的私有配置中，仅用于模块自身的网页请求。"
            ))
        }

        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.paragraph(activity, proxyHint(spec.mode)))
        card.addView(UiKit.filledButtonRow(activity, "测试代理连接") { testProxy(activity) })
    }

    private fun proxyHint(mode: ProxyMode): String = when (mode) {
        ProxyMode.SYSTEM -> "使用 Android 系统代理。模块的网页请求会自动沿用 Wi-Fi 或全局代理设置。"
        ProxyMode.DIRECT -> "不使用代理，模块的网页请求直接连接。"
        ProxyMode.HTTP -> "HTTP / HTTPS 代理：HTTP 请求直接转发，HTTPS 请求通过 CONNECT 隧道；支持 HTTP Basic 认证。"
        ProxyMode.SOCKS4 -> "SOCKS4 / SOCKS4a 代理：支持域名目标；认证方式为 USERID，不支持密码。"
        ProxyMode.SOCKS5 -> "SOCKS5 代理：支持域名目标与 RFC 1929 用户名 / 密码认证。"
    }

    private fun testProxy(activity: Activity) {
        val store = Runtime.configOrNull()
        if (store == null) {
            Toast.makeText(activity, "配置尚未初始化", Toast.LENGTH_SHORT).show()
            return
        }
        val spec = ProxySettings.current(store)
        if (spec.mode.requiresEndpoint() && !spec.hasValidEndpoint()) {
            Toast.makeText(activity, "请先填写有效的代理地址和端口", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, "正在测试代理连接…", Toast.LENGTH_SHORT).show()
        Thread({
            val result = ProxySettings.test(store)
            try {
                activity.runOnUiThread {
                    if (!activity.isFinishing) {
                        Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Throwable) {
            }
        }, "plp-proxy-test").apply { isDaemon = true }.start()
    }

    private fun addRow(card: LinearLayout, row: View) {
        if (card.childCount > 0) {
            card.addView(UiKit.divider(card.context, 16, 16))
        }
        card.addView(row)
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
}
