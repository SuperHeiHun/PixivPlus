package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.widget.LinearLayout
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.i18n.SlimText as S
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.slim.BottomSlimPolicy
import com.heihun.pixivplus.feature.slim.MyPageSlimFeature
import com.heihun.pixivplus.feature.slim.MyPageSlimPolicy
import com.heihun.pixivplus.ui.UiKit

internal object MyPageSlimPanel {
    fun build(activity: Activity, body: LinearLayout) {
        val store = ConfigStoreRegistry.store
        val card = UiKit.card(activity)
        card.addView(UiKit.switchRow(activity, S.ENABLE(), S.MY_PAGE_HINT(), store.get(ConfigKeys.MY_PAGE_SLIM_ENABLED)) {
            store.put(ConfigKeys.MY_PAGE_SLIM_ENABLED, it)
        })
        card.addView(UiKit.paragraph(activity, S.MY_PAGE_HELP()))
        body.addView(card)
        val items = UiKit.card(activity)
        items.addView(UiKit.cardSectionTitle(activity, S.ITEMS()))
        val hidden = BottomSlimPolicy.parse(store.get(ConfigKeys.MY_PAGE_SLIM_ITEMS))
        for ((key, label) in MyPageSlimFeature.labels) {
            items.addView(UiKit.switchRow(activity, label(), S.HIDE(), key in hidden) { checked ->
                val updated = BottomSlimPolicy.parse(store.get(ConfigKeys.MY_PAGE_SLIM_ITEMS)).toMutableSet()
                if (checked) updated.add(key) else updated.remove(key)
                store.put(ConfigKeys.MY_PAGE_SLIM_ITEMS, updated.intersect(MyPageSlimPolicy.entries).joinToString(","))
            })
        }
        body.addView(items)
    }
}
