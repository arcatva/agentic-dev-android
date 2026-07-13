package dev.agentic.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-logic tests for the composer's `/`-command palette (CommandPalette.kt). */
class CommandPaletteTest {

    // ── activeCommandQuery: only fires for a command at the message START ──

    @Test
    fun `bare slash at start activates with empty query`() {
        assertEquals(CommandQuery(0, ""), activeCommandQuery("/", 1))
    }

    @Test
    fun `typing a command name carries the query`() {
        assertEquals(CommandQuery(0, "lf"), activeCommandQuery("/lf", 3))
        assertEquals(CommandQuery(0, "ce-code"), activeCommandQuery("/ce-code", 8))
    }

    @Test
    fun `leading whitespace before the slash is allowed`() {
        assertEquals(CommandQuery(2, "lf"), activeCommandQuery("  /lf", 5))
    }

    @Test
    fun `caret mid-token uses only text before the caret`() {
        assertEquals(CommandQuery(0, "l"), activeCommandQuery("/lfg", 2))
    }

    @Test
    fun `a space after the command closes the palette`() {
        assertNull(activeCommandQuery("/lfg add a feature", 18))
    }

    @Test
    fun `slash not at message start never activates`() {
        assertNull(activeCommandQuery("please run /lfg", 15)) // mid-text
        assertNull(activeCommandQuery("a/b", 3)) // path-ish, not leading
    }

    @Test
    fun `ordinary prose never activates`() {
        assertNull(activeCommandQuery("add a feature", 5))
        assertNull(activeCommandQuery("", 0))
    }

    // ── filterCommands ──

    private val cmds = listOf(
        CommandItem("lfg", "pipeline", "[feature]"),
        CommandItem("ce-code-review"),
        CommandItem("ce-simplify-code"),
        CommandItem("ponytail-review"),
    )

    @Test
    fun `filter matches by name prefix case-insensitively`() {
        assertEquals(listOf("lfg"), filterCommands(cmds, "l").map { it.name })
        assertEquals(listOf("ce-code-review", "ce-simplify-code"), filterCommands(cmds, "CE-").map { it.name })
        assertEquals(4, filterCommands(cmds, "").size) // empty query keeps all
        assertEquals(0, filterCommands(cmds, "zzz").size)
    }

    // ── applyCommand: insert `/name ` replacing the typed token ──

    @Test
    fun `applyCommand replaces the typed token and puts caret past the trailing space`() {
        val (text, caret) = applyCommand("/lf", CommandQuery(0, "lf"), "lfg")
        assertEquals("/lfg ", text)
        assertEquals(5, caret) // just past "/lfg "
    }

    @Test
    fun `applyCommand preserves leading whitespace and any trailing text`() {
        val (text, caret) = applyCommand("  /ce extra", CommandQuery(2, "ce"), "ce-code-review")
        assertEquals("  /ce-code-review extra", text)
        assertEquals("  /ce-code-review ".length, caret)
    }
}
