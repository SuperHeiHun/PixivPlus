package com.heihun.pixivplus.core.net

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterRulesTest {

    private fun item(
        uid: Long = 111222333L,
        name: String = "画师名",
        account: String = "artist_acc",
        title: String = "作品标题"
    ): JSONObject {
        return JSONObject()
            .put("id", 987654321L)
            .put("title", title)
            .put("user", JSONObject().put("id", uid).put("name", name).put("account", account))
    }

    @Test
    fun `author uid rule matches by id exactly`() {
        val rules = FilterRules(emptyList(), emptyList(), listOf("111222333"), false)
        assertTrue(rules.matches(item()))
        assertFalse(rules.matches(item(uid = 999L)))
    }

    @Test
    fun `digit rule does not substring match names`() {
        // 昵称包含相同数字也不应命中纯数字规则。
        val rules = FilterRules(emptyList(), emptyList(), listOf("111"), false)
        assertFalse(rules.matches(item(uid = 999111999L, name = "user111")))
    }

    @Test
    fun `author name and account rules still work`() {
        val rules = FilterRules(emptyList(), emptyList(), listOf("画师名"), false)
        assertTrue(rules.matches(item()))
        val byAccount = FilterRules(emptyList(), emptyList(), listOf("artist_acc"), false)
        assertTrue(byAccount.matches(item()))
        val miss = FilterRules(emptyList(), emptyList(), listOf("别人"), false)
        assertFalse(miss.matches(item()))
    }

    @Test
    fun `flat user id field is matched for uid rule`() {
        val flat = JSONObject().put("title", "t").put("user_id", 42L).put("user_name", "nm")
        val rules = FilterRules(emptyList(), emptyList(), listOf("42"), false)
        assertTrue(rules.matches(flat))
    }

    @Test
    fun `case insensitive name matching`() {
        val rules = FilterRules(emptyList(), emptyList(), listOf("ARTIST_ACC"), false)
        assertTrue(rules.matches(item()))
        val sensitive = FilterRules(emptyList(), emptyList(), listOf("ARTIST_ACC"), true)
        assertFalse(sensitive.matches(item()))
    }
}
