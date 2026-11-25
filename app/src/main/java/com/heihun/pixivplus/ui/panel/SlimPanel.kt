package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.SlimText as S
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.ui.UiKit
import com.heihun.pixivplus.ui.theme.Md3

internal object SlimPanel {
    fun summary(): String {
        val store = ConfigStoreRegistry.store
        val count = listOf(ConfigKeys.DRAWER_SLIM_ENABLED, ConfigKeys.BOTTOM_SLIM_ENABLED,
            ConfigKeys.MY_PAGE_SLIM_ENABLED).count { store.get(it) }
        return S.SUMMARY(count)
    }

    /** 标签栏固定在内容滚动区上方，三个标签复用同一个页面和返回导航。 */
    fun build(activity: Activity, container: LinearLayout, state: SlimTabState, onScrollCreated: (ScrollView) -> Unit) {
        val palette = UiKit.colors(activity)
        val tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 8), UiKit.dp(activity, 12), UiKit.dp(activity, 8))
        }
        container.addView(tabs)
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, UiKit.dp(activity, 24))
        }
        scroll.addView(body)
        container.addView(scroll)
        onScrollCreated(scroll)
        val buttons = LinkedHashMap<SlimTab, TextView>()
        var generation = 0
        fun render() {
            val tab = state.selected
            val restoreY = state.scrollY(tab)
            val token = ++generation
            body.removeAllViews()
            scroll.scrollTo(0, 0)
            for ((entry, button) in buttons) {
                val selected = entry == tab
                button.isSelected = selected
                button.setTextColor(if (selected) palette.onPrimaryContainer else palette.onSurfaceVariant)
                button.typeface = if (selected) Md3.medium else Md3.regular
                button.background = RippleDrawable(ColorStateList.valueOf(UiKit.withAlpha(palette.primary, 30)),
                    UiKit.rounded(activity, if (selected) palette.primaryContainer else palette.surfaceContainerLow, 12), null)
            }
            when (tab) {
                SlimTab.DRAWER -> DrawerSlimPanel.build(activity, body)
                SlimTab.BOTTOM -> BottomSlimPanel.build(activity, body)
                SlimTab.MY_PAGE -> MyPageSlimPanel.build(activity, body)
            }
            scroll.post {
                if (generation == token && state.selected == tab && scroll.isAttachedToWindow) scroll.scrollTo(0, restoreY)
            }
        }
        val labels = linkedMapOf(SlimTab.DRAWER to S.TAB_DRAWER, SlimTab.BOTTOM to S.TAB_BOTTOM, SlimTab.MY_PAGE to S.TAB_MY_PAGE)
        for ((tab, label) in labels) {
            val button = UiKit.textButton(activity, label()).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(UiKit.dp(activity, 8), 0, UiKit.dp(activity, 8), 0)
                contentDescription = label()
                setOnClickListener { if (state.select(tab, scroll.scrollY)) render() }
            }
            buttons[tab] = button
            tabs.addView(button, LinearLayout.LayoutParams(0, UiKit.dp(activity, 52), 1f).apply {
                marginStart = UiKit.dp(activity, 4)
                marginEnd = UiKit.dp(activity, 4)
            })
        }
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                state.remember(state.selected, scroll.scrollY)
                generation++
            }
        })
        render()
    }
}
