package com.heihun.pixivplus.feature.slim

internal object BottomSlimPolicy {
    val entries = listOf("home", "search", "new_works", "notifications", "mypage")

    fun parse(value: String): Set<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun canHide(hidden: Set<String>): Boolean = entries.any { it !in hidden }

    /** 备份/旧配置全部隐藏或宿主只保留部分入口时，仍保留实际可用的一个。 */
    fun effectiveHidden(hidden: Set<String>, available: List<String>): Set<String> {
        val managed = available.filter { it in entries }.distinct()
        val result = hidden.intersect(managed.toSet()).toMutableSet()
        if (managed.isNotEmpty() && managed.all { it in result }) result.remove(managed.first())
        return result
    }
}
