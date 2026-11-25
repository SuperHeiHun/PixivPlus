package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.widget.LinearLayout
import android.widget.Toast
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.SlimText as S
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.slim.BottomSlimFeature
import com.heihun.pixivplus.feature.slim.BottomSlimPolicy
import com.heihun.pixivplus.ui.UiKit

internal object BottomSlimPanel {
    fun build(activity: Activity, body: LinearLayout) {
        val store = ConfigStoreRegistry.store
        val card = UiKit.card(activity)
        card.addView(UiKit.switchRow(activity, S.ENABLE(), S.BOTTOM_HINT(), store.get(ConfigKeys.BOTTOM_SLIM_ENABLED)) {
            store.put(ConfigKeys.BOTTOM_SLIM_ENABLED, it)
        })
        card.addView(UiKit.paragraph(activity, S.BOTTOM_HELP()))
        body.addView(card)
        val items = UiKit.card(activity)
        items.addView(UiKit.cardSectionTitle(activity, S.ITEMS()))
        fun render() {
            while (items.childCount > 1) items.removeViewAt(1)
            val hidden = BottomSlimPolicy.parse(store.get(ConfigKeys.BOTTOM_SLIM_ITEMS))
            for ((key, label) in BottomSlimFeature.labels) {
                items.addView(UiKit.switchRow(activity, label(), S.HIDE(), key in hidden) { checked ->
                    val updated = BottomSlimPolicy.parse(store.get(ConfigKeys.BOTTOM_SLIM_ITEMS)).toMutableSet()
                    if (checked) updated.add(key) else updated.remove(key)
                    if (!BottomSlimPolicy.canHide(updated)) {
                        Toast.makeText(activity, S.KEEP_ONE(), Toast.LENGTH_LONG).show()
                    } else store.put(ConfigKeys.BOTTOM_SLIM_ITEMS, updated.joinToString(","))
                    items.post { render() }
                })
            }
        }
        render()
        body.addView(items)
    }
}
