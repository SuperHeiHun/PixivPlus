package com.heihun.pixivplus.feature.slim

import org.junit.Assert.*
import org.junit.Test

class MyPageSlimPolicyTest {
    @Test fun `disabled simplification keeps every row`() {
        assertTrue(MyPageSlimPolicy.hidden(false, MyPageSlimPolicy.entries).isEmpty())
    }
    @Test fun `account profile settings and unknown entries cannot be hidden`() {
        assertEquals(setOf("works"), MyPageSlimPolicy.hidden(true, setOf("works", "settings", "profile", "account", "unknown")))
    }
    @Test fun `icon based identification does not depend on host language`() {
        assertEquals("history", MyPageSlimPolicy.rowKey("feature_mypage_ic_history", "閲覧履歴", emptyMap()))
        assertNull(MyPageSlimPolicy.rowKey("other_history_icon", "History", mapOf("following" to "History")))
    }
    @Test fun `iconless connection rows use current host labels`() {
        val labels = mapOf("following" to "关注用户", "follower" to "粉丝", "mypixiv" to "好P友")
        assertEquals("following", MyPageSlimPolicy.rowKey(null, "关注用户", labels))
        assertEquals("follower", MyPageSlimPolicy.rowKey(null, "粉丝", labels))
        assertNull(MyPageSlimPolicy.rowKey(null, "设置", labels))
    }
    @Test fun `all optional entries may hide while protected entries remain outside policy`() {
        assertEquals(12, MyPageSlimPolicy.hidden(true, MyPageSlimPolicy.entries).size)
        assertFalse("settings" in MyPageSlimPolicy.entries)
        assertFalse("profile" in MyPageSlimPolicy.entries)
    }
}
