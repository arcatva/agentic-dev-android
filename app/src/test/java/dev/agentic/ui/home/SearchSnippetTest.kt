package dev.agentic.ui.home

import dev.agentic.data.net.SearchField
import dev.agentic.data.net.SearchHit
import dev.agentic.data.net.SearchMatch
import dev.agentic.data.net.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [bestSnippetMatch] — the rule that decides which match (if any) is surfaced as a
 * highlighted snippet under a search result row. A match on a field the row already shows (its
 * prompt / repos) must NOT produce a snippet; a content match must; and among content matches the
 * one whose snippet actually contains the query (preferably prose, not the Ask JSON) wins so the
 * highlight is visible.
 */
class SearchSnippetTest {

    private fun match(field: SearchField, snippet: String = "x") =
        SearchMatch(field = field, snippet = snippet, lineIndex = 0)

    private fun hit(vararg matches: SearchMatch, prompt: String = "p") =
        SearchHit(session = Session(id = "s", prompt = prompt), score = 1f, matches = matches.toList())

    @Test fun `a prompt-only match shows no snippet (the prompt is already the row's main line)`() {
        assertNull(hit(match(SearchField.Title)).bestSnippetMatch("x"))
    }

    @Test fun `a repo-only match shows no snippet (repos are in the row subtitle)`() {
        assertNull(hit(match(SearchField.Repo)).bestSnippetMatch("x"))
    }

    @Test fun `no matches at all yields no snippet`() {
        assertNull(hit().bestSnippetMatch("x"))
    }

    @Test fun `a content match is surfaced`() {
        val m = match(SearchField.Answer, "the answer text")
        assertEquals(m, hit(m).bestSnippetMatch("answer"))
    }

    @Test fun `the prompt match is skipped in favour of the content match after it`() {
        val tool = match(SearchField.ToolDetail, "rm -rf build")
        assertEquals(tool, hit(match(SearchField.Title), tool).bestSnippetMatch("rm"))
    }

    @Test fun `non-row-visible metadata (branch, status, error) is surfaced too`() {
        assertEquals(SearchField.Branch, hit(match(SearchField.Repo), match(SearchField.Branch)).bestSnippetMatch("x")?.field)
        assertEquals(SearchField.Status, hit(match(SearchField.Status)).bestSnippetMatch("x")?.field)
        assertEquals(SearchField.Error, hit(match(SearchField.Error)).bestSnippetMatch("x")?.field)
    }

    @Test fun `the first eligible match wins when several contain the query`() {
        val first = match(SearchField.Answer, "shared one")
        val second = match(SearchField.ToolDetail, "shared two")
        assertEquals(first, hit(match(SearchField.Title), first, second).bestSnippetMatch("shared"))
    }

    @Test fun `a Prompt match equal to the row prompt shows no snippet (it just repeats the title line)`() {
        assertNull(hit(match(SearchField.Prompt, "fix login bug"), prompt = "fix login bug").bestSnippetMatch("login"))
    }

    @Test fun `an ellipsis-wrapped Prompt snippet equal to the row prompt is still suppressed`() {
        assertNull(hit(match(SearchField.Prompt, "...fix login bug..."), prompt = "fix login bug").bestSnippetMatch("login"))
    }

    @Test fun `an earlier-turn Prompt match (differs from the row prompt) is surfaced`() {
        val earlier = match(SearchField.Prompt, "add the search box")
        assertEquals(earlier, hit(earlier, prompt = "now fix the login bug").bestSnippetMatch("search"))
    }

    @Test fun `a windowed Prompt slice that differs from the row prompt is surfaced`() {
        val windowed = match(SearchField.Prompt, "...long earlier turn mentioning login here...")
        assertEquals(windowed, hit(windowed, prompt = "short title").bestSnippetMatch("login"))
    }

    // ── prefer a snippet that actually contains the query (the backend window can miss it) ──

    @Test fun `prefers a later match whose snippet contains the query over an earlier one that does not`() {
        val broken = match(SearchField.Answer, "windowed off the match, no keyword here")
        val good = match(SearchField.Notes, "this mentions login plainly")
        assertEquals(good, hit(broken, good).bestSnippetMatch("login"))
    }

    @Test fun `deprioritises the Ask JSON snippet when a prose match also contains the query`() {
        val ask = match(SearchField.Ask, """[{"question":"the login flow","options":[]}]""")
        val notes = match(SearchField.Notes, "notes about login")
        assertEquals(notes, hit(ask, notes).bestSnippetMatch("login"))
    }

    @Test fun `uses the Ask snippet when it is the only match containing the query`() {
        val ask = match(SearchField.Ask, """[{"question":"login flow"}]""")
        val other = match(SearchField.Notes, "no keyword present")
        assertEquals(ask, hit(ask, other).bestSnippetMatch("login"))
    }

    @Test fun `falls back to the first eligible match when no snippet contains the query`() {
        val a = match(SearchField.Answer, "alpha")
        val b = match(SearchField.Notes, "beta")
        assertEquals(a, hit(a, b).bestSnippetMatch("zzz"))
    }
}
