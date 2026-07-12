package dev.agentic.ui.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatGptOAuthLoopbackTest {
    @Test
    fun parses_code_and_state() {
        val r = ChatGptOAuthLoopback.parseCallback("GET /auth/callback?code=abc123&state=xyz HTTP/1.1")
        assertEquals("abc123" to "xyz", r)
    }

    @Test
    fun url_decodes_values() {
        val r = ChatGptOAuthLoopback.parseCallback("GET /auth/callback?code=a%2Fb&state=s%20t HTTP/1.1")
        assertEquals("a/b" to "s t", r)
    }

    @Test
    fun order_independent_and_ignores_extra_params() {
        val r = ChatGptOAuthLoopback.parseCallback("GET /auth/callback?state=s&code=c&scope=x HTTP/1.1")
        assertEquals("c" to "s", r)
    }

    @Test
    fun missing_code_or_state_or_malformed_is_null() {
        assertNull(ChatGptOAuthLoopback.parseCallback("GET /auth/callback?state=xyz HTTP/1.1"))
        assertNull(ChatGptOAuthLoopback.parseCallback("GET /auth/callback?code=abc HTTP/1.1"))
        assertNull(ChatGptOAuthLoopback.parseCallback("GET /auth/callback HTTP/1.1"))
        assertNull(ChatGptOAuthLoopback.parseCallback("garbage"))
        // empty values are treated as absent
        assertNull(ChatGptOAuthLoopback.parseCallback("GET /auth/callback?code=&state=s HTTP/1.1"))
    }
}
