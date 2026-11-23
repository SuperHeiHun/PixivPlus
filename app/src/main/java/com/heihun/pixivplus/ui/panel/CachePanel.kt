package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.heihun.pixivplus.core.i18n.CommonText as C
import com.heihun.pixivplus.core.i18n.PanelText as P
import com.heihun.pixivplus.core.media.ModuleCache
import com.heihun.pixivplus.ui.UiKit

object CachePanel {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "plp-cache-clean").apply { isDaemon = true } }
    fun build(activity: Activity, body: LinearLayout) {
        val card = UiKit.card(activity)
        val size = UiKit.infoRow(activity, P.DISK_CACHE(), P.COUNTING_CACHE())
        card.addView(size)
        fun refresh() { executor.execute {
            val bytes = try { ModuleCache.size(activity.applicationContext) } catch (_: Exception) { 0L }
            activity.runOnUiThread { (size.getChildAt(1) as? TextView)?.text = android.text.format.Formatter.formatFileSize(activity, bytes) }
        } }
        card.addView(UiKit.paragraph(activity, P.CACHE_CLEAN_HINT()))
        card.addView(UiKit.filledButtonRow(activity, P.CACHE_CLEAN()) {
            ConfirmDialog.show(activity, P.CACHE_CLEAN(), P.CACHE_CONFIRM(), C.CLEAN()) {
                executor.execute {
                    val message = try {
                        val bytes = ModuleCache.clear(activity.applicationContext)
                        P.CACHE_CLEANED(android.text.format.Formatter.formatFileSize(activity, bytes))
                    } catch (t: Exception) { t.message ?: P.CACHE_CLEAN_FAILED() }
                    activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); refresh() }
                }
            }
        })
        body.addView(card)
        refresh()
    }
}
