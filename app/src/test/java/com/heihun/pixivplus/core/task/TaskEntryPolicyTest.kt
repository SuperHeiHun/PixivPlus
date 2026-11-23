package com.heihun.pixivplus.core.task

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskEntryPolicyTest {
    @Test fun `notification reuses the origin without clearing its activity stack`() {
        val flags = TaskEntryPolicy.ACTIVITY_FLAGS
        assertEquals(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT, flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        assertEquals(Intent.FLAG_ACTIVITY_SINGLE_TOP, flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
        assertEquals(0, flags and (Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    }

    @Test fun `refreshing an entry preserves rather than extends its timeout`() {
        assertEquals(40000L, TaskEntryPolicy.remainingTimeout(1000, 60000, 21000))
        assertEquals(0L, TaskEntryPolicy.remainingTimeout(1000, 60000, 61000))
        assertEquals(0L, TaskEntryPolicy.remainingTimeout(1000, 60000, 71000))
    }
}
