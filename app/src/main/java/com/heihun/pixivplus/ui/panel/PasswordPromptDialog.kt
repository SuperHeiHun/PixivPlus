package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import com.heihun.pixivplus.core.i18n.BackupText as B
import com.heihun.pixivplus.core.i18n.CommonText as C
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.ui.UiKit

/** 单行密码输入弹窗（用于恢复加密备份）。 */
object PasswordPromptDialog {

    private const val TAG = "PasswordPromptDialog"

    fun show(activity: Activity, title: String, onConfirm: (String) -> Unit) {
        try {
            val dialog = UiKit.dialog(activity)
            val root = UiKit.dialogSurface(activity)

            root.addView(
                UiKit.dialogHeader(activity, title, null) { UiKit.dismissAnimated(dialog, root) }
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
                value = "",
                hint = B.BACKUP_PASSWORD(),
                password = true,
                onChanged = {}
            )
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
            val cancel = UiKit.textButton(activity, C.CANCEL()).apply {
                setOnClickListener { UiKit.dismissAnimated(dialog, root) }
            }
            val confirm = UiKit.filledButton(activity, C.CONFIRM()).apply {
                setOnClickListener {
                    val value = field.text.toString()
                    if (value.isEmpty()) return@setOnClickListener
                    UiKit.dismissAnimated(dialog, root) { onConfirm(value) }
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
            UiKit.animateDialogIn(root)
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
            field.requestFocus()
            ModuleLog.i(TAG, "password prompt shown")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "show failed", t)
        }
    }
}
