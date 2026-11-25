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
import com.heihun.pixivplus.core.i18n.CommonText as C
import com.heihun.pixivplus.core.i18n.DownloadText as D
import com.heihun.pixivplus.core.i18n.I18n
import com.heihun.pixivplus.core.i18n.LanguageText as L
import com.heihun.pixivplus.core.i18n.SettingsText as S
import com.heihun.pixivplus.core.i18n.Text
import com.heihun.pixivplus.core.i18n.WebViewingText as W
import com.heihun.pixivplus.core.i18n.SlimText as Slim
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.FilterRules
import com.heihun.pixivplus.core.net.ProxyMode
import com.heihun.pixivplus.core.net.ProxySettings
import com.heihun.pixivplus.core.net.ProxySpec
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.ui.ActivityResultBridge
import com.heihun.pixivplus.ui.UiKit
import com.heihun.pixivplus.ui.theme.Md3

object SettingsPanelBuilder {

    private const val TAG = "SettingsPanel"

    enum class Screen(private val titleText: Text, private val subtitleText: Text) {
        MAIN(S.MODULE, S.HOME_SUBTITLE),
        LANGUAGE(S.LANGUAGE, S.LANGUAGE_HINT),
        WEB_VIEWING(W.TITLE, W.HINT),
        FEATURES(S.FEATURES, S.FEATURES_HINT),
        FILTERS(S.FILTERS, S.FILTERS_HINT),
        NOVEL_SAVE(S.NOVEL_SAVE, S.NOVEL_SAVE_HINT),
        ILLUST_SAVE(S.ILLUST_SAVE, S.ILLUST_SAVE_HINT),
        TASKS(S.TASKS, S.TASKS_HINT),
        CACHE(S.CACHE, S.CACHE_HINT),
        SLIM(Slim.TITLE, Slim.HINT),
        PROXY(S.PROXY, S.PROXY_HINT),
        BACKUP(S.BACKUP, S.BACKUP_HINT),
        HISTORY(S.HISTORY, S.HISTORY_HINT),
        DIAGNOSTICS(S.DIAGNOSTICS, S.DIAGNOSTICS_HINT),
        ABOUT(S.ABOUT, S.ABOUT_HINT);

        val title get() = titleText()
        val subtitle get() = subtitleText()
    }

    /** 直达二级页时返回箭头和系统返回键均关闭面板，回到宿主打开位置。 */
    fun show(activity: Activity, initialScreen: Screen = Screen.MAIN) {
        try {
            val dialog = UiKit.dialog(activity, fullScreen = true)
            // 面板为全屏不透明表面，去掉遮罩：避免入场动画期间遮罩先于内容闪现 / 提前消失。
            dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
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

            // 直达二级页打开的面板（如屏蔽设定页入口）：返回键直接关闭面板回到宿主页面，
            // 不再先跳模块主页；普通入口保持「返回 → 回主页」的既有行为。
            val deepLinked = initialScreen != Screen.MAIN
            var current = Screen.MAIN
            // 记住各页滚动位置：从二级页返回主页时恢复，不再跳回顶部。
            val scrollPositions = HashMap<Screen, Int>()
            val slimTabs = SlimTabState()
            var activeScroll: ScrollView? = null
            fun showScreen(screen: Screen, fromBack: Boolean = false) {
                activeScroll?.let { view -> scrollPositions[current] = view.scrollY }
                val goingBack = fromBack || (current != Screen.MAIN && screen == Screen.MAIN)
                current = screen
                renderScreen(
                    activity,
                    dialog,
                    container,
                    screen,
                    slimTabs,
                    goingBack,
                    { showScreen(it) },
                    {
                        if (deepLinked) dialog.dismiss()
                        else showScreen(Screen.MAIN, fromBack = true)
                    },
                    scrollPositions[screen] ?: 0,
                    { activeScroll = it }
                )
            }

            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
                if (deepLinked) {
                    if (event.action == KeyEvent.ACTION_UP) dialog.dismiss()
                    true
                } else if (current != Screen.MAIN) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        showScreen(Screen.MAIN, fromBack = true)
                    }
                    true
                } else {
                    false
                }
            }
            val onLanguageChanged = { if (dialog.isShowing) showScreen(current) }
            I18n.addListener(onLanguageChanged)
            dialog.setOnDismissListener {
                I18n.removeListener(onLanguageChanged)
                scrollPositions.clear()
            }
            showScreen(initialScreen)
            ModuleLog.i(TAG, "panel shown (initial=${initialScreen.name})")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "show failed", t)
        }
    }

    private fun renderScreen(
        activity: Activity,
        dialog: Dialog,
        container: LinearLayout,
        screen: Screen,
        slimTabs: SlimTabState,
        goingBack: Boolean,
        open: (Screen) -> Unit,
        back: () -> Unit,
        restoreScrollY: Int,
        onScrollCreated: (ScrollView) -> Unit
    ) {
        container.removeAllViews()
        val header = if (screen == Screen.MAIN) {
            navigationHeader(activity, screen.title, screen.subtitle, null) { dialog.dismiss() }
        } else {
            navigationHeader(activity, screen.title, screen.subtitle, back) { dialog.dismiss() }
        }
        container.addView(header)
        container.addView(UiKit.divider(activity))

        if (screen == Screen.SLIM) {
            SlimPanel.build(activity, container, slimTabs, onScrollCreated)
            UiKit.fadeIn(header)
            return
        }

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
        onScrollCreated(scroll)
        if (restoreScrollY > 0) {
            // 布局完成后再恢复滚动位置，否则会被重置。
            scroll.post { scroll.scrollTo(0, restoreScrollY) }
        }

        // 页面切换动画：进入二级页从右滑入，返回主页从左滑入。
        UiKit.fadeIn(header)
        UiKit.animateScreenIn(scroll, fromRight = !goingBack)

        when (screen) {
            Screen.MAIN -> buildMainMenu(activity, body, open)
            Screen.LANGUAGE -> LanguagePanel.build(activity, body)
            Screen.WEB_VIEWING -> WebViewingPanel.build(activity, body)
            Screen.FEATURES -> buildFeaturesPage(activity, body)
            Screen.FILTERS -> {
                val card = UiKit.card(activity)
                body.addView(card)
                renderFilterCard(activity, card)
            }
            Screen.NOVEL_SAVE -> buildNovelSavePage(activity, body)
            Screen.ILLUST_SAVE -> buildIllustSavePage(activity, body)
            Screen.TASKS -> TaskPanel.build(activity, body) { open(Screen.CACHE) }
            Screen.CACHE -> CachePanel.build(activity, body)
            Screen.SLIM -> Unit // 固定标签页已在创建普通滚动容器前处理。
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
        val languageCard = UiKit.card(activity)
        addMenuRow(languageCard, activity, S.LANGUAGE(), LanguagePanel.summary(), Screen.LANGUAGE, open)
        body.addView(languageCard)
        body.addView(UiKit.paragraph(activity, S.HOME_HINT()))

        body.addView(UiKit.sectionTitle(activity, S.FEATURES_SECTION()))
        val features = UiKit.card(activity)
        addMenuRow(
            features,
            activity,
            S.FEATURES(),
            S.FEATURES_HINT(),
            Screen.FEATURES,
            open
        )
        addMenuRow(features, activity, S.FILTERS(), filterOverview(), Screen.FILTERS, open)
        addMenuRow(features, activity, W.TITLE(), W.HINT(), Screen.WEB_VIEWING, open)
        addMenuRow(features, activity, S.HISTORY(), historySummary(), Screen.HISTORY, open)
        addMenuRow(features, activity, S.NOVEL_SAVE(), novelSaveSummary(), Screen.NOVEL_SAVE, open)
        addMenuRow(features, activity, S.ILLUST_SAVE(), if (configValue(ConfigKeys.ILLUST_SAVE_ENABLED)) S.ORIGINAL_FORMATS() else C.DISABLED(), Screen.ILLUST_SAVE, open)
        addMenuRow(features, activity, S.TASKS(), S.TASKS_HINT(), Screen.TASKS, open)
        addMenuRow(features, activity, Slim.TITLE(), SlimPanel.summary(), Screen.SLIM, open)
        body.addView(features)

        body.addView(UiKit.sectionTitle(activity, S.NETWORK_SECTION()))
        val network = UiKit.card(activity)
        addMenuRow(network, activity, S.PROXY(), proxySummary(), Screen.PROXY, open)
        addMenuRow(network, activity, S.BACKUP(), backupSummary(), Screen.BACKUP, open)
        body.addView(network)

        body.addView(UiKit.sectionTitle(activity, S.OTHER_SECTION()))
        val others = UiKit.card(activity)
        addMenuRow(others, activity, S.CACHE(), S.CACHE_HINT(), Screen.CACHE, open)
        addMenuRow(others, activity, S.DIAGNOSTICS(), diagnosticsSummary(), Screen.DIAGNOSTICS, open)
        addMenuRow(others, activity, S.ABOUT(), aboutSummary(), Screen.ABOUT, open)
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
            S.LOCAL_HISTORY(),
            S.LOCAL_HISTORY_HINT(),
            configValue(ConfigKeys.HISTORY_ENABLED)
        ) { checked -> putConfig(ConfigKeys.HISTORY_ENABLED, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            S.HISTORY_BRIDGE(),
            S.HISTORY_BRIDGE_HINT(),
            configValue(ConfigKeys.HISTORY_BRIDGE_ENABLED)
        ) { checked -> putConfig(ConfigKeys.HISTORY_BRIDGE_ENABLED, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            S.LOCAL_FILTER(),
            S.LOCAL_FILTER_HINT(),
            configValue(ConfigKeys.FILTER_ENABLED)
        ) { checked -> putConfig(ConfigKeys.FILTER_ENABLED, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            S.PREMIUM(),
            S.PREMIUM_HINT(),
            configValue(ConfigKeys.PREMIUM_OVERRIDE)
        ) { checked -> putConfig(ConfigKeys.PREMIUM_OVERRIDE, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            S.REMOVE_ADS(),
            S.REMOVE_ADS_HINT(),
            configValue(ConfigKeys.ADS_REMOVAL)
        ) { checked -> putConfig(ConfigKeys.ADS_REMOVAL, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            S.CAPTION_FIX(),
            S.CAPTION_FIX_HINT(),
            configValue(ConfigKeys.CAPTION_FIX)
        ) { checked -> putConfig(ConfigKeys.CAPTION_FIX, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            S.NOVEL_SAVE(),
            S.NOVEL_SAVE_TOGGLE(),
            configValue(ConfigKeys.NOVEL_SAVE_ENABLED)
        ) { checked -> putConfig(ConfigKeys.NOVEL_SAVE_ENABLED, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            S.ILLUST_SAVE(),
            S.ILLUST_SAVE_TOGGLE(),
            configValue(ConfigKeys.ILLUST_SAVE_ENABLED)
        ) { checked -> putConfig(ConfigKeys.ILLUST_SAVE_ENABLED, checked) })
        addRow(card, UiKit.switchRow(
            activity,
            S.TRANSLATE(),
            S.TRANSLATE_HINT(),
            configValue(ConfigKeys.TRANSLATE_ENABLED),
            actionLabel = S.TARGET_LABEL(translateTargetLabel()),
            onAction = { entry ->
                val targets = com.heihun.pixivplus.core.translate.Translator.TARGETS
                val selected = targets.indexOfFirst { it.code == configText(ConfigKeys.TRANSLATE_TARGET) }
                ChoiceDialog.show(
                    activity,
                    S.TRANSLATE_TARGET(),
                    targets.map { it.label },
                    if (selected >= 0) selected else 0
                ) { index ->
                    putConfig(ConfigKeys.TRANSLATE_TARGET, targets[index].code)
                    entry.text = S.TARGET_LABEL(targets[index].label)
                }
            }
        ) { checked -> putConfig(ConfigKeys.TRANSLATE_ENABLED, checked) })
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.paragraph(
            activity,
            S.TRANSLATE_SERVICE()
        ))
        body.addView(card)
        body.addView(
            UiKit.paragraph(
                activity,
                S.FEATURE_NOTE()
            )
        )
    }

    private fun buildIllustSavePage(activity: Activity, body: LinearLayout) {
        val card = UiKit.card(activity)
        card.addView(UiKit.switchRow(activity, S.INCLUDE_LINKS(), S.INCLUDE_LINKS_HINT(),
            configValue(ConfigKeys.ILLUST_SAVE_INCLUDE_LINKS)) { checked -> putConfig(ConfigKeys.ILLUST_SAVE_INCLUDE_LINKS, checked) })
        val namingRow = UiKit.infoRow(activity, S.SAVE_NAME(), configText(ConfigKeys.ILLUST_SAVE_NAME_TEMPLATE))
        card.addView(namingRow)
        card.addView(UiKit.filledButtonRow(activity, S.CUSTOM_SAVE_NAME()) {
            MediaNameDialog.show(activity, configText(ConfigKeys.ILLUST_SAVE_NAME_TEMPLATE)) { template ->
                putConfig(ConfigKeys.ILLUST_SAVE_NAME_TEMPLATE, template)
                (namingRow.getChildAt(1) as? TextView)?.text = template
            }
        })
        fun folderLabel(): String {
            val raw = configText(ConfigKeys.ILLUST_SAVE_FOLDER_URI)
            if (raw.isBlank()) return D.DEFAULT_LOCATION()
            return try {
                com.heihun.pixivplus.core.backup.BackupManager.folderName(activity, android.net.Uri.parse(raw))
                    ?: C.FOLDER_UNREADABLE()
            } catch (_: Exception) { C.FOLDER_UNREADABLE() }
        }
        val folderRow = UiKit.infoRow(activity, C.FOLDER(), folderLabel())
        card.addView(folderRow)
        card.addView(UiKit.filledButtonRow(activity, C.SELECT_FOLDER()) {
            ActivityResultBridge.pickFolder(activity) { resultActivity, uri ->
                if (uri != null) {
                    putConfig(ConfigKeys.ILLUST_SAVE_FOLDER_URI, uri.toString())
                    (folderRow.getChildAt(1) as? TextView)?.text = folderLabel()
                    Toast.makeText(resultActivity, C.FOLDER_SELECTED(), Toast.LENGTH_SHORT).show()
                }
            }
        })
        card.addView(UiKit.filledButtonRow(activity, D.RESET_LOCATION()) {
            putConfig(ConfigKeys.ILLUST_SAVE_FOLDER_URI, "")
            (folderRow.getChildAt(1) as? TextView)?.text = folderLabel()
        })
        card.addView(UiKit.paragraph(activity, S.ILLUST_INSTRUCTIONS()))
        body.addView(card)
    }

    private fun buildNovelSavePage(activity: Activity, body: LinearLayout) {
        val card = UiKit.card(activity)
        val folderRow = UiKit.infoRow(activity, C.FOLDER(), novelSaveFolderLabel(activity))
        card.addView(folderRow)
        card.addView(UiKit.filledButtonRow(activity, C.SELECT_FOLDER()) {
            ActivityResultBridge.pickFolder(activity) { resultActivity, uri ->
                if (uri == null) {
                    Toast.makeText(resultActivity, C.NO_FOLDER_SELECTED(), Toast.LENGTH_SHORT).show()
                } else {
                    putConfig(ConfigKeys.NOVEL_SAVE_FOLDER_URI, uri.toString())
                    ModuleLog.i(TAG, "novel save folder selected: $uri")
                    Toast.makeText(resultActivity, C.FOLDER_SELECTED(), Toast.LENGTH_SHORT).show()
                    (folderRow.getChildAt(1) as? TextView)?.text = novelSaveFolderLabel(resultActivity)
                }
            }
        })
        card.addView(UiKit.filledButtonRow(activity, D.RESET_LOCATION()) {
            putConfig(ConfigKeys.NOVEL_SAVE_FOLDER_URI, "")
            (folderRow.getChildAt(1) as? TextView)?.text = novelSaveFolderLabel(activity)
        })
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.paragraph(
            activity,
            S.NOVEL_INSTRUCTIONS()
        ))
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.paragraph(
            activity,
            S.NOVEL_STORAGE()
        ))
        body.addView(card)
    }

    private fun novelSaveFolderLabel(activity: Activity): String {
        val raw = configText(ConfigKeys.NOVEL_SAVE_FOLDER_URI)
        if (raw.isBlank()) return D.DEFAULT_LOCATION()
        return try {
            com.heihun.pixivplus.core.backup.BackupManager.folderName(activity, android.net.Uri.parse(raw))
                ?: C.FOLDER_UNREADABLE()
        } catch (_: Throwable) {
            C.FOLDER_UNREADABLE()
        }
    }

    private fun novelSaveSummary(): String {
        val enabled = configValue(ConfigKeys.NOVEL_SAVE_ENABLED)
        val folder = if (configText(ConfigKeys.NOVEL_SAVE_FOLDER_URI).isBlank()) D.DEFAULT_LOCATION() else S.FOLDER_SET_SHORT()
        return if (enabled) "$folder · PDF / TXT" else C.DISABLED()
    }

    private fun translateTargetLabel(): String {
        val store = Runtime.configOrNull()
            ?: return com.heihun.pixivplus.core.translate.Translator.TARGETS[0].label
        return com.heihun.pixivplus.core.translate.Translator.targetOf(store).label
    }

    private fun buildHistoryPage(activity: Activity, body: LinearLayout) {
        val card = UiKit.card(activity)
        addRow(card, UiKit.switchRow(
            activity,
            S.HISTORY_PAUSE(),
            S.HISTORY_PAUSE_HINT(),
            configValue(ConfigKeys.HISTORY_PAUSED)
        ) { checked -> putConfig(ConfigKeys.HISTORY_PAUSED, checked) })
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.inputRow(
            activity,
            S.HISTORY_RETENTION(),
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
                S.MODULE_VERSION(),
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            )
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.infoRow(activity, S.BUILD_TIME(), DiagnosticsPanel.moduleBuildLabel()))
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.infoRow(activity, S.DATA_STORAGE(), S.DATA_STORAGE_HINT()))
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.infoRow(
                activity,
                S.BACKUP_LOCATION(),
                S.BACKUP_LOCATION_HINT()
            )
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.infoRow(
                activity,
                S.GITHUB(),
                "https://github.com/SuperHeiHun/PixivPlus"
            )
        )
        card.addView(
            UiKit.buttonRow(activity, S.OPEN_GITHUB()) {
                openUrl(activity, "https://github.com/SuperHeiHun/PixivPlus")
            }
        )
        body.addView(card)
        body.addView(
            UiKit.paragraph(
                activity,
                S.ABOUT_PREMIUM()
            )
        )
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "open url failed", t)
            Toast.makeText(activity, S.NO_BROWSER(), Toast.LENGTH_SHORT).show()
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
            header.addView(navigationButton(activity, "\u2190", C.BACK(), onBack))
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
                S.FILTER_KEYWORDS(),
                ConfigKeys.FILTER_KEYWORDS,
                S.SAMPLE_KEYWORDS()
            )
        )
        addRow(
            card,
            filterRow(
                activity,
                card,
                C.TAGS(),
                ConfigKeys.FILTER_TAGS,
                S.SAMPLE_TAGS()
            )
        )
        addRow(
            card,
            filterRow(
                activity,
                card,
                C.AUTHORS(),
                ConfigKeys.FILTER_AUTHORS,
                S.SAMPLE_CREATORS()
            )
        )
        addRow(
            card,
            UiKit.switchRow(
                activity,
                S.CASE_SENSITIVE(),
                S.CASE_SENSITIVE_HINT(),
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
            rules.isEmpty() -> C.NOT_SET()
            rules.size == 1 -> rules.first()
            else -> S.RULES_COUNT(rules.size)
        }
    }

    private fun filterOverview(): String {
        val keywords = FilterRules.parse(configText(ConfigKeys.FILTER_KEYWORDS)).size
        val tags = FilterRules.parse(configText(ConfigKeys.FILTER_TAGS)).size
        val authors = FilterRules.parse(configText(ConfigKeys.FILTER_AUTHORS)).size
        val total = keywords + tags + authors
        return if (total == 0) {
            S.NO_RULES()
        } else {
            S.RULES_SUMMARY(keywords, tags, authors)
        }
    }

    private fun proxySummary(): String {
        val store = Runtime.configOrNull() ?: return C.CONFIG_NOT_READY()
        return try {
            val base = ProxySettings.describe(ProxySettings.current(store))
            if (configValue(ConfigKeys.APP_PROXY_ENABLED)) S.PROXY_WITH_APP(base) else base
        } catch (_: Throwable) {
            C.UNKNOWN()
        }
    }

    private fun backupSummary(): String {
        val folder = if (configText(ConfigKeys.BACKUP_FOLDER_URI).isBlank()) {
            S.BACKUP_LOCAL_MISSING()
        } else {
            S.BACKUP_LOCAL_SET()
        }
        val host = WebDavBackup.normalizeHost(configText(ConfigKeys.WEBDAV_HOST))
        val remote = if (host.isNullOrBlank()) S.BACKUP_REMOTE_MISSING() else "WebDAV $host"
        return "$folder · $remote"
    }

    private fun historySummary(): String {
        if (configValue(ConfigKeys.HISTORY_PAUSED)) return S.HISTORY_PAUSED()
        val days = configText(ConfigKeys.HISTORY_RETENTION_DAYS).toIntOrNull() ?: 30
        val retention = if (days <= 0) S.KEEP_FOREVER() else S.KEEP_DAYS(days)
        val total = try {
            Runtime.historyDaoOrNull()?.count(null) ?: 0
        } catch (_: Throwable) {
            0
        }
        return "$retention · ${S.RECORD_COUNT(total)}"
    }

    private fun diagnosticsSummary(): String {
        val version = Host.versionName.ifBlank { C.UNKNOWN() }
        return S.HOST_SUMMARY(version, Host.versionCode)
    }

    private fun aboutSummary(): String {
        return "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    private fun renderProxyCard(activity: Activity, card: LinearLayout) {
        val store = Runtime.configOrNull()
        val spec = store?.let { ProxySettings.current(it) } ?: ProxySpec(ProxyMode.SYSTEM, "", 0)
        card.removeAllViews()

        card.addView(UiKit.selectRow(activity, S.PROXY_MODE(), spec.mode.label) {
            val modes = ProxyMode.entries
            ChoiceDialog.show(
                activity,
                S.PROXY_MODE(),
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
                S.PROXY_ADDRESS(),
                configText(ConfigKeys.PROXY_HOST),
                C.SAMPLE_ADDRESS(),
                singleLine = true
            ) { value -> putConfig(ConfigKeys.PROXY_HOST, value) })
            card.addView(UiKit.divider(activity, 16, 16))
            card.addView(UiKit.inputRow(
                activity,
                S.PROXY_PORT(),
                configText(ConfigKeys.PROXY_PORT),
                "7890",
                numeric = true
            ) { value -> putConfig(ConfigKeys.PROXY_PORT, value.filter { it.isDigit() }.take(5)) })

            when (spec.mode) {
                ProxyMode.HTTP, ProxyMode.SOCKS5 -> {
                    card.addView(UiKit.divider(activity, 16, 16))
                    card.addView(UiKit.inputRow(
                        activity,
                        C.OPTIONAL_USERNAME(),
                        configText(ConfigKeys.PROXY_USERNAME),
                        C.BLANK_NO_AUTH(),
                        singleLine = true
                    ) { value -> putConfig(ConfigKeys.PROXY_USERNAME, value.trim()) })
                    card.addView(UiKit.divider(activity, 16, 16))
                    card.addView(UiKit.inputRow(
                        activity,
                        C.OPTIONAL_PASSWORD(),
                        configText(ConfigKeys.PROXY_PASSWORD),
                        C.BLANK_NO_PASSWORD(),
                        password = true
                    ) { value -> putConfig(ConfigKeys.PROXY_PASSWORD, value) })
                }
                ProxyMode.SOCKS4 -> {
                    card.addView(UiKit.divider(activity, 16, 16))
                    card.addView(UiKit.inputRow(
                        activity,
                        S.SOCKS_USER_ID(),
                        configText(ConfigKeys.PROXY_USERNAME),
                        S.SOCKS_USER_ID_HINT(),
                        singleLine = true
                    ) { value -> putConfig(ConfigKeys.PROXY_USERNAME, value.trim()) })
                }
                else -> Unit
            }

            card.addView(UiKit.paragraph(
                activity,
                S.PROXY_CREDENTIALS()
            ))
        }

        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(
            UiKit.switchRow(
                activity,
                S.PROXY_APP(),
                S.PROXY_APP_HINT(),
                configValue(ConfigKeys.APP_PROXY_ENABLED)
            ) { checked -> putConfig(ConfigKeys.APP_PROXY_ENABLED, checked) }
        )
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.paragraph(activity, appProxyHint(spec.mode)))

        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.paragraph(activity, proxyHint(spec.mode)))
        card.addView(UiKit.filledButtonRow(activity, S.PROXY_TEST()) { testProxy(activity) })
    }

    private fun appProxyHint(mode: ProxyMode): String = when (mode) {
        ProxyMode.SYSTEM -> S.PROXY_APP_SYSTEM()
        ProxyMode.DIRECT -> S.PROXY_APP_DIRECT()
        ProxyMode.HTTP -> S.PROXY_APP_HTTP()
        ProxyMode.SOCKS4 -> S.PROXY_APP_SOCKS4()
        ProxyMode.SOCKS5 -> S.PROXY_APP_SOCKS5()
    }

    private fun proxyHint(mode: ProxyMode): String = when (mode) {
        ProxyMode.SYSTEM -> S.PROXY_MODULE_SYSTEM()
        ProxyMode.DIRECT -> S.PROXY_MODULE_DIRECT()
        ProxyMode.HTTP -> S.PROXY_MODULE_HTTP()
        ProxyMode.SOCKS4 -> S.PROXY_MODULE_SOCKS4()
        ProxyMode.SOCKS5 -> S.PROXY_MODULE_SOCKS5()
    }

    private fun testProxy(activity: Activity) {
        val store = Runtime.configOrNull()
        if (store == null) {
            Toast.makeText(activity, C.CONFIG_NOT_READY(), Toast.LENGTH_SHORT).show()
            return
        }
        val spec = ProxySettings.current(store)
        if (spec.mode.requiresEndpoint() && !spec.hasValidEndpoint()) {
            Toast.makeText(activity, S.PROXY_NEED_ENDPOINT(), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, S.PROXY_TESTING(), Toast.LENGTH_SHORT).show()
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
