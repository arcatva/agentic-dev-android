package dev.agentic.ui.diagnostics

import dev.agentic.domain.splitAttachments
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Diagnostics attach flow composes its follow-up prompt with [composeAttachPrompt]; the
 * transcript side parses it back with [splitAttachments]. These tests pin the round-trip so the
 * two never drift apart (a drift = the attachment chip silently stops rendering).
 */
class ComposeAttachPromptTest {

    @Test
    fun `message plus marker round-trips through splitAttachments`() {
        val prompt = composeAttachPrompt(
            "Please investigate the crash.",
            "uploads/agentic-logs-20260709-001122.zip",
        )
        val (clean, paths) = splitAttachments(prompt)
        assertEquals("Please investigate the crash.", clean)
        assertEquals(listOf("uploads/agentic-logs-20260709-001122.zip"), paths)
    }

    @Test
    fun `blank message still yields a parseable marker-only prompt`() {
        val prompt = composeAttachPrompt("   ", "uploads/agentic-logs.zip")
        val (clean, paths) = splitAttachments(prompt)
        assertEquals("", clean)
        assertEquals(listOf("uploads/agentic-logs.zip"), paths)
    }

    @Test
    fun `trailing whitespace in message does not break the marker`() {
        val prompt = composeAttachPrompt("look at this\n", "uploads/x.zip")
        val (clean, paths) = splitAttachments(prompt)
        assertEquals("look at this", clean)
        assertEquals(listOf("uploads/x.zip"), paths)
    }
}
