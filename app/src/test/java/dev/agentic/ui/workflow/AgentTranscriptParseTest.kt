package dev.agentic.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [splitAgentTranscript] — the pure parser that turns the server's combined
 * `**Task** … --- … **Output** …` markdown back into structured Task / Output parts.
 */
class AgentTranscriptParseTest {

    @Test fun `both sections split into task and output`() {
        val raw = "**Task**\n\nFind bugs in auth.\n\n---\n\n**Output**\n\nFound 3 bugs."
        val parts = splitAgentTranscript(raw)
        assertEquals("Find bugs in auth.", parts.task)
        assertEquals("Found 3 bugs.", parts.output)
    }

    @Test fun `task-only payload has null output`() {
        val parts = splitAgentTranscript("**Task**\n\nDo the thing.")
        assertEquals("Do the thing.", parts.task)
        assertNull(parts.output)
    }

    @Test fun `output-only payload has null task`() {
        val parts = splitAgentTranscript("**Output**\n\nAll done.")
        assertNull(parts.task)
        assertEquals("All done.", parts.output)
    }

    @Test fun `blank input yields nulls`() {
        splitAgentTranscript("").let { assertNull(it.task); assertNull(it.output) }
        splitAgentTranscript("   \n  ").let { assertNull(it.task); assertNull(it.output) }
    }

    @Test fun `unknown shape falls back to output so nothing is lost`() {
        val parts = splitAgentTranscript("just a plain blob with no markers")
        assertNull(parts.task)
        assertEquals("just a plain blob with no markers", parts.output)
    }

    @Test fun `leading and trailing whitespace is trimmed`() {
        val parts = splitAgentTranscript("  **Task**\n\nX\n\n---\n\n**Output**\n\nY  ")
        assertEquals("X", parts.task)
        assertEquals("Y", parts.output)
    }

    @Test fun `a horizontal rule inside the output is not mistaken for the section separator`() {
        // Only "\n\n---\n\n**Output**\n\n" separates sections; a bare "---" inside the output stays put.
        val raw = "**Task**\n\nT\n\n---\n\n**Output**\n\nA\n\n---\n\nB"
        val parts = splitAgentTranscript(raw)
        assertEquals("T", parts.task)
        assertEquals("A\n\n---\n\nB", parts.output)
    }
}
