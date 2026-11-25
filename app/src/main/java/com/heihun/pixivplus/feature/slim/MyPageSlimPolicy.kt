package com.heihun.pixivplus.feature.slim

internal object MyPageSlimPolicy {
    val rowResources = linkedMapOf(
        "works" to "core_string_my_works",
        "collection" to "core_string_collection",
        "history" to "core_string_browsing_history",
        "marker" to "core_string_novel_marker",
        "following" to "core_string_connection_following",
        "follower" to "core_string_connection_follower",
        "mypixiv" to "core_string_connection_mypixiv",
        "mute" to "core_string_mute_settings",
        "help" to "core_string_help_and_feedback",
        "about" to "core_string_about_app"
    )
    private val icons = mapOf("works" to "feature_mypage_ic_works", "collection" to "feature_mypage_ic_collection",
        "history" to "feature_mypage_ic_history", "marker" to "feature_mypage_ic_marker",
        "mute" to "feature_mypage_ic_mute", "help" to "feature_mypage_ic_help", "about" to "feature_mypage_ic_about")
    val entries = rowResources.keys + setOf("premium", "upload")

    fun hidden(enabled: Boolean, configured: Set<String>): Set<String> = if (enabled) configured.intersect(entries) else emptySet()

    fun rowKey(iconName: String?, title: String, labels: Map<String, String>): String? {
        if (iconName != null) return icons.entries.firstOrNull { it.value == iconName }?.key
        return listOf("following", "follower", "mypixiv").firstOrNull { labels[it] == title }
    }
}
