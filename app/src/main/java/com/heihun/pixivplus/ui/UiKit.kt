package com.heihun.pixivplus.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.heihun.pixivplus.ui.theme.CharcoalBridge

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

    fun sectionTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(CharcoalBridge.brandColor(context))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 6))
        }
    }

    fun infoRow(context: Context, label: String, value: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8))
            addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
            })
        }
    }

    fun switchRow(context: Context, title: String, subtitle: String?, checked: Boolean, onChanged: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 20), dp(context, 10), dp(context, 12), dp(context, 10))
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            if (!subtitle.isNullOrEmpty()) {
                addView(TextView(context).apply {
                    text = subtitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    alpha = 0.7f
                })
            }
        }
        row.addView(texts)
        val switchView = CharcoalBridge.newSwitch(context)
        if (switchView is android.widget.CompoundButton) {
            switchView.isChecked = checked
            switchView.setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        row.addView(switchView)
        return row
    }

    fun inputRow(
        context: Context,
        label: String,
        value: String,
        hint: String,
        onChanged: (String) -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 6), dp(context, 20), dp(context, 6))
        }
        row.addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.75f
        })
        row.addView(EditText(context).apply {
            setText(value)
            setHint(hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 3
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onChanged(s?.toString() ?: "")
                }

                override fun afterTextChanged(s: android.text.Editable?) {
                }
            })
        })
        return row
    }

    fun buttonRow(context: Context, title: String, onClick: () -> Unit): View {
        val button = CharcoalBridge.newButton(context, title)
        button.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        button.setOnClickListener { onClick() }
        return button
    }

    fun divider(context: Context): View {
        return View(context).apply {
            setBackgroundColor(CharcoalBridge.dividerColor(context))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 1)
            )
        }
    }

    fun plainButton(context: Context, title: String, onClick: () -> Unit): Button {
        return Button(context).apply {
            text = title
            setOnClickListener { onClick() }
        }
    }
}
