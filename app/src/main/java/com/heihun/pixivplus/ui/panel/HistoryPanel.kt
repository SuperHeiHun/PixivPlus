package com.heihun.pixivplus.ui.panel

import android.content.Context
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.ui.UiKit

object HistoryPanel {

    private const val TAG = "HistoryPanel"
    private const val MAX_ROWS = 15

    fun build(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun refresh() {
            listContainer.removeAllViews()
            val dao = Runtime.historyDaoOrNull()
            if (dao == null) {
                listContainer.addView(UiKit.infoRow(context, "状态", "数据库未初始化"))
                return
            }
            val total = dao.count(null)
            val items = dao.queryAll(MAX_ROWS)
            listContainer.addView(UiKit.infoRow(context, "记录条数", total.toString()))
            listContainer.addView(UiKit.infoRow(context, "保留策略", retentionLabel()))
            if (items.isEmpty()) {
                listContainer.addView(UiKit.infoRow(context, "最近记录", "暂无记录"))
            } else {
                for (item in items) {
                    listContainer.addView(buildRow(context, item.title, item.authorName, item.type, item.visitedAt))
                }
            }
        }

        refresh()
        container.addView(listContainer)
        container.addView(
            UiKit.buttonRow(context, "清空全部历史") {
                try {
                    val removed = Runtime.historyDaoOrNull()?.clear(null) ?: 0
                    Toast.makeText(context, "已清空 $removed 条记录", Toast.LENGTH_SHORT).show()
                    ModuleLog.i(TAG, "history cleared by user ($removed rows)")
                    refresh()
                } catch (t: Throwable) {
                    ModuleLog.e(TAG, "clear history failed", t)
                }
            }
        )
        return container
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
}
