package com.heihun.pixivplus.feature.drawer

import org.junit.Assert.*
import org.junit.Test

class DrawerVisibilityPolicyTest {
    private val notification = "drawer_menu_notification"
    private val tasks = "drawer_menu_pixivplus_tasks"

    @Test fun `notification stays hidden after repeated host visibility updates`() {
        repeat(20) {
            assertTrue(DrawerVisibilityPolicy.mustHide(notification, true, setOf(notification), true))
        }
    }

    @Test fun `rebuilt notification items use stable resource name rather than object identity`() {
        val rebuiltName = String(notification.toCharArray())
        assertTrue(DrawerVisibilityPolicy.mustHide(rebuiltName, true, setOf(notification), true))
    }

    @Test fun `restoring all menus permits host to show notifications`() {
        assertFalse(DrawerVisibilityPolicy.mustHide(notification, false, setOf(notification), true))
    }

    @Test fun `unselected items and unrelated menus are not suppressed`() {
        assertFalse(DrawerVisibilityPolicy.mustHide(notification, true, emptySet(), true))
        assertFalse(DrawerVisibilityPolicy.mustHide("other_notification", true, setOf("other_notification"), true))
        assertFalse(DrawerVisibilityPolicy.mustHide("drawer_menu_home", true, setOf(notification), true))
    }

    @Test fun `task entry respects its own switch independently of slim mode`() {
        for (slim in listOf(false, true)) {
            assertTrue(DrawerVisibilityPolicy.mustHide(tasks, slim, emptySet(), false))
            assertFalse(DrawerVisibilityPolicy.mustHide(tasks, slim, emptySet(), true))
        }
        assertTrue(DrawerVisibilityPolicy.mustHide(tasks, true, setOf(tasks), true))
    }
}
