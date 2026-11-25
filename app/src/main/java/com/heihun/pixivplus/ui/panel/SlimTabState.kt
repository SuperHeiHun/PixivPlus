package com.heihun.pixivplus.ui.panel

internal enum class SlimTab { DRAWER, BOTTOM, MY_PAGE }

/** 仅在当前面板会话内保存标签/滚动位置，不新增配置，不进入备份。 */
internal class SlimTabState {
    var selected: SlimTab = SlimTab.DRAWER
        private set
    private val positions = HashMap<SlimTab, Int>()

    fun select(tab: SlimTab, currentScrollY: Int): Boolean {
        if (selected == tab) return false
        remember(selected, currentScrollY)
        selected = tab
        return true
    }

    fun remember(tab: SlimTab, scrollY: Int) { positions[tab] = scrollY.coerceAtLeast(0) }
    fun scrollY(tab: SlimTab = selected): Int = positions[tab] ?: 0
}
