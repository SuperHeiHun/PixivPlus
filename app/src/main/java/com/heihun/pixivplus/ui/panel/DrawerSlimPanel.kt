package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.widget.LinearLayout
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.SettingsText as S
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.drawer.DrawerSlimFeature
import com.heihun.pixivplus.feature.slim.BottomSlimPolicy
import com.heihun.pixivplus.ui.UiKit

internal object DrawerSlimPanel {
    fun build(activity: Activity, body: LinearLayout) {
        val store = ConfigStoreRegistry.store
        val card = UiKit.card(activity)
        card.addView(UiKit.switchRow(activity, S.SLIM_ENABLE(), S.SLIM_ENABLE_HINT(), store.get(ConfigKeys.DRAWER_SLIM_ENABLED)) {
            store.put(ConfigKeys.DRAWER_SLIM_ENABLED, it)
        })
        card.addView(UiKit.divider(activity, 16, 16))
        card.addView(UiKit.paragraph(activity, S.SLIM_INSTRUCTIONS()))
        body.addView(card)
        val items = UiKit.card(activity)
        items.addView(UiKit.cardSectionTitle(activity, S.SLIM_HIDE_TITLE()))
        val hidden = BottomSlimPolicy.parse(store.get(ConfigKeys.DRAWER_SLIM_ITEMS))
        for ((key, label) in DrawerSlimFeature.DRAWER_ITEM_LABELS) {
            if (items.childCount > 1) items.addView(UiKit.divider(activity, 12, 12))
            items.addView(UiKit.switchRow(activity, label(), S.SLIM_HIDE_ITEM(), key in hidden) { checked ->
                val updated = BottomSlimPolicy.parse(store.get(ConfigKeys.DRAWER_SLIM_ITEMS)).toMutableSet()
                if (checked) updated.add(key) else updated.remove(key)
                store.put(ConfigKeys.DRAWER_SLIM_ITEMS, updated.joinToString(","))
            })
        }
        body.addView(items)
        body.addView(UiKit.paragraph(activity, S.SLIM_GROUP_NOTE()))
    }
}
