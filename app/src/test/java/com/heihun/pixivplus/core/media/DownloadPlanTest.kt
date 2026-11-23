package com.heihun.pixivplus.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPlanTest {
    @Test fun `default download location survives retry serialization`() {
        val plan = DownloadPlan(123, "作品", OutputPolicy.DEFAULT_FOLDER, DownloadFormat.GIF)
        assertEquals(plan, DownloadPlan.decode(plan.encode()))
    }
    @Test fun `retry plans preserve selection naming and output options`() {
        val plan = DownloadPlan(123, "作者_标题", "content://folder/tree/test", DownloadFormat.ZIP, listOf(1, 3, 8), "links")
        assertEquals(plan, DownloadPlan.decode(plan.encode()))
    }
    @Test(expected = IllegalArgumentException::class) fun `cache directory traversal is rejected`() {
        DownloadPlan.decode(DownloadPlan(123, "作品", "content://folder/tree/test", DownloadFormat.GIF, cacheKey = "../outside").encode())
    }
}
