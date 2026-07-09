package dev.agentic.ui.session

import dev.agentic.data.net.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-logic tests for the composer's @-mention parsing/filtering (SessionMention.kt). */
class SessionMentionTest {

    // ── activeMentionQuery ──────────────────────────────────────────────────

    @Test
    fun `bare at sign at text start activates with empty query`() {
        assertEquals(MentionQuery(0, ""), activeMentionQuery("@", 1))
    }

    @Test
    fun `at sign after whitespace activates and carries the typed query`() {
        assertEquals(MentionQuery(6, "fix"), activeMentionQuery("check @fix", 10))
        assertEquals(MentionQuery(5, ""), activeMentionQuery("line\n@", 6))
    }

    @Test
    fun `caret in the middle of a mention uses only the text before the caret`() {
        // caret between 'f' and 'x' of "@fx" → query is "f"
        assertEquals(MentionQuery(0, "f"), activeMentionQuery("@fx", 2))
    }

    @Test
    fun `email-like at sign does not activate`() {
        assertNull(activeMentionQuery("user@example", 12))
    }

    @Test
    fun `whitespace between at sign and caret deactivates`() {
        assertNull(activeMentionQuery("@abc def", 8))
    }

    @Test
    fun `no at sign means no mention`() {
        assertNull(activeMentionQuery("plain text", 5))
        assertNull(activeMentionQuery("", 0))
    }

    @Test
    fun `caret before or at the at sign does not activate`() {
        assertNull(activeMentionQuery("@abc", 0))
    }

    @Test
    fun `out of range caret is rejected`() {
        assertNull(activeMentionQuery("@abc", 9))
    }

    // ── filterMentionCandidates ─────────────────────────────────────────────

    private fun sess(id: String, prompt: String) = Session(id = id, prompt = prompt)

    private val sessions = listOf(
        sess("abcd1234-0000", "Fix the login bug"),
        sess("ef561234-0000", "Add dark mode"),
        sess("77881234-0000", "fix flaky tests"),
    )

    @Test
    fun `empty query keeps all candidates`() {
        assertEquals(sessions, filterMentionCandidates(sessions, ""))
    }

    @Test
    fun `query matches name substring case-insensitively`() {
        assertEquals(
            listOf(sessions[0], sessions[2]),
            filterMentionCandidates(sessions, "FIX"),
        )
    }

    @Test
    fun `query matches id prefix but not id substring`() {
        assertEquals(listOf(sessions[1]), filterMentionCandidates(sessions, "ef56"))
        // "1234" appears inside every id but prefixes none → only name matches could hit
        assertEquals(emptyList<Session>(), filterMentionCandidates(sessions, "1234"))
    }

    // ── mentionToken ────────────────────────────────────────────────────────

    @Test
    fun `token is at-session-colon plus 8 id chars and a trailing space`() {
        assertEquals(
            "@session:abcd1234 ",
            mentionToken("abcd1234-0000-0000-0000-000000000000"),
        )
    }
}
