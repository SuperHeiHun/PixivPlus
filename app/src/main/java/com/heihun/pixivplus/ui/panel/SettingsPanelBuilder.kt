package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.heihun.pixivplus.BuildConfig
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.backup.WebDavBackup
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.FilterRules
import com.heihun.pixivplus.core.net.ProxyMode
import com.heihun.pixivplus.core.net.ProxySettings
import com.heihun.pixivplus.core.net.ProxySpec
import com.heihun.pixivplus.ui.UiKit
import com.heihun.pixivplus.ui.theme.Md3

object SettingsPanelBuilder {

    private const val TAG = "SettingsPanel"

    private enum class Screen(val title: String, val subtitle: String) {
        MAIN("PixivPlus", "本地增强设置"),
        FEATURES("功能开关", "浏览历史、过滤、Premium、广告与简介修复"),
        FILTERS("过滤规则", "关键词、标签与作者"),
        PROXY("网络代理", "模块网页请求使用的代理"),
        BACKUP("数据备份", "本地文件夹与 WebDAV"),
        HISTORY("浏览历史", "记录策略与历史列表"),
        DIAGNOSTICS("诊断", "运行状态与日志"),
        ABOUT("关于", "版本与使用提示")
    }

    fun show(activity: Activity) {
        try {
            val dialog = UiKit.dialog(activity)
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(UiKit.colors(activity).surface)
            }
            dialog.setContentView(container)
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            dialog.show()
            UiKit.styleFullScreenWindow(dialog)

            var current = Screen.MAIN
            fun showScreen(screen: Screen) {
                current = screen
                renderScreen(
                    activity,
                    dialog,
                    container,
                    screen,
                    { showScreen(it) },
                    { showScreen(Screen.MAIN) }
                )
            }

            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && current != Screen.MAIN) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        showScreen(Screen.MAIN)
                    }
                    true
                } else {
                    false
                }
            }
            showScreen(Screen.MAIN)
            ModuleLog.i(TAG, "panel shown")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "show failed", t)
        }
    }

    private fun renderScreen(
        activity: Activity,
        dialog: Dialog,
        container: LinearLayout,
        screen: Screen,
        open: (Screen) -> Unit,
        back: () -> Unit
    ) {
        container.removeAllViews()
        val header = if (screen == Screen.MAIN) {
            navigationHeader(activity, screen.title, screen.subtitle, null) { dialog.dismiss() }
        } else {
            navigationHeader(activity, screen.title, screen.subtitle, back) { dialog.dismiss() }
        }
        container.addView(header)
        container.addView(UiKit.divider(activity))

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
        scroll.addView(body)
        container.addView(scroll)

        when (screen) {
            Screen.MAIN -> buildMainMenu(activity, body, open)
            Screen.FEATURES -> buildFeaturesPage(activity, body)
            Screen.FILTERS -> {
                val card = UiKit.card(activity)
                body.addView(card)
                renderFilterCard(activity, card)
            }
            Screen.PROXY -> {
                val card = UiKit.card(activity)
                body.addView(card)
                renderProxyCard(activity, card)
            }
            Screen.BACKUP -> {
                val card = UiKit.card(activity)
                body.addView(card)
                BackupPanel.build(activity, card)
            }
            Screen.HISTORY -> buildHistoryPage(activity, body)
            Screen.DIAGNOSTICS -> {
                val card = UiKit.card(activity)
                card.addView(DiagnosticsPanel.build(activity))
                body.addView(card)
            }
            Screen.ABOUT -> buildAboutPage(activity, body)
        }
    }

    private fun buildMainMenu(activity: Activity, body: LinearLayout, open: (Screen) -> Unit) {
        body.addView(
            UiKit.paragraph(
                activity,
                "选择分类进入二级设置；返回主页时，右侧汇总信息会自动刷新。"
            )
        )

        body.addView(UiKit.sectionTitle(activity, "功能"))
        val features = UiKit.card(activity)
        addMenuRow(
            features,
            activity,
            "功能开关",
            "浏览历史、过滤、Premium、广告与简介修复",
            Screen.FEATURES,
            open
        )
        addMenuRow(features, activity, "过滤规则", filterOverview(), Screen.FILTERS, open)
        addMenuRow(features, activity, "浏览历史", historySummary(), Screen.HISTORY, open)
        body.addView(features)

        body.addView(UiKit.sectionTitle(activity, "网络与数据"))
        val network = UiKit.card(activity)
        addMenuRow(network, activity, "网络代理", proxySummary(), Screen.PROXY, open)
        addMenuRow(network, activity, "数据备份", backupSummary(), Screen.BACKUP, open)
        body.addView(network)

        body.addView(UiKit.sectionTitle(activity, "其他"))
        val others = UiKit.card(activity)
        addMenuRow(others, activity, "诊断", diagnosticsSummary(), Screen.DIAGNOSTICS, open)
        addMenuRow(others, activity, "关于", aboutSummary(), Screen.ABOUT, open)
        body.addView(others)
    }

    private fun addMenuRow(
        card: LinearLayout,
        activity: Activity,
        title: String,
        subtitle: String,
        screen: Screen,
        open: (Screen) -> Unit
    ) {
        if (card.childCount > 0) {
            card.addView(UiKit.divider(activity, 16, 16))
        }
        card.addView(UiKit.menuRow(activity, title, subtitle) { open(screen) })
    }

    private fun buildFeaturesPage(activity: Activity, body: LinearLayout) {
        val card = UiKit.card(activity)
        addRow(card, UiKit.switchRow(
            activity,
            "本地浏览历史",
            "记录插画 / 漫画 / 小说的访问历史",
            configValue(ConfigKeys.HISTORY_ENABLED)
        ) { checked -> putConfig(ConfigKeys.HISTORY_ENABLED, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            "个人首页浏览记录联动",
            "在原版「浏览记录」页面展示本地历史记录",
            configValue(ConfigKeys.HISTORY_BRIDGE_ENABLED)
        ) { checked -> putConfig(ConfigKeys.HISTORY_BRIDGE_ENABLED, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            "本地过滤",
            "按关键词 / 标签 / 作者隐藏内容",
            configValue(ConfigKeys.FILTER_ENABLED)
        ) { checked -> putConfig(ConfigKeys.FILTER_ENABLED, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            "本地 Premium 覆盖",
            "仅影响客户端界面显示，服务端权限不会解锁",
            configValue(ConfigKeys.PREMIUM_OVERRIDE)
        ) { checked -> putConfig(ConfigKeys.PREMIUM_OVERRIDE, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            "移除广告",
            "屏蔽 ADG / AppLovin / Self-serve 广告请求与广告位",
            configValue(ConfigKeys.ADS_REMOVAL)
        ) { checked -> putConfig(ConfigKeys.ADS_REMOVAL, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            "插画简介修复",
            "恢复因包含链接而被 Android 客户端隐藏的完整简介",
            configValue(ConfigKeys.CAPTION_FIX)
        ) { checked -> putConfig(ConfigKeys.CAPTION_FIX, checked) })
        body.addView(card)
        body.addView(
            UiKit.paragraph(
                activity,
                "这些开关只影响本地模块行为；本地 Premium 覆盖不会解锁服务端限制功能。"
            )
        )
    }

    private fun buildHistoryPage(activity: Activity, body: LinearLayout) {
        val card = UiKit.card(activity)
        addRow(card, UiKit.switchRow(
            activity,
            "暂停记录历史",
            "临时停止写入浏览历史",
            configValue(ConfigKeys.HISTORY_PAUSED)
        ) { checked -> putConfig(ConfigKeys.HISTORY_PAUSED, checked) })
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.inputRow(
            activity,
            "历史保留天数（0 = 永久）",
            configText(ConfigKeys.HISTORY_RETENTION_DAYS).ifEmpty { "30" },
            "30",
            numeric = true
        ) { value ->
            putConfig(
                ConfigKeys.HISTORY_RETENTION_DAYS,
                value.filter { it.isDigit() }.ifEmpty { "0" }
            )
        })
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(HistoryPanel.build(activity))
        body.addView(card)
    }

    private fun buildAboutPage(activity: Activity, body: LinearLayout) {
        val card = UiKit.card(activity)
        card.addView(
            UiKit.infoRow(
                activity,
                "模块版本",
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            )
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.infoRow(activity, "数据存储", "宿主应用私有目录，不上传任何数据"))
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.infoRow(
                activity,
                "备份位置",
                "本地文件夹由用户选择；WebDAV 目录可自定义"
            )
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.infoRow(
                activity,
                "GitHub 仓库",
                "https://github.com/SuperHeiHun/PixivPlus"
            )
        )
        card.addView(
            UiKit.buttonRow(activity, "打开 GitHub 仓库") {
                openUrl(activity, "https://github.com/SuperHeiHun/PixivPlus")
            }
        )
        body.addView(card)
        body.addView(
            UiKit.paragraph(
                activity,
                "提示：本地 Premium 只改变本机界面判断，不会解锁服务端限制功能；" +
                    "请仅在备用账号上测试。"
            )
        )
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "open url failed", t)
            Toast.makeText(activity, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigationHeader(
        activity: Activity,
        title: String,
        subtitle: String,
        onBack: (() -> Unit)?,
        onClose: () -> Unit
    ): View {
        val palette = UiKit.colors(activity)
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = UiKit.dp(activity, 64)
            setPadding(
                UiKit.dp(activity, if (onBack != null) 8 else 24),
                UiKit.dp(activity, 12),
                UiKit.dp(activity, 12),
                UiKit.dp(activity, 12)
            )
            setBackgroundColor(palette.surface)
        }
        if (onBack != null) {
            header.addView(navigationButton(activity, "\u2190", "返回", onBack))
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setPadding(UiKit.dp(activity, if (onBack != null) 4 else 0), 0, UiKit.dp(activity, 12), 0)
        }
        texts.addView(TextView(activity).apply {
            text = title
            textSize = 22f
            setTextColor(palette.onSurface)
            typeface = Md3.medium
        })
        if (subtitle.isNotEmpty()) {
            texts.addView(TextView(activity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(palette.onSurfaceVariant)
                setPadding(0, UiKit.dp(activity, 2), 0, 0)
            })
        }
        header.addView(texts)
        header.addView(UiKit.closeButton(activity, onClose))
        return header
    }

    private fun navigationButton(
        context: Context,
        symbol: String,
        description: String,
        onClick: () -> Unit
    ): View {
        val palette = UiKit.colors(context)
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        return TextView(context).apply {
            text = symbol
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(palette.onSurfaceVariant)
            gravity = Gravity.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf(UiKit.withAlpha(palette.onSurfaceVariant, 0x1F)),
                null,
                mask
            )
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(context, 48),
                UiKit.dp(context, 48)
            )
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { onClick() }
        }
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

    private fun filterOverview(): String {
        val keywords = FilterRules.parse(configText(ConfigKeys.FILTER_KEYWORDS)).size
        val tags = FilterRules.parse(configText(ConfigKeys.FILTER_TAGS)).size
        val authors = FilterRules.parse(configText(ConfigKeys.FILTER_AUTHORS)).size
        val total = keywords + tags + authors
        return if (total == 0) {
            "未设置规则"
        } else {
            "关键词 $keywords · 标签 $tags · 作者 $authors"
        }
    }

    private fun proxySummary(): String {
        val store = Runtime.configOrNull() ?: return "配置未初始化"
        return try {
            ProxySettings.describe(ProxySettings.current(store))
        } catch (_: Throwable) {
            "未知"
        }
    }

    private fun backupSummary(): String {
        val folder = if (configText(ConfigKeys.BACKUP_FOLDER_URI).isBlank()) {
            "本地未选择"
        } else {
            "本地已选择"
        }
        val host = WebDavBackup.normalizeHost(configText(ConfigKeys.WEBDAV_HOST))
        val remote = if (host.isNullOrBlank()) "WebDAV 未设置" else "WebDAV $host"
        return "$folder · $remote"
    }

    private fun historySummary(): String {
        if (configValue(ConfigKeys.HISTORY_PAUSED)) return "已暂停记录"
        val days = configText(ConfigKeys.HISTORY_RETENTION_DAYS).toIntOrNull() ?: 30
        val retention = if (days <= 0) "永久保留" else "保留 $days 天"
        val total = try {
            Runtime.historyDaoOrNull()?.count(null) ?: 0
        } catch (_: Throwable) {
            0
        }
        return "$retention · $total 条记录"
    }

    private fun diagnosticsSummary(): String {
        val version = Host.versionName.ifBlank { "未知" }
        return "宿主 $version (${Host.versionCode})"
    }

    private fun aboutSummary(): String {
        return "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
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
                "例如 192.168.10.12",
                singleLine = true
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
                        "留空表示免认证",
                        singleLine = true
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
                        "作为 SOCKS4 USERID 发送，可留空",
                        singleLine = true
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
