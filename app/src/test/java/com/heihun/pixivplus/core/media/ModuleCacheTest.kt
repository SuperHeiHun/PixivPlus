package com.heihun.pixivplus.core.media

import org.junit.Assert.*
import org.junit.Test

class ModuleCacheTest {
    @Test fun `cache cleanup targets only module owned data directories`() {
        assertTrue(ModuleCache.ownsDirectoryName("pixivplus_downloads"))
        assertTrue(ModuleCache.ownsDirectoryName("plp_bodies"))
        assertTrue(ModuleCache.ownsDirectoryName("plp-media-${java.util.UUID.randomUUID()}"))
        assertFalse(ModuleCache.ownsDirectoryName("plp_libs"))
        assertFalse(ModuleCache.ownsDirectoryName("coil"))
        assertFalse(ModuleCache.ownsDirectoryName("plp-media-user-folder"))
        assertFalse(ModuleCache.ownsDirectoryName("shared_prefs"))
    }
}
