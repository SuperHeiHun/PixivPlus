package com.heihun.pixivplus.core.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

object DetailPagerResolver {

    private const val MAX_CONTEXT_DEPTH = 8

    private const val ADAPTER_FIELD = "D"

    private const val BINDING_FIELD = "E"

    private const val PAGER_FIELD = "i"

    private const val ADAPTER_ITEM_METHOD = "d"

    fun activityOf(context: Context?): Activity? {
        var current = context
        var depth = 0
        while (current is ContextWrapper && depth < MAX_CONTEXT_DEPTH) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) return null
            current = base
            depth++
        }
        return null
    }

    fun currentIndex(activity: Any?): Int? {
        val binding = ReflectUtil.readField(activity, BINDING_FIELD) ?: return null
        val pager = ReflectUtil.readField(binding, PAGER_FIELD) ?: return null
        val method = ReflectUtil.findMethod(pager.javaClass, "getCurrentItem") ?: return null
        return ReflectUtil.invoke(pager, method) as? Int
    }

    fun currentIllustId(activity: Any?): Long? {
        val illust = currentItem(activity) ?: return null
        val idMethod = ReflectUtil.findMethod(illust.javaClass, "getId") ?: return null
        return ReflectUtil.invoke(illust, idMethod) as? Long
    }

    fun itemAt(activity: Any?, index: Int): Any? {
        val adapter = ReflectUtil.readField(activity, ADAPTER_FIELD) ?: return null
        val method = ReflectUtil.findMethod(
            adapter.javaClass,
            ADAPTER_ITEM_METHOD,
            Int::class.javaPrimitiveType ?: return null
        ) ?: return null
        return ReflectUtil.invoke(adapter, method, index)
    }

    fun currentItem(activity: Any?): Any? {
        val index = currentIndex(activity) ?: return null
        return itemAt(activity, index)
    }

    fun isCurrentWork(activity: Any?, id: Long): Boolean {
        val current = currentIllustId(activity) ?: return true
        return current == id
    }
}
