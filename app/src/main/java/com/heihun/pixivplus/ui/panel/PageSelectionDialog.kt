package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.heihun.pixivplus.core.media.MediaModels
import com.heihun.pixivplus.ui.UiKit

/** 1 起始页码选择；错误在原弹窗中显示，取消不会创建任何保存文件。 */
object PageSelectionDialog {
    fun show(activity: Activity, total: Int, onSelect: (List<Int>) -> Unit) {
        val dialog = UiKit.dialog(activity)
        val root = UiKit.dialogSurface(activity)
        root.addView(UiKit.dialogHeader(activity, "自定义保存图片", null) { UiKit.dismissAnimated(dialog, root) })
        root.addView(UiKit.divider(activity))
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 16), UiKit.dp(activity, 20), UiKit.dp(activity, 16))
        }
        content.addView(UiKit.paragraph(activity, "共 $total 张，页码从 1 开始。\n可输入单页或范围，例如：1,3,5-8。"))
        val field = UiKit.inputField(activity, "", "要保存的页码", onChanged = {})
        content.addView(field)
        root.addView(content)
        val footer = LinearLayout(activity).apply {
            gravity = Gravity.END
            setPadding(UiKit.dp(activity, 16), UiKit.dp(activity, 8), UiKit.dp(activity, 16), UiKit.dp(activity, 12))
        }
        footer.addView(UiKit.textButton(activity, "取消").apply {
            setOnClickListener { UiKit.dismissAnimated(dialog, root) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(activity, 40)))
        footer.addView(UiKit.filledButton(activity, "下一步").apply {
            setOnClickListener {
                try {
                    val selected = MediaModels.selectPages(field.text.toString(), total)
                    UiKit.dismissAnimated(dialog, root) { onSelect(selected) }
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
