package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.heihun.pixivplus.core.media.MediaNames
import com.heihun.pixivplus.ui.UiKit

object MediaNameDialog {
    fun show(activity: Activity, current: String, onSave: (String) -> Unit) {
        val dialog = UiKit.dialog(activity)
        val root = UiKit.dialogSurface(activity)
        root.addView(UiKit.dialogHeader(activity, "自定义保存命名", null) { UiKit.dismissAnimated(dialog, root) })
        root.addView(UiKit.divider(activity))
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 12), UiKit.dp(activity, 20), UiKit.dp(activity, 12))
        }
        content.addView(UiKit.paragraph(activity, "可使用 {author} 作者、{title} 标题、{id} 作品 ID，也可输入固定名称。\n图片自动追加 _p页码，ZIP、GIF 和文件夹使用同一基础名称。"))
        val field = UiKit.inputField(activity, current, MediaNames.DEFAULT_TEMPLATE, onChanged = {})
        content.addView(field)
        root.addView(content)
        val footer = LinearLayout(activity).apply {
            gravity = Gravity.END
            setPadding(UiKit.dp(activity, 16), UiKit.dp(activity, 8), UiKit.dp(activity, 16), UiKit.dp(activity, 12))
        }
        footer.addView(UiKit.textButton(activity, "恢复默认").apply {
            setOnClickListener { field.setText(MediaNames.DEFAULT_TEMPLATE) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(activity, 40)))
        footer.addView(UiKit.filledButton(activity, "保存").apply {
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
