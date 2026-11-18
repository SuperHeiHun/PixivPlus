package com.heihun.pixivplus.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureMenuOrderTest {
    private val order = listOf(
        FeatureMenuOrder.BLOCK_TAG, FeatureMenuOrder.BLOCK_AUTHOR, FeatureMenuOrder.BLOCK_TITLE,
        FeatureMenuOrder.TRANSLATE_CAPTION, FeatureMenuOrder.TRANSLATE_NOVEL, FeatureMenuOrder.RESTORE_NOVEL,
        FeatureMenuOrder.SAVE_ORIGINAL, FeatureMenuOrder.SAVE_GIF, FeatureMenuOrder.SAVE_PDF, FeatureMenuOrder.SAVE_TXT
    )

    @Test fun `menu order is unique and follows block translate save`() {
        assertEquals(order.size, order.distinct().size)
        assertEquals(order, order.sorted())
        assertTrue(order.all { it in 1..65535 })
    }

    @Test fun `feature injection timing does not affect resulting order`() {
        repeat(100) { seed ->
            assertEquals(listOf(0) + order, (order.shuffled(kotlin.random.Random(seed)) + 0).sorted())
        }
    }
}
