package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.ui.UiKit
import com.heihun.pixivplus.ui.theme.CharcoalBridge

object HistoryPanel {

    private const val TAG = "HistoryPanel"
    private const val MAX_ROWS = 100
    private const val ROW_HEIGHT_DP = 64
    private const val CHROME_HEIGHT_DP = 210
    private const val MAX_LIST_HEIGHT_DP = 360

    fun build(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val dao = Runtime.historyDaoOrNull()
        val total = dao?.count(null) ?: 0
        container.addView(UiKit.infoRow(context, "记录条数", total.toString()))
        container.addView(UiKit.infoRow(context, "保留策略", retentionLabel()))
        container.addView(
            UiKit.buttonRow(context, "查看浏览历史") {
                val activity = context as? Activity
                if (activity == null) {
                    Toast.makeText(context, "当前页面无法打开历史列表", Toast.LENGTH_SHORT).show()
                } else {
                    showDialog(activity)
                }
            }
        )
        return container
    }

    fun showDialog(activity: Activity) {
        try {
            val theme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.R.style.Theme_DeviceDefault_DayNight
            } else {
                android.R.style.Theme_DeviceDefault
            }
            val dialog = Dialog(activity, theme)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val (content, itemCount) = buildDialogContent(activity, dialog)
            dialog.setContentView(content)
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
            applyWindowSize(activity, dialog, itemCount)
            ModuleLog.i(TAG, "history dialog shown")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "show dialog failed", t)
        }
    }

    private fun buildDialogContent(activity: Activity, dialog: Dialog): Pair<View, Int> {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, UiKit.dp(activity, 12))
        }
        root.addView(buildHeader(activity, dialog))
        root.addView(UiKit.divider(activity))

        val countRow = UiKit.infoRow(activity, "记录条数", "-")
        val countValue = countRow.getChildAt(1) as? TextView
        root.addView(countRow)
        root.addView(UiKit.infoRow(activity, "保留策略", retentionLabel()))
        root.addView(UiKit.divider(activity))

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        var itemCount = 0

        fun refresh() {
            listContainer.removeAllViews()
            val dao = Runtime.historyDaoOrNull()
            val total = dao?.count(null) ?: 0
            countValue?.text = total.toString()
            if (dao == null) {
                itemCount = 0
                listContainer.addView(UiKit.infoRow(activity, "状态", "数据库未初始化"))
                return
            }
            val items = dao.queryAll(MAX_ROWS)
            itemCount = items.size
            if (items.isEmpty()) {
                listContainer.addView(UiKit.infoRow(activity, "最近记录", "暂无记录"))
            } else {
                for (item in items) {
                    listContainer.addView(buildRow(activity, item.title, item.authorName, item.type, item.visitedAt))
                }
            }
        }

        refresh()

        root.addView(UiKit.divider(activity))
        root.addView(
            UiKit.buttonRow(activity, "清空全部历史") {
                try {
                    val removed = Runtime.historyDaoOrNull()?.clear(null) ?: 0
                    Toast.makeText(activity, "已清空 $removed 条记录", Toast.LENGTH_SHORT).show()
                    ModuleLog.i(TAG, "history cleared by user ($removed rows)")
                    refresh()
                } catch (t: Throwable) {
                    ModuleLog.e(TAG, "clear history failed", t)
                }
            }
        )
        return root to itemCount
    }

    private fun buildHeader(activity: Activity, dialog: Dialog): View {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 14), UiKit.dp(activity, 8), UiKit.dp(activity, 14))
        }
        header.addView(TextView(activity).apply {
            text = "浏览历史"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(activity).apply {
            text = "关闭"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(CharcoalBridge.brandColor(activity))
            setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 8), UiKit.dp(activity, 12), UiKit.dp(activity, 8))
            setOnClickListener { dialog.dismiss() }
        })
        return header
    }

    private fun applyWindowSize(activity: Activity, dialog: Dialog, itemCount: Int) {
        val metrics = activity.resources.displayMetrics
        val width = (metrics.widthPixels * 0.88f).toInt()
        val listHeight = UiKit.dp(
            activity,
            (itemCount * ROW_HEIGHT_DP).coerceIn(ROW_HEIGHT_DP, MAX_LIST_HEIGHT_DP)
        )
        val desired = UiKit.dp(activity, CHROME_HEIGHT_DP) + listHeight
        val maxHeight = (metrics.heightPixels * 0.8f).toInt()
        val window = dialog.window ?: return
        val background = GradientDrawable().apply {
            cornerRadius = UiKit.dp(activity, 16).toFloat()
            setColor(backgroundColor(activity))
        }
        window.setBackgroundDrawable(background)
        window.setLayout(width, desired.coerceAtMost(maxHeight))
    }

    private fun buildRow(
        context: Context,
        title: String?,
        author: String?,
        type: String,
        visitedAt: Long
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 20), UiKit.dp(context, 6), UiKit.dp(context, 20), UiKit.dp(context, 6))
        }
        val label = if (title.isNullOrBlank()) "(无标题)" else title
        row.addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val meta = buildString {
            append(typeLabel(type))
            if (!author.isNullOrBlank()) {
                append(" · ")
                append(author)
            }
            append(" · ")
            append(DateUtils.getRelativeTimeSpanString(visitedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        }
        row.addView(TextView(context).apply {
            text = meta
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
            gravity = Gravity.START
        })
        return row
    }

    private fun typeLabel(type: String): String {
        return when (type) {
            "MANGA" -> "漫画"
            "NOVEL" -> "小说"
            else -> "插画"
        }
    }

    private fun retentionLabel(): String {
        return try {
            val days = ConfigStoreRegistry.store.get(ConfigKeys.HISTORY_RETENTION_DAYS).toIntOrNull() ?: 30
            if (days <= 0) "永久保留" else "$days 天"
        } catch (_: Throwable) {
            "未知"
        }
    }

    private fun backgroundColor(activity: Activity): Int {
        return try {
            val typedValue = TypedValue()
            if (activity.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
                typedValue.data
            } else {
                Color.WHITE
            }
        } catch (_: Throwable) {
            Color.WHITE
        }
    }
}
