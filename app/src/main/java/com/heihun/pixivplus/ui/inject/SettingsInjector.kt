package com.heihun.pixivplus.ui.inject

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.heihun.pixivplus.adapt.anchors.SettingsAnchors
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.ui.UiKit
import com.heihun.pixivplus.ui.panel.SettingsPanelBuilder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object SettingsInjector {

    private const val TAG = "SettingsInjector"
    private const val ROW_TAG = "pixiv_local_plus_settings_entry"

    fun install() {
        try {
            val clazz = ReflectUtil.loadClass(Host.classLoader, SettingsAnchors.SETTING_ACTIVITY_CLASS)
            if (clazz == null) {
                ModuleLog.e(TAG, "settings activity class not found: ${SettingsAnchors.SETTING_ACTIVITY_CLASS}")
                return
            }
            XposedHelpers.findAndHookMethod(clazz, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    inject(activity)
                }
            })
            ModuleLog.i(TAG, "settings hook installed on ${clazz.name}")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "install failed", t)
        }
    }

    fun inject(activity: Activity) {
        try {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val container = resolveContainer(content) ?: run {
                ModuleLog.w(TAG, "no LinearLayout container found")
                return
            }
            val existing: View? = container.findViewWithTag(ROW_TAG)
            if (existing != null) return
            val row = buildEntryRow(activity)
            row.tag = ROW_TAG
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            shrinkFragmentContainer(activity, container)
            ModuleLog.i(TAG, "settings row injected into ${container.javaClass.simpleName}")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "inject failed", t)
        }
    }

    private fun shrinkFragmentContainer(activity: Activity, container: ViewGroup) {
        try {
            val id = activity.resources.getIdentifier(SettingsAnchors.SETTING_CONTAINER_ID, "id", activity.packageName)
            if (id == 0) return
            val target = container.findViewById<View>(id) ?: return
            val params = target.layoutParams
            if (params is LinearLayout.LayoutParams && params.height != 0) {
                params.height = 0
                params.weight = 1f
                target.layoutParams = params
            }
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "container resize skipped", t)
        }
    }

    private fun resolveContainer(content: ViewGroup): ViewGroup? {
        if (content is LinearLayout) return content
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child is LinearLayout) return child
        }
        return content
    }

    private fun buildEntryRow(activity: Activity): LinearLayout {
        val palette = UiKit.colors(activity)
        val titleColor = resolveThemeColor(activity, android.R.attr.textColorPrimary, palette.onSurface)
        val subtitleColor = resolveThemeColor(activity, android.R.attr.textColorSecondary, palette.onSurfaceVariant)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = UiKit.dp(activity, 72)
            setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 12), UiKit.dp(activity, 16), UiKit.dp(activity, 12))
            isClickable = true
            isFocusable = true
            background = resolveSelectableBackground(activity)
                ?: activity.getDrawable(android.R.drawable.list_selector_background)
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(activity).apply {
            text = "PixivPlus"
            textSize = 16f
            setTextColor(titleColor)
            typeface = com.heihun.pixivplus.ui.theme.Md3.medium
        })
        texts.addView(TextView(activity).apply {
            text = "本地历史 / 过滤 / Premium / 去广告"
            textSize = 12f
            setTextColor(subtitleColor)
            setPadding(0, UiKit.dp(activity, 2), 0, 0)
        })
        row.addView(texts)
        row.addView(TextView(activity).apply {
            text = "\u203A"
            textSize = 22f
            setTextColor(subtitleColor)
            gravity = Gravity.CENTER
            setPadding(UiKit.dp(activity, 12), 0, 0, 0)
        })
        row.setOnClickListener { openPanel(activity) }
        row.setOnLongClickListener {
            openPanel(activity)
            true
        }
        return row
    }

    private fun resolveThemeColor(activity: Activity, attr: Int, fallback: Int): Int {
        return try {
            val value = android.util.TypedValue()
            if (!activity.theme.resolveAttribute(attr, value, true)) return fallback
            val resId = value.resourceId
            if (resId != 0) {
                activity.resources.getColorStateList(resId, activity.theme)?.defaultColor ?: fallback
            } else if (value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
                value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT
            ) {
                value.data
            } else {
                fallback
            }
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun resolveSelectableBackground(activity: Activity): android.graphics.drawable.Drawable? {
        return try {
            val value = android.util.TypedValue()
            if (!activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
                return null
            }
            val resId = value.resourceId
            if (resId != 0) activity.getDrawable(resId) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun openPanel(activity: Activity) {
        try {
            if (activity.isFinishing) return
            SettingsPanelBuilder.show(activity)
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "open panel failed", t)
        }
    }
}
