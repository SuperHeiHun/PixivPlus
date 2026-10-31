package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.ui.UiKit

/** 过滤规则的弹窗编辑器：每行一条，或用逗号 / 分号分隔。 */
object FilterRuleDialog {

    private const val TAG = "FilterRuleDialog"

    fun show(
        activity: Activity,
        title: String,
        example: String,
        initial: String,
        onSave: (String) -> Unit
    ) {
        try {
            val dialog = UiKit.dialog(activity)
            val root = UiKit.dialogSurface(activity)

            root.addView(
                UiKit.dialogHeader(
                    activity,
                    title,
                    "每行一条，也可用逗号 / 分号分隔"
                ) { UiKit.dismissAnimated(dialog, root) }
            )
            root.addView(UiKit.divider(activity))

            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    UiKit.dp(activity, 20),
                    UiKit.dp(activity, 16),
                    UiKit.dp(activity, 20),
                    UiKit.dp(activity, 4)
                )
            }
            val field = UiKit.inputField(
                context = activity,
                value = initial,
                hint = example,
                onChanged = {}
            ).apply {
                minLines = 5
                maxLines = 8
            }
            content.addView(
                field,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            root.addView(content)

            root.addView(UiKit.divider(activity))
            val footer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(
                    UiKit.dp(activity, 16),
                    UiKit.dp(activity, 8),
                    UiKit.dp(activity, 16),
                    UiKit.dp(activity, 12)
                )
            }
            val cancel = UiKit.textButton(activity, "取消").apply {
                setOnClickListener { UiKit.dismissAnimated(dialog, root) }
            }
            val save = UiKit.filledButton(activity, "保存").apply {
                setOnClickListener {
                    val value = field.text.toString().trim()
                    UiKit.dismissAnimated(dialog, root) { onSave(value) }
                }
            }
            footer.addView(
                cancel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    UiKit.dp(activity, 40)
                )
            )
            footer.addView(
                save,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    UiKit.dp(activity, 40)
                ).apply {
                    marginStart = UiKit.dp(activity, 8)
                }
            )
            root.addView(footer)

            dialog.setContentView(root)
            dialog.show()
            UiKit.applyDialogWidth(dialog, 0.92f, 520)
            UiKit.animateDialogIn(root)
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            field.requestFocus()
            field.setSelection(field.text.length)
            ModuleLog.i(TAG, "editor shown")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "show failed", t)
        }
    }
}
