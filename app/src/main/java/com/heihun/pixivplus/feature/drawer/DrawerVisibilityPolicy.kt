package com.heihun.pixivplus.feature.drawer

internal object DrawerVisibilityPolicy {
    fun mustHide(entryName: String, slim: Boolean, hidden: Set<String>, taskEnabled: Boolean): Boolean {
        if (!entryName.startsWith("drawer_menu_")) return false
        return (slim && entryName in hidden) ||
            (entryName == "drawer_menu_pixivplus_tasks" && !taskEnabled)
    }
}
