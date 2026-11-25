package com.heihun.pixivplus.core.net

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebViewingSettingsTest {
    private class Server {
        var cookie = "PHPSESSID=test-session"
        var id = "123"
        var level = 0
        var age: Int? = 20
        var ignoreSave = false
        var changeSessionOnPage = false
        var postError = false
        val posts = mutableListOf<Int>()
        val token = "a".repeat(32)
        val client = WebViewingSettings({ cookie }) { path, method, headers, body ->
            assertEquals(cookie, headers["Cookie"])
            when (path) {
                "/ajax/user/self" -> ok(JSONObject().put("userId", id).put("xRestrict", level))
                "/ajax/settings/self" -> ok(JSONObject().put("user_status", JSONObject().put("age", age)))
                "/settings/viewing" -> {
                    if (changeSessionOnPage) cookie = "PHPSESSID=other-session"
                    ProxyResponse(200, "<script>{\"token\":\"$token\"}</script>")
                }
                "/ajax/settings/user_x_restrict" -> {
                    assertEquals("POST", method)
                    assertEquals(token, headers["x-csrf-token"])
                    assertEquals("application/json; charset=utf-8", headers["Content-Type"])
                    val value = JSONObject(String(requireNotNull(body), Charsets.UTF_8)).getInt("userXRestrict")
                    posts.add(value)
                    if (!ignoreSave && !postError) level = value
                    if (postError) ProxyResponse(200, "{\"error\":true}") else ok(JSONObject())
                }
                else -> error("Unexpected endpoint $path")
            }
        }
        private fun ok(body: JSONObject) = ProxyResponse(200,
            JSONObject().put("error", false).put("body", body).toString())
    }

    private fun problem(expected: WebViewingProblem, action: () -> Unit) {
        try { action(); fail("Expected $expected") }
        catch (e: WebViewingException) { assertEquals(expected, e.problem) }
    }

    @Test fun `two switches map to the official three levels`() {
        for (level in 0..2) {
            val state = WebViewingState("123", 20, level)
            assertEquals(level >= 1, state.r18)
            assertEquals(level == 2, state.r18g)
            assertEquals(0, state.withR18(false))
            assertEquals(maxOf(1, level), state.withR18(true))
            assertEquals(2, state.withR18g(true))
            assertEquals(minOf(1, level), state.withR18g(false))
        }
    }

    @Test fun `save posts official value with csrf and verifies all levels`() {
        val server = Server()
        var state = server.client.load()
        for (level in listOf(1, 2, 1, 0)) {
            state = server.client.save(state, level)
            assertEquals(level, state.level)
        }
        assertEquals(listOf(1, 2, 1, 0), server.posts)
    }

    @Test fun `missing login never requests account data`() {
        val client = WebViewingSettings({ "other=value" }) { _, _, _, _ -> error("Must not request") }
        problem(WebViewingProblem.LOGIN) { client.load() }
    }

    @Test fun `minor and unknown ages never write`() {
        for (age in listOf(null, 0, 17, -1, 151)) {
            val server = Server().apply { this.age = age }
            val state = server.client.load()
            assertFalse(state.canEdit)
            problem(WebViewingProblem.AGE) { server.client.save(state, 1) }
            assertTrue(server.posts.isEmpty())
        }
        assertTrue(WebViewingState("123", 18, 0).canEdit)
    }

    @Test fun `account or settings changes prevent stale writes`() {
        for (changeAccount in listOf(true, false)) {
            val server = Server()
            val state = server.client.load()
            if (changeAccount) server.id = "456" else server.level = 1
            problem(WebViewingProblem.SESSION) { server.client.save(state, 2) }
            assertTrue(server.posts.isEmpty())
        }
    }

    @Test fun `session changes while reading csrf prevent writes`() {
        val server = Server()
        val state = server.client.load()
        server.changeSessionOnPage = true
        problem(WebViewingProblem.SESSION) { server.client.save(state, 1) }
        assertTrue(server.posts.isEmpty())
    }

    @Test fun `successful http without verified state is not a successful save`() {
        val server = Server().apply { ignoreSave = true }
        problem(WebViewingProblem.VERIFY) { server.client.save(server.client.load(), 2) }
    }

    @Test fun `api error is not a successful save`() {
        val server = Server().apply { postError = true }
        problem(WebViewingProblem.RESPONSE) { server.client.save(server.client.load(), 1) }
    }

    @Test fun `unknown response html redirects and malformed values fail closed`() {
        for (response in listOf(ProxyResponse(200, "{}"), ProxyResponse(200, "<html>login</html>"),
            ProxyResponse(302, ""), ProxyResponse(500, "{}"), ProxyResponse(200, "{\"error\":0}"))) {
            problem(WebViewingProblem.RESPONSE) { WebViewingSettings.json(response) }
        }
        problem(WebViewingProblem.LOGIN) { WebViewingSettings.json(ProxyResponse(403, "{}")) }
        problem(WebViewingProblem.RESPONSE) {
            WebViewingSettings.parse(JSONObject("{\"userId\":\"123\",\"xRestrict\":9}"), JSONObject())
        }
    }

    @Test fun `csrf accepts raw and html encoded json but not missing or invalid token`() {
        val token = "abc123".repeat(8)
        assertEquals(token, WebViewingSettings.csrf("{\"token\":\"$token\"}"))
        assertEquals(token, WebViewingSettings.csrf("{&quot;token&quot;:&quot;$token&quot;}"))
        val serialized = JSONObject().put("api", JSONObject().put("token", token)).toString()
        val next = JSONObject().put("props", JSONObject().put("pageProps",
            JSONObject().put("serverSerializedPreloadedState", serialized))).toString()
        assertEquals(token, WebViewingSettings.csrf("<script id=\"__NEXT_DATA__\" type=\"application/json\">$next</script>"))
        assertNull(WebViewingSettings.csrf("{\"token\":\"bad\"}"))
        assertNull(WebViewingSettings.csrf("<html>login</html>"))
    }
}
