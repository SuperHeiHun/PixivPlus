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
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 14), UiKit.dp(activity, 20), UiKit.dp(activity, 20))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        row.addView(TextView(activity).apply {
            text = "PixivLocalPlus"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(activity).apply {
            text = "本地历史 / 过滤 / Premium / 去广告"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = 0.65f
        })
        row.setOnClickListener { openPanel(activity) }
        row.setOnLongClickListener {
            openPanel(activity)
            true
        }
        return row
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
