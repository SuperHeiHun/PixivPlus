package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.heihun.pixivplus.core.i18n.CommonText as C
import com.heihun.pixivplus.core.i18n.SettingsText as S
import com.heihun.pixivplus.core.i18n.WorkText as W
import com.heihun.pixivplus.core.media.MediaNames
import com.heihun.pixivplus.ui.UiKit

object MediaNameDialog {
    fun show(activity: Activity, current: String, onSave: (String) -> Unit) {
        val dialog = UiKit.dialog(activity)
        val root = UiKit.dialogSurface(activity)
        root.addView(UiKit.dialogHeader(activity, S.CUSTOM_SAVE_NAME(), null) { UiKit.dismissAnimated(dialog, root) })
        root.addView(UiKit.divider(activity))
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 12), UiKit.dp(activity, 20), UiKit.dp(activity, 12))
        }
        content.addView(UiKit.paragraph(activity, W.FILENAME_INSTRUCTIONS()))
        val field = UiKit.inputField(activity, current, MediaNames.DEFAULT_TEMPLATE, onChanged = {})
        content.addView(field)
        root.addView(content)
        val footer = LinearLayout(activity).apply {
            gravity = Gravity.END
            setPadding(UiKit.dp(activity, 16), UiKit.dp(activity, 8), UiKit.dp(activity, 16), UiKit.dp(activity, 12))
        }
        footer.addView(UiKit.textButton(activity, W.RESET_FILENAME()).apply {
            setOnClickListener { field.setText(MediaNames.DEFAULT_TEMPLATE) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(activity, 40)))
        footer.addView(UiKit.filledButton(activity, C.SAVE()).apply {
            setOnClickListener {
                try {
                    val value = field.text.toString().trim()
                    MediaNames.validateTemplate(value)
                    UiKit.dismissAnimated(dialog, root) { onSave(value) }
                } catch (t: IllegalArgumentException) { field.error = t.message }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(activity, 40)))
        root.addView(footer)
        dialog.setContentView(root)
        dialog.show()
        UiKit.applyDialogWidth(dialog, 0.92f, 520)
        UiKit.animateDialogIn(root)
    }
}
