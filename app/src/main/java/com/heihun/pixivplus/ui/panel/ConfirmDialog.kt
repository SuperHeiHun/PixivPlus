package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.ui.UiKit
import com.heihun.pixivplus.ui.theme.Md3

/** 通用二次确认弹窗。 */
object ConfirmDialog {

    private const val TAG = "ConfirmDialog"

    fun show(
        activity: Activity,
        title: String,
        message: String,
        confirmLabel: String = "确定",
        danger: Boolean = false,
        onConfirm: () -> Unit
    ) {
        try {
            val dialog = UiKit.dialog(activity)
            val palette = UiKit.colors(activity)
            val root = UiKit.dialogSurface(activity)

            root.addView(UiKit.dialogHeader(activity, title, null) { dialog.dismiss() })
            root.addView(UiKit.divider(activity))
            root.addView(TextView(activity).apply {
                text = message
                textSize = 14f
                setTextColor(palette.onSurfaceVariant)
                typeface = Md3.regular
                setLineSpacing(UiKit.dp(activity, 2).toFloat(), 1f)
                setPadding(
                    UiKit.dp(activity, 24),
                    UiKit.dp(activity, 16),
                    UiKit.dp(activity, 24),
                    UiKit.dp(activity, 16)
                )
            })

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
                setOnClickListener { dialog.dismiss() }
            }
            val confirm = (if (danger) {
                UiKit.dangerTextButton(activity, confirmLabel)
            } else {
                UiKit.filledButton(activity, confirmLabel)
            }).apply {
                setOnClickListener {
                    dialog.dismiss()
                    onConfirm()
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
                confirm,
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
            UiKit.applyDialogWidth(dialog, 0.9f, 460)
            ModuleLog.i(TAG, "confirm shown: $title")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "show failed", t)
        }
    }
}
