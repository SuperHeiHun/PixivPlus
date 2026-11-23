package com.heihun.pixivplus.core.task

import android.content.Intent

/** 复用最近页面；不得继承原始深链中的 CLEAR_TOP/CLEAR_TASK/NEW_DOCUMENT 等破坏栈的 flags。 */
internal object TaskEntryPolicy {
    const val ACTIVITY_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT

    fun remainingTimeout(postTime: Long, timeout: Long, now: Long): Long =
        (timeout - (now - postTime).coerceAtLeast(0)).coerceAtLeast(0)
}
