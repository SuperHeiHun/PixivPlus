package com.heihun.pixivplus.ui.panel

import org.junit.Assert.*
import org.junit.Test

class SlimTabStateTest {
    @Test fun `defaults to sidebar with no saved scroll position`() {
        val state = SlimTabState()
        assertEquals(SlimTab.DRAWER, state.selected)
        for (tab in SlimTab.values()) assertEquals(0, state.scrollY(tab))
    }
    @Test fun `tabs keep independent scroll positions`() {
        val state = SlimTabState()
        assertTrue(state.select(SlimTab.BOTTOM, 360))
        assertTrue(state.select(SlimTab.MY_PAGE, 72))
        assertTrue(state.select(SlimTab.DRAWER, 140))
        assertEquals(360, state.scrollY())
        assertEquals(72, state.scrollY(SlimTab.BOTTOM))
        assertEquals(140, state.scrollY(SlimTab.MY_PAGE))
    }
    @Test fun `clicking selected tab does not reset saved scroll`() {
        val state = SlimTabState()
        state.remember(SlimTab.DRAWER, 90)
        assertFalse(state.select(SlimTab.DRAWER, 0))
        assertEquals(90, state.scrollY())
    }
    @Test fun `view rebuild preserves selection and clamps negative scroll`() {
        val state = SlimTabState()
        state.select(SlimTab.MY_PAGE, 80)
        state.remember(state.selected, 240)
        assertEquals(SlimTab.MY_PAGE, state.selected)
        assertEquals(240, state.scrollY())
        state.remember(SlimTab.BOTTOM, -10)
        assertEquals(0, state.scrollY(SlimTab.BOTTOM))
    }
    @Test fun `new panel session does not inherit selection from another`() {
        val first = SlimTabState()
        first.select(SlimTab.MY_PAGE, 80)
        assertEquals(SlimTab.DRAWER, SlimTabState().selected)
    }
}
