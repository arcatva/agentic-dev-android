package dev.agentic.data.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerErrorTest {
    @Test fun `extracts and unescapes the server error field from a transport dump`() {
        val e = RuntimeException(
            "Server error(POST https://192.168.0.2:7420/api/plugins: 500 . Text: " +
                "\"{\\\"error\\\":\\\"Failed to install plugin \\\\\\\"x@y\\\\\\\": not found.\\\\n\\\"}\"",
        )
        // The regex works on the RAW message; Ktor messages carry the body verbatim, so use one:
        val real = RuntimeException(
            "Server error(POST https://h/api/plugins: 500 . Text: " +
                """"{"error":"Plugin \"ponytail\" not found in marketplace \"ponytail\".\n"}"""",
        )
        assertEquals("Plugin \"ponytail\" not found in marketplace \"ponytail\".", real.serverError())
        // No error field → raw message untouched.
        val plain = RuntimeException("connect timeout")
        assertEquals("connect timeout", plain.serverError())
        // Suppress unused warning for the doubly-escaped variant above.
        check(e.message != null)
    }
}
