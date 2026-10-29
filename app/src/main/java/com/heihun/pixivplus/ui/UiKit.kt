package com.heihun.pixivplus.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import com.heihun.pixivplus.ui.theme.Md3
import com.heihun.pixivplus.ui.theme.Md3Colors
import com.heihun.pixivplus.ui.widget.Md3Switch

/**
 * 模块 UI 组件库（Material Design 3 视觉）。
 *
 * 因为 LSPosed 不注入模块资源，这里不使用 Material 库与 XML 主题，
 * 所有形状、状态层、排版均通过代码构建，保证在宿主进程内稳定可用。
 */
object UiKit {

    fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun sp(context: Context, value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            context.resources.displayMetrics
        )
    }

    fun colors(context: Context): Md3Colors = Md3.colors(context)

    fun sectionTitle(context: Context, text: String): TextView {
        val palette = colors(context)
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(palette.primary)
            typeface = Md3.medium
            letterSpacing = 0.01f
            setPadding(dp(context, 20), dp(context, 22), dp(context, 20), dp(context, 8))
        }
    }

    /** 卡片内部的子分区标题。 */
    fun cardSectionTitle(context: Context, text: String): TextView {
        val palette = colors(context)
        return bodyText(context, text, 14f, palette.primary).apply {
            typeface = Md3.medium
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 4))
        }
    }

    fun card(context: Context): LinearLayout {
        val palette = colors(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, palette.surfaceContainerLow, 16)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(context, 16)
                marginEnd = dp(context, 16)
                bottomMargin = dp(context, 12)
            }
        }
    }

    fun switchRow(
        context: Context,
        title: String,
        subtitle: String?,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): View {
        val palette = colors(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 64)
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
            background = rippleRect(context, palette.primary)
            isClickable = true
            isFocusable = true
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, dp(context, 12), 0)
        }
        texts.addView(bodyText(context, title, 16f, palette.onSurface))
        if (!subtitle.isNullOrEmpty()) {
            texts.addView(bodyText(context, subtitle, 13f, palette.onSurfaceVariant).apply {
                setPadding(0, dp(context, 2), 0, 0)
            })
        }
        row.addView(texts)
        val switchView = Md3Switch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        row.addView(switchView)
        row.setOnClickListener { switchView.toggle() }
        return row
    }

    /** 可点按的选择行：上方标签、下方当前值、右侧箭头。 */
    fun selectRow(
        context: Context,
        label: String,
        value: String,
        onClick: () -> Unit
    ): View {
        val palette = colors(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 64)
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
            background = rippleRect(context, palette.primary)
            isClickable = true
            isFocusable = true
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(bodyText(context, label, 13f, palette.onSurfaceVariant))
        texts.addView(bodyText(context, value, 16f, palette.onSurface).apply {
            setPadding(0, dp(context, 2), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts)
        row.addView(bodyText(context, "\u203A", 22f, palette.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), 0, 0, 0)
        })
        row.setOnClickListener { onClick() }
        return row
    }

    /** 页面切换动画：内容从左侧 / 右侧滑入并淡入。 */
    fun animateScreenIn(view: View, fromRight: Boolean) {
        val offset = dp(view.context, 48).toFloat()
        view.translationX = if (fromRight) offset else -offset
        view.alpha = 0f
        view.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /** 轻量淡入，用于标题栏等辅助元素。 */
    fun fadeIn(view: View, durationMs: Long = 180L) {
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(durationMs).start()
    }

    /** 二级菜单入口：标题在上、说明在下，右侧箭头。 */
    fun menuRow(
        context: Context,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): View {
        val palette = colors(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 68)
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
            background = rippleRect(context, palette.primary)
            isClickable = true
            isFocusable = true
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(bodyText(context, title, 16f, palette.onSurface))
        if (subtitle.isNotEmpty()) {
            texts.addView(bodyText(context, subtitle, 13f, palette.onSurfaceVariant).apply {
                setPadding(0, dp(context, 2), 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(texts)
        row.addView(bodyText(context, "\u203A", 22f, palette.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), 0, 0, 0)
        })
        row.setOnClickListener { onClick() }
        return row
    }

    fun infoRow(context: Context, label: String, value: String): LinearLayout {
        val palette = colors(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 48)
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
            addView(bodyText(context, label, 14f, palette.onSurfaceVariant).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(bodyText(context, value, 14f, palette.onSurface).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.END
                setPadding(dp(context, 12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
            })
        }
    }

    fun inputRow(
        context: Context,
        label: String,
        value: String,
        hint: String,
        numeric: Boolean = false,
        password: Boolean = false,
        singleLine: Boolean = false,
        onChanged: (String) -> Unit
    ): LinearLayout {
        val palette = colors(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }.also { row ->
            row.addView(bodyText(context, label, 13f, palette.onSurfaceVariant).apply {
                setPadding(dp(context, 2), 0, 0, dp(context, 6))
            })
            row.addView(inputField(context, value, hint, numeric, password, singleLine, onChanged))
        }
    }

    /** 单行/多行输入框；供设置项与弹窗编辑器复用。 */
    fun inputField(
        context: Context,
        value: String,
        hint: String,
        numeric: Boolean = false,
        password: Boolean = false,
        singleLine: Boolean = false,
        onChanged: (String) -> Unit
    ): EditText {
        val palette = colors(context)
        return EditText(context).apply {
            setText(value)
            setHint(hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(palette.onSurface)
            setHintTextColor(palette.onSurfaceVariant)
            typeface = Md3.regular
            background = textFieldBackground(context, palette)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            minHeight = dp(context, 56)
            gravity = Gravity.TOP or Gravity.START
            highlightColor = withAlpha(palette.primary, 0x33)
            if (numeric) {
                inputType = InputType.TYPE_CLASS_NUMBER
                maxLines = 1
                setSingleLine(true)
            } else if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                maxLines = 1
                setSingleLine(true)
                typeface = Md3.regular
            } else if (singleLine) {
                inputType = InputType.TYPE_CLASS_TEXT
                maxLines = 1
                setSingleLine(true)
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                maxLines = 4
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                textCursorDrawable = textCursorDrawable?.apply {
                    setTint(palette.primary)
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onChanged(s?.toString() ?: "")
                }

                override fun afterTextChanged(s: Editable?) {
                }
            })
        }
    }

    /** 单行按钮容器；默认使用 MD3 tonal 样式（通栏）。 */
    fun buttonRow(context: Context, title: String, onClick: () -> Unit): View {
        return buttonRowInternal(context, title, style = ButtonStyle.TONAL, onClick = onClick)
    }

    fun filledButtonRow(context: Context, title: String, onClick: () -> Unit): View {
        return buttonRowInternal(context, title, style = ButtonStyle.FILLED, onClick = onClick)
    }

    fun dangerButtonRow(context: Context, title: String, onClick: () -> Unit): View {
        return buttonRowInternal(context, title, style = ButtonStyle.DANGER, onClick = onClick)
    }

    private enum class ButtonStyle {
        FILLED,
        TONAL,
        DANGER
    }

    private fun buttonRowInternal(
        context: Context,
        title: String,
        style: ButtonStyle,
        onClick: () -> Unit
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                dp(context, 16),
                dp(context, if (style == ButtonStyle.DANGER) 2 else 8),
                dp(context, 16),
                dp(context, if (style == ButtonStyle.DANGER) 6 else 12)
            )
        }
        val button = when (style) {
            ButtonStyle.FILLED -> filledButton(context, title)
            ButtonStyle.TONAL -> tonalButton(context, title)
            ButtonStyle.DANGER -> dangerTextButton(context, title)
        }
        button.layoutParams = LinearLayout.LayoutParams(
            if (style == ButtonStyle.DANGER) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 40)
        )
        button.setOnClickListener { onClick() }
        container.addView(button)
        return container
    }

    fun filledButton(context: Context, title: String): TextView {
        val palette = colors(context)
        return actionButton(
            context,
            title,
            filled = palette.primary,
            textColor = palette.onPrimary,
            strokeColor = 0,
            rippleColor = palette.onPrimary
        )
    }

    fun tonalButton(context: Context, title: String): TextView {
        val palette = colors(context)
        return actionButton(
            context,
            title,
            filled = palette.secondaryContainer,
            textColor = palette.onSecondaryContainer,
            strokeColor = 0,
            rippleColor = palette.onSecondaryContainer
        )
    }

    fun outlinedButton(context: Context, title: String): TextView {
        val palette = colors(context)
        return actionButton(
            context,
            title,
            filled = Color.TRANSPARENT,
            textColor = palette.primary,
            strokeColor = palette.outline,
            rippleColor = palette.primary
        )
    }

    fun textButton(context: Context, title: String): TextView {
        val palette = colors(context)
        return actionButton(
            context,
            title,
            filled = Color.TRANSPARENT,
            textColor = palette.primary,
            strokeColor = 0,
            rippleColor = palette.primary
        )
    }

    fun dangerTextButton(context: Context, title: String): TextView {
        val palette = colors(context)
        return actionButton(
            context,
            title,
            filled = Color.TRANSPARENT,
            textColor = palette.error,
            strokeColor = 0,
            rippleColor = palette.error
        )
    }

    private fun actionButton(
        context: Context,
        title: String,
        filled: Int,
        textColor: Int,
        strokeColor: Int,
        rippleColor: Int
    ): TextView {
        val radius = dp(context, 20)
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(filled)
            if (strokeColor != 0) setStroke(dp(context, 1), strokeColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(Color.WHITE)
        }
        return TextView(context).apply {
            text = title
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Md3.medium
            setTextColor(textColor)
            letterSpacing = 0.01f
            minHeight = dp(context, 40)
            minWidth = dp(context, 64)
            setPadding(dp(context, 24), dp(context, 10), dp(context, 24), dp(context, 10))
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(rippleColor, 0x1F)),
                content,
                mask
            )
            isClickable = true
            isFocusable = true
        }
    }

    fun divider(context: Context, startDp: Int = 0, endDp: Int = 0): View {
        return View(context).apply {
            setBackgroundColor(colors(context).outlineVariant)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 1)
            ).apply {
                marginStart = dp(context, startDp)
                marginEnd = dp(context, endDp)
            }
        }
    }

    fun dialogHeader(
        context: Context,
        title: String,
        subtitle: String?,
        onClose: (() -> Unit)? = null
    ): View {
        val palette = colors(context)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 64)
            setPadding(dp(context, 24), dp(context, 12), dp(context, 12), dp(context, 12))
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(bodyText(context, title, 22f, palette.onSurface))
        if (!subtitle.isNullOrEmpty()) {
            texts.addView(bodyText(context, subtitle, 13f, palette.onSurfaceVariant).apply {
                setPadding(0, dp(context, 2), 0, 0)
            })
        }
        header.addView(texts)
        if (onClose != null) {
            header.addView(closeButton(context, onClose))
        }
        return header
    }

    fun closeButton(context: Context, onClose: () -> Unit): View {
        val palette = colors(context)
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        return TextView(context).apply {
            text = "\u2715"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(palette.onSurfaceVariant)
            gravity = Gravity.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(palette.onSurfaceVariant, 0x1F)),
                null,
                mask
            )
            layoutParams = LinearLayout.LayoutParams(dp(context, 48), dp(context, 48))
            isClickable = true
            isFocusable = true
            contentDescription = "关闭"
            setOnClickListener { onClose() }
        }
    }

    fun paragraph(context: Context, text: String): TextView {
        val palette = colors(context)
        return bodyText(context, text, 12f, palette.onSurfaceVariant).apply {
            setLineSpacing(dp(context, 2).toFloat(), 1f)
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8))
        }
    }

    fun chip(context: Context, text: String): TextView {
        val palette = colors(context)
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Md3.medium
            setTextColor(palette.onSecondaryContainer)
            background = rounded(context, palette.secondaryContainer, 8)
            setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2))
            gravity = Gravity.CENTER
        }
    }

    fun emptyState(context: Context, text: String): TextView {
        val palette = colors(context)
        return bodyText(context, text, 14f, palette.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 24), dp(context, 32), dp(context, 24), dp(context, 32))
        }
    }

    fun dialog(activity: Activity): Dialog {
        val theme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.R.style.Theme_DeviceDefault_DayNight
        } else {
            android.R.style.Theme_DeviceDefault
        }
        return Dialog(activity, theme).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.32f)
            }
        }
    }

    /** 28dp 圆角的 MD3 对话框/面板表面。 */
    fun dialogSurface(context: Context): LinearLayout {
        val palette = colors(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, palette.surfaceContainerHigh, 28)
            clipToOutline = true
        }
    }

    fun applyDialogWidth(dialog: Dialog, fraction: Float = 0.92f, maxWidthDp: Int = 560) {
        val context = dialog.context
        val metrics = context.resources.displayMetrics
        val width = minOf(dp(context, maxWidthDp), (metrics.widthPixels * fraction).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun styleFullScreenWindow(dialog: Dialog) {
        val palette = colors(dialog.context)
        val window = dialog.window ?: return
        window.statusBarColor = palette.surface
        window.navigationBarColor = palette.surface
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    fun maxHeightScroll(context: Context, maxHeightDp: Int): ScrollView {
        return MaxHeightScrollView(context, dp(context, maxHeightDp))
    }

    fun rounded(context: Context, color: Int, radiusDp: Int, strokeColor: Int = 0, strokeWidthDp: Int = 0): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(color)
            if (strokeColor != 0 && strokeWidthDp > 0) {
                setStroke(dp(context, strokeWidthDp), strokeColor)
            }
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
    }

    private fun bodyText(context: Context, text: String, sizeSp: Float, color: Int): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = Md3.regular
        }
    }

    private fun rippleRect(context: Context, color: Int): Drawable {
        return RippleDrawable(
            ColorStateList.valueOf(withAlpha(color, 0x14)),
            null,
            GradientDrawable().apply { setColor(Color.WHITE) }
        )
    }

    private fun textFieldBackground(context: Context, palette: Md3Colors): Drawable {
        val radius = dp(context, 4).toFloat()
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(dp(context, 1), palette.outline)
        }
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(dp(context, 2), palette.primary)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }
}

private class MaxHeightScrollView(
    context: Context,
    private val maxHeightPx: Int
) : ScrollView(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, spec)
    }
}
