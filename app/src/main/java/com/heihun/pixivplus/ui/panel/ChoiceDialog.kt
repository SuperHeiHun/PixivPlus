package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import com.heihun.pixivplus.ui.UiKit
import com.heihun.pixivplus.ui.theme.Md3
import com.heihun.pixivplus.ui.theme.Md3Colors

object ChoiceDialog {

    private const val MAX_LIST_HEIGHT_DP = 420

    fun show(
        activity: Activity,
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        try {
            val dialog = UiKit.dialog(activity)
            val palette = UiKit.colors(activity)
            val root = UiKit.dialogSurface(activity)

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
                text = title
                textSize = 22f
                setTextColor(palette.onSurface)
                typeface = Md3.medium
            })
            texts.addView(TextView(activity).apply {
                text = "点按选择"
                textSize = 13f
                setTextColor(palette.onSurfaceVariant)
                setPadding(0, UiKit.dp(activity, 2), 0, 0)
            })
            header.addView(texts)
            header.addView(UiKit.closeButton(activity) { dialog.dismiss() })
            root.addView(header)
            root.addView(UiKit.divider(activity))

            val list = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiKit.dp(activity, 8), UiKit.dp(activity, 4), UiKit.dp(activity, 8), UiKit.dp(activity, 4))
            }
            for (index in options.indices) {
                val radio = RadioButton(activity).apply {
                    isChecked = index == selectedIndex
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
                    minimumHeight = UiKit.dp(activity, 56)
                    setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 2), UiKit.dp(activity, 16), UiKit.dp(activity, 2))
                    background = rowBackground(activity, palette)
                    isClickable = true
                    isFocusable = true
                }
                row.addView(radio)
                row.addView(TextView(activity).apply {
                    text = options[index]
                    textSize = 16f
                    setTextColor(palette.onSurface)
                    setPadding(UiKit.dp(activity, 4), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.setOnClickListener {
                    dialog.dismiss()
                    onSelect(index)
                }
                list.addView(row)
            }

            val scroll = UiKit.maxHeightScroll(activity, maxListHeightDp(activity)).apply {
                isFillViewport = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(list)
            }
            root.addView(scroll)
            root.addView(View(activity).apply { minimumHeight = UiKit.dp(activity, 8) })

            dialog.setContentView(root)
            dialog.show()
            UiKit.applyDialogWidth(dialog, 0.92f, 520)
        } catch (_: Throwable) {
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
        val cap = (metrics.heightPixels * 0.55f / density).toInt()
        return minOf(MAX_LIST_HEIGHT_DP, cap.coerceAtLeast(160))
    }
}
