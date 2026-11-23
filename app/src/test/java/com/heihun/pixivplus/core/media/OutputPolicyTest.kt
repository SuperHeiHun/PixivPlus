package com.heihun.pixivplus.core.media

import org.junit.Assert.*
import org.junit.Test

class OutputPolicyTest {
    @Test fun `empty preference selects public PixivPlus downloads`() {
        assertTrue(OutputPolicy.isDefault(""))
        assertTrue(OutputPolicy.isDefault(OutputPolicy.DEFAULT_FOLDER))
        assertFalse(OutputPolicy.isDefault("content://folder/tree/custom"))
        assertEquals("Download/PixivPlus/", OutputPolicy.RELATIVE_ROOT)
    }

    @Test fun `duplicate works get independent directories`() {
        assertEquals("作品 (3)", OutputPolicy.uniqueName("作品") { it in setOf("作品", "作品 (2)") })
    }

    @Test fun `cannot escape output directory via file name`() {
        for (name in listOf("..", ".", "../other", "a/b", "a\\b", "bad\u0000name")) {
            try { OutputPolicy.requireName(name); fail(name) } catch (_: IllegalArgumentException) { }
        }
    }
}
