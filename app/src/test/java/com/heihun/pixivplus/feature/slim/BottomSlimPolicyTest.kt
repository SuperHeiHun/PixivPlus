package com.heihun.pixivplus.feature.slim

import org.junit.Assert.*
import org.junit.Test

class BottomSlimPolicyTest {
    @Test fun `cannot configure every entry hidden`() {
        assertFalse(BottomSlimPolicy.canHide(BottomSlimPolicy.entries.toSet()))
        for (entry in BottomSlimPolicy.entries) assertTrue(BottomSlimPolicy.canHide(BottomSlimPolicy.entries.toSet() - entry))
    }
    @Test fun `restored all hidden settings keep first available entry`() {
        assertEquals(setOf("mypage"), BottomSlimPolicy.effectiveHidden(BottomSlimPolicy.entries.toSet(), listOf("search", "mypage")))
    }
    @Test fun `single available item remains usable`() {
        assertTrue(BottomSlimPolicy.effectiveHidden(setOf("notifications"), listOf("notifications")).isEmpty())
    }
    @Test fun `unknown missing duplicate and empty entries do not break fallback`() {
        assertTrue(BottomSlimPolicy.effectiveHidden(setOf("unknown"), emptyList()).isEmpty())
        assertTrue(BottomSlimPolicy.effectiveHidden(setOf("unknown"), listOf("unknown")).isEmpty())
        assertEquals(setOf("search"), BottomSlimPolicy.effectiveHidden(setOf("search"), listOf("home", "search", "search")))
        assertEquals(setOf("home", "search"), BottomSlimPolicy.parse(" home,search,home, ,"))
    }
}
