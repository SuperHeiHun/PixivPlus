package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.db.HistoryDao
import com.heihun.pixivplus.core.i18n.CommonText as C
import com.heihun.pixivplus.core.i18n.I18n
import com.heihun.pixivplus.core.i18n.PanelText as P
import com.heihun.pixivplus.core.i18n.SettingsText as S
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.ui.UiKit
import com.heihun.pixivplus.ui.theme.Md3

object HistoryPanel {

    private const val TAG = "HistoryPanel"
    private const val MAX_ROWS = 100
    private const val MAX_LIST_HEIGHT_DP = 360

    fun build(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val dao = Runtime.historyDaoOrNull()
        val total = dao?.count(null) ?: 0
        container.addView(UiKit.infoRow(context, P.HISTORY_COUNT(), total.toString()))
        container.addView(UiKit.infoRow(context, P.RETENTION_POLICY(), retentionLabel()))
        container.addView(UiKit.divider(context, 16, 16))
        container.addView(
            UiKit.buttonRow(context, P.OPEN_HISTORY()) {
                val activity = context as? Activity
                if (activity == null) {
                    Toast.makeText(context, P.HISTORY_UNAVAILABLE(), Toast.LENGTH_SHORT).show()
                } else {
                    showDialog(activity)
                }
            }
        )
        return container
    }

    fun showDialog(activity: Activity) {
        try {
            val dialog = UiKit.dialog(activity)
            val content = buildDialogContent(activity, dialog)
            dialog.setContentView(content)
            dialog.show()
            UiKit.applyDialogWidth(dialog, 0.92f, 520)
            UiKit.animateDialogIn(content)
            ModuleLog.i(TAG, "history dialog shown")
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "show dialog failed", t)
        }
    }

    private fun buildDialogContent(activity: Activity, dialog: Dialog): View {
        val palette = UiKit.colors(activity)
        val root = UiKit.dialogSurface(activity).apply {
            setPadding(0, 0, 0, UiKit.dp(activity, 8))
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
            text = S.HISTORY()
            textSize = 22f
            setTextColor(palette.onSurface)
            typeface = Md3.medium
        })
        val summary = TextView(activity).apply {
            textSize = 13f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, UiKit.dp(activity, 2), 0, 0)
        }
        texts.addView(summary)
        header.addView(texts)
        header.addView(UiKit.closeButton(activity) { UiKit.dismissAnimated(dialog, root) })
        root.addView(header)
        root.addView(UiKit.divider(activity))

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, UiKit.dp(activity, 4), 0, UiKit.dp(activity, 4))
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
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiKit.dp(activity, 16), UiKit.dp(activity, 4), UiKit.dp(activity, 16), UiKit.dp(activity, 4))
        }
        val clearButton = UiKit.dangerTextButton(activity, P.CLEAR_HISTORY())
        clearButton.setOnClickListener {
            try {
                val removed = Runtime.historyDaoOrNull()?.clear(null) ?: 0
                Toast.makeText(activity, P.HISTORY_CLEARED(removed), Toast.LENGTH_SHORT).show()
                ModuleLog.i(TAG, "history cleared by user ($removed rows)")
                refresh(activity, listContainer, summary)
            } catch (t: Throwable) {
                ModuleLog.e(TAG, "clear history failed", t)
            }
        }
        footer.addView(clearButton)
        root.addView(footer)

        refresh(activity, listContainer, summary)
        return root
    }

    private fun refresh(activity: Activity, listContainer: LinearLayout, summary: TextView) {
        listContainer.removeAllViews()
        val dao = Runtime.historyDaoOrNull()
        val total = dao?.count(null) ?: 0
        summary.text = if (total == 0) {
            P.EMPTY_HISTORY_SUMMARY(retentionLabel())
        } else {
            P.HISTORY_SUMMARY(total, retentionLabel())
        }
        if (dao == null) {
            listContainer.addView(UiKit.emptyState(activity, P.DATABASE_UNAVAILABLE()))
            return
        }
        val items = dao.queryAll(MAX_ROWS)
        if (items.isEmpty()) {
            listContainer.addView(UiKit.emptyState(activity, P.NO_BROWSING_HISTORY()))
            return
        }
        for (item in items) {
            listContainer.addView(buildRow(activity, item))
        }
    }

    private fun buildRow(context: Context, item: HistoryDao.HistoryItem): View {
        val palette = UiKit.colors(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 20), UiKit.dp(context, 10), UiKit.dp(context, 20), UiKit.dp(context, 10))
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(UiKit.chip(context, typeLabel(item.type)))
        val label = item.title?.takeIf { it.isNotBlank() } ?: P.UNTITLED()
        top.addView(TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(palette.onSurface)
            typeface = Md3.medium
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(UiKit.dp(context, 8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(top)

        val meta = buildString {
            if (!item.authorName.isNullOrBlank()) {
                append(item.authorName)
                append(" · ")
            }
            append(I18n.dateTime(item.visitedAt))
        }
        row.addView(TextView(context).apply {
            text = meta
            textSize = 12f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, UiKit.dp(context, 4), 0, 0)
        })
        return row
    }

    private fun maxListHeightDp(activity: Activity): Int {
        val metrics = activity.resources.displayMetrics
        val density = metrics.density.takeIf { it > 0f } ?: 1f
        val cap = (metrics.heightPixels * 0.52f / density).toInt()
        return minOf(MAX_LIST_HEIGHT_DP, cap.coerceAtLeast(160))
    }

    private fun typeLabel(type: String): String {
        return when (type) {
            "MANGA" -> P.MANGA()
            "NOVEL" -> P.NOVEL()
            else -> P.ILLUSTRATION()
        }
    }

    private fun retentionLabel(): String {
        return try {
            val days = ConfigStoreRegistry.store.get(ConfigKeys.HISTORY_RETENTION_DAYS).toIntOrNull() ?: 30
            if (days <= 0) S.KEEP_FOREVER() else P.DAYS(days)
        } catch (_: Throwable) {
            C.UNKNOWN()
        }
    }
}
