package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.ui.UiKit
import com.heihun.pixivplus.ui.theme.Md3
import com.heihun.pixivplus.ui.theme.Md3Colors

object TagSelectDialog {

    private const val TAG = "TagSelectDialog"
    private const val MAX_LIST_HEIGHT_DP = 380

    fun show(activity: Activity, tags: List<String>, onConfirm: (List<String>) -> Unit) {
        try {
            val dialog = UiKit.dialog(activity)
            val palette = UiKit.colors(activity)
            val checked = BooleanArray(tags.size)

            val root = UiKit.dialogSurface(activity).apply {
                setPadding(0, 0, 0, 0)
            }

            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(UiKit.dp(activity, 24), UiKit.dp(activity, 16), UiKit.dp(activity, 12), UiKit.dp(activity, 12))
            }
            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(activity).apply {
                text = "选择要屏蔽的标签"
                textSize = 22f
                setTextColor(palette.onSurface)
                typeface = Md3.medium
            })
            val summary = TextView(activity).apply {
                text = "已选择 0 / ${tags.size}"
                textSize = 13f
                setTextColor(palette.onSurfaceVariant)
                setPadding(0, UiKit.dp(activity, 2), 0, 0)
            }
            texts.addView(summary)
            header.addView(texts)
            header.addView(UiKit.closeButton(activity) { dialog.dismiss() })
            root.addView(header)
            root.addView(UiKit.divider(activity))

            val listContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiKit.dp(activity, 8), UiKit.dp(activity, 4), UiKit.dp(activity, 8), UiKit.dp(activity, 4))
            }
            for (index in tags.indices) {
                val checkbox = CheckBox(activity).apply {
                    isChecked = false
                    isClickable = false
                    isFocusable = false
                    buttonTintList = ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf()
                        ),
                        intArrayOf(palette.primary, palette.onSurfaceVariant)
                    )
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = UiKit.dp(activity, 52)
                    setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 2), UiKit.dp(activity, 16), UiKit.dp(activity, 2))
                    background = rowBackground(activity, palette)
                    isClickable = true
                    isFocusable = true
                }
                row.addView(checkbox)
                row.addView(TextView(activity).apply {
                    text = tags[index]
                    textSize = 15f
                    setTextColor(palette.onSurface)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(UiKit.dp(activity, 4), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.setOnClickListener {
                    checked[index] = !checked[index]
                    checkbox.isChecked = checked[index]
                    summary.text = "已选择 ${checked.count { it }} / ${tags.size}"
                }
                listContainer.addView(row)
            }

            val scroll = UiKit.maxHeightScroll(activity, maxListHeightDp(activity)).apply {
                isFillViewport = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(listContainer)
            }
            root.addView(scroll)

            root.addView(UiKit.divider(activity))
            val footer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(UiKit.dp(activity, 16), UiKit.dp(activity, 8), UiKit.dp(activity, 16), UiKit.dp(activity, 12))
            }
            val cancel = UiKit.textButton(activity, "取消").apply {
                setOnClickListener { dialog.dismiss() }
            }
            val confirm = UiKit.filledButton(activity, "保存").apply {
                setOnClickListener {
                    val selected = tags.filterIndexed { index, _ -> checked[index] }
                    dialog.dismiss()
                    onConfirm(selected)
                }
            }
            footer.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(activity, 40)))
            footer.addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(activity, 40)).apply {
                marginStart = UiKit.dp(activity, 8)
            })
            root.addView(footer)

            dialog.setContentView(root)
            dialog.show()
            UiKit.applyDialogWidth(dialog, 0.92f, 520)
            ModuleLog.i(TAG, "tag dialog shown (${tags.size} tags)")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "show failed", t)
        }
    }

    private fun rowBackground(context: Context, palette: Md3Colors): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiKit.dp(context, 12).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(UiKit.withAlpha(palette.primary, 0x14)),
            null,
            mask
        )
    }

    private fun maxListHeightDp(activity: Activity): Int {
        val metrics = activity.resources.displayMetrics
        val density = metrics.density.takeIf { it > 0f } ?: 1f
        val cap = (metrics.heightPixels * 0.5f / density).toInt()
        return minOf(MAX_LIST_HEIGHT_DP, cap.coerceAtLeast(160))
    }
}
